"""Image banane wala engine (diffusers). Model load, LoRA, Turbo, txt2img / img2img / inpaint."""
from __future__ import annotations

import gc
import random
import threading
import time
from dataclasses import dataclass, field
from typing import Callable

import torch
from PIL import Image

from . import paths
from .hardware import HW
from .models import CONFIG_REPOS, LCM_LORA, NEED_VRAM, ModelInfo

SAMPLERS = ["Default", "DPM++ 2M Karras", "DPM++ 2M SDE Karras", "Euler a", "Euler", "UniPC", "DDIM"]


def _make_scheduler(name: str, base_config):
    from diffusers import (DDIMScheduler, DPMSolverMultistepScheduler, EulerAncestralDiscreteScheduler,
                           EulerDiscreteScheduler, LCMScheduler, UniPCMultistepScheduler)
    table = {
        "DPM++ 2M Karras": (DPMSolverMultistepScheduler, {"use_karras_sigmas": True}),
        "DPM++ 2M SDE Karras": (DPMSolverMultistepScheduler,
                                {"use_karras_sigmas": True, "algorithm_type": "sde-dpmsolver++"}),
        "Euler a": (EulerAncestralDiscreteScheduler, {}),
        "Euler": (EulerDiscreteScheduler, {}),
        "UniPC": (UniPCMultistepScheduler, {}),
        "DDIM": (DDIMScheduler, {}),
        "LCM": (LCMScheduler, {}),
    }
    cls, kw = table[name]
    return cls.from_config(base_config, **kw)


@dataclass
class GenRequest:
    prompt: str
    negative: str = ""
    mode: str = "txt2img"            # txt2img | img2img | inpaint
    width: int = 512
    height: int = 512
    steps: int = 25
    cfg: float = 7.0
    seed: int = -1
    count: int = 1
    sampler: str = "Default"
    turbo: bool = False
    loras: list[tuple[str, float]] = field(default_factory=list)
    init_image: Image.Image | None = None
    mask: Image.Image | None = None
    strength: float = 0.6
    tile_vae: bool = False


class Engine:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.info: ModelInfo | None = None
        self.base = None
        self.pipes: dict[str, object] = {}
        self.offload: str | None = None    # None | "model" | "sequential"
        self.offload_owner: str | None = None
        self.sched_config = None
        self.lora_state: tuple = (False, ())
        self.cancel = False

    # ------------------------------------------------------------------ loading
    def unload(self) -> None:
        self.base = None
        self.pipes = {}
        self.info = None
        self.lora_state = (False, ())
        gc.collect()
        if HW.device == "cuda":
            torch.cuda.empty_cache()
        elif HW.device == "mps":
            torch.mps.empty_cache()

    def load(self, info: ModelInfo, log: Callable[[str], None] = print) -> None:
        if self.info and self.info.key == info.key and self.base is not None:
            return
        self.unload()
        from diffusers import (AutoPipelineForText2Image, StableDiffusionPipeline,
                               StableDiffusionXLPipeline)
        dtype = HW.dtype(info.family)
        log(f"⏳ Model load ho raha hai: {info.name} ({HW.device}, {str(dtype).replace('torch.', '')}) ...")
        t = time.time()
        extra = {"safety_checker": None, "requires_safety_checker": False} if info.family == "sd15" else {}
        if info.kind == "single":
            cls = StableDiffusionXLPipeline if info.family == "sdxl" else StableDiffusionPipeline
            pipe = cls.from_single_file(info.source, config=CONFIG_REPOS[info.family], torch_dtype=dtype, **extra)
        else:
            pipe = None
            last_err = None
            for variant in ("fp16", None):
                try:
                    pipe = AutoPipelineForText2Image.from_pretrained(info.source, torch_dtype=dtype,
                                                                     variant=variant, **extra)
                    break
                except Exception as e:  # variant nahi mila -> bina variant try karo
                    last_err = e
            if pipe is None:
                raise RuntimeError(f"Model load nahi hua: {last_err}")
        self.base = pipe
        self.info = info
        self.sched_config = pipe.scheduler.config
        self.sched_cls = pipe.scheduler.__class__
        self._place(pipe)
        self.pipes = {"txt2img": pipe}
        self.offload_owner = "txt2img"
        log(f"✅ Model ready ({time.time() - t:.0f}s)")

    def _place(self, pipe) -> None:
        fam = self.info.family
        if HW.device == "cuda":
            need = NEED_VRAM.get(fam, 8)
            if fam == "flux" and HW.vram_gb < 12:
                pipe.enable_sequential_cpu_offload()
                self.offload = "sequential"
            elif HW.vram_gb < need:
                pipe.enable_model_cpu_offload()
                self.offload = "model"
            else:
                pipe.to("cuda")
                self.offload = None
        else:
            pipe.to(HW.device)
            self.offload = None
            if HW.device == "mps" or HW.ram_gb and HW.ram_gb < 12:
                pipe.enable_attention_slicing()

    def _pipe_for(self, mode: str):
        from diffusers import AutoPipelineForImage2Image, AutoPipelineForInpainting
        if mode not in self.pipes:
            cls = AutoPipelineForImage2Image if mode == "img2img" else AutoPipelineForInpainting
            self.pipes[mode] = cls.from_pipe(self.base)
        p = self.pipes[mode]
        if self.offload and self.offload_owner != mode:  # offload hooks naye pipe par lagao
            if self.offload == "sequential":
                p.enable_sequential_cpu_offload()
            else:
                p.enable_model_cpu_offload()
            self.offload_owner = mode
        return p

    def _set_loras(self, turbo: bool, loras: list[tuple[str, float]], log) -> bool:
        fam = self.info.family
        use_turbo = turbo and not self.info.turbo_native and fam in LCM_LORA
        want = (use_turbo, tuple(loras))
        if want == self.lora_state:
            return use_turbo
        if self.lora_state != (False, ()):
            self.base.unload_lora_weights()
        names, weights = [], []
        if use_turbo:
            log("⚡ Turbo (LCM-LoRA) load ho raha hai...")
            self.base.load_lora_weights(LCM_LORA[fam], weight_name="pytorch_lora_weights.safetensors",
                                        adapter_name="lcm")
            names.append("lcm"); weights.append(1.0)
        for i, (fname, w) in enumerate(loras):
            log(f"🎭 LoRA load: {fname}")
            self.base.load_lora_weights(str(paths.LORA), weight_name=fname, adapter_name=f"user{i}")
            names.append(f"user{i}"); weights.append(float(w))
        if names:
            self.base.set_adapters(names, adapter_weights=weights)
        self.lora_state = want
        return use_turbo

    # ------------------------------------------------------------------ generation
    def generate(self, req: GenRequest, on_step: Callable[[int, int, int], None] | None = None,
                 log: Callable[[str], None] = print):
        """Generator: har image ke baad (image, seed) yield karta hai."""
        if self.base is None:
            raise RuntimeError("Pehle model load karo.")
        self.cancel = False
        fam = self.info.family
        use_turbo = self._set_loras(req.turbo, req.loras, log)
        pipe = self._pipe_for(req.mode)

        # scheduler
        if fam != "flux":
            if use_turbo:
                pipe.scheduler = _make_scheduler("LCM", self.sched_config)
            elif req.sampler != "Default" and not self.info.turbo_native:
                pipe.scheduler = _make_scheduler(req.sampler, self.sched_config)
            else:
                pipe.scheduler = self.sched_cls.from_config(self.sched_config)

        # badi image par VAE tiling (memory bachao)
        try:
            if (req.tile_vae or req.width * req.height > 1024 * 1024
                    or (HW.device == "cpu" and req.width * req.height > 768 * 768)):
                pipe.vae.enable_tiling()
            else:
                pipe.vae.disable_tiling()
        except Exception:
            pass

        seed = req.seed if req.seed is not None and req.seed >= 0 else random.randint(0, 2**32 - 1)
        for i in range(max(1, req.count)):
            if self.cancel:
                break
            s = (seed + i) % (2**32)
            gen = torch.Generator("cpu").manual_seed(s)
            kw = dict(prompt=req.prompt, num_inference_steps=int(req.steps), guidance_scale=float(req.cfg),
                      generator=gen)
            if fam != "flux":
                kw["negative_prompt"] = req.negative or None
            else:
                kw["max_sequence_length"] = 256
            if req.mode == "txt2img":
                kw.update(width=int(req.width), height=int(req.height))
            else:
                img = req.init_image.convert("RGB").resize((req.width, req.height), Image.LANCZOS)
                kw.update(image=img, strength=float(req.strength))
                if req.mode == "inpaint":
                    kw.update(mask_image=req.mask.convert("L").resize((req.width, req.height)),
                              width=int(req.width), height=int(req.height))
                # img2img mein steps*strength chalte hain; kam se kam 1 step pakka karo
                if int(req.steps) * float(req.strength) < 1:
                    kw["num_inference_steps"] = int(1 / max(0.05, float(req.strength))) + 1

            total = kw["num_inference_steps"]
            if req.mode != "txt2img":
                total = max(1, int(total * float(req.strength)))

            def _cb(p, step, timestep, cbk, _i=i, _total=total):
                if on_step:
                    on_step(_i, min(step + 1, _total), _total)
                if self.cancel:
                    p._interrupt = True
                return cbk

            kw["callback_on_step_end"] = _cb
            with torch.inference_mode():
                out = pipe(**kw)
            yield out.images[0], s

    # ------------------------------------------------------------------ extras
    def ai_upscale(self, img: Image.Image, prompt: str, strength: float = 0.3, scale: float = 2.0,
                   steps: int = 20, turbo: bool = False, on_step=None, log=print) -> Image.Image:
        w, h = int(img.width * scale) // 8 * 8, int(img.height * scale) // 8 * 8
        big = img.convert("RGB").resize((w, h), Image.LANCZOS)
        req = GenRequest(prompt=prompt or "highly detailed, sharp focus, best quality",
                         negative="blurry, lowres, artifacts", mode="img2img", width=w, height=h,
                         steps=max(steps, 4), cfg=self.info.cfg if not turbo else 1.5,
                         init_image=big, strength=strength, turbo=turbo,
                         loras=list(self.lora_state[1]), tile_vae=True)
        if self.info.turbo_native or turbo:
            req.cfg = min(req.cfg, 2.0)
        for im, _ in self.generate(req, on_step=on_step, log=log):
            return im
        return big


ENGINE = Engine()
