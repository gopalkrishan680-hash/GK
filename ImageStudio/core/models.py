"""Model catalog + local models ki khoj.

Teen tarah ke models chalte hain:
1. Catalog wale (Hugging Face se ek baar download, phir offline)
2. models/checkpoints/ mein rakhi .safetensors files (Civitai etc. se)
3. models/diffusers/ mein rakhe diffusers-format folders
"""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass, field
from pathlib import Path

from . import paths

# Base family -> default resolution
BASE_RES = {"sd15": 512, "sdxl": 1024, "flux": 1024}
# Bina offload ke aaram se chalne ke liye lagbhag VRAM (GB)
NEED_VRAM = {"sd15": 4, "sdxl": 9, "flux": 24}

# Turbo (LCM-LoRA): kisi bhi SD1.5 / SDXL model ko 4-8 steps mein chalata hai
LCM_LORA = {
    "sd15": "latent-consistency/lcm-lora-sdv1-5",
    "sdxl": "latent-consistency/lcm-lora-sdxl",
}
# .safetensors single-file models ko offline load karne ke liye config files
CONFIG_REPOS = {
    "sd15": "stable-diffusion-v1-5/stable-diffusion-v1-5",
    "sdxl": "stabilityai/stable-diffusion-xl-base-1.0",
}


@dataclass
class ModelInfo:
    key: str
    name: str
    family: str                 # sd15 | sdxl | flux
    source: str                 # HF repo id ya local path
    kind: str = "hf"            # hf | single | folder
    steps: int = 25
    cfg: float = 7.0
    turbo_native: bool = False  # model khud hi fast hai (LCM-LoRA ki zaroorat nahi)
    size_gb: float = 0.0
    desc: str = ""
    license: str = ""
    tags: list[str] = field(default_factory=list)

    @property
    def label(self) -> str:
        return self.name


CATALOG: list[ModelInfo] = [
    ModelInfo("dreamshaper8", "DreamShaper 8 (All-rounder, SD1.5)", "sd15", "Lykon/dreamshaper-8",
              steps=25, cfg=7.0, size_gb=2.1,
              desc="Har type ki image: photo, art, fantasy, anime. CPU + Turbo mode par bhi chalta hai.",
              license="CreativeML OpenRAIL-M", tags=["recommended", "cpu"]),
    ModelInfo("realvision51", "Realistic Vision 5.1 (Real photo, SD1.5)", "sd15",
              "SG161222/Realistic_Vision_V5.1_noVAE", steps=25, cfg=6.0, size_gb=2.1,
              desc="Asli jaisi photos, insaan, products. CPU + Turbo par bhi theek.",
              license="CreativeML OpenRAIL-M", tags=["cpu"]),
    ModelInfo("anything5", "Anything V5 (Anime, SD1.5)", "sd15", "stablediffusionapi/anything-v5",
              steps=25, cfg=7.0, size_gb=2.1,
              desc="Anime / manga / cartoon style.", license="CreativeML OpenRAIL-M", tags=["cpu"]),
    ModelInfo("sdxlturbo", "SDXL Turbo (Super fast, 512px)", "sdxl", "stabilityai/sdxl-turbo",
              steps=2, cfg=0.0, turbo_native=True, size_gb=6.9,
              desc="1-4 steps mein image. GPU (6GB+) par second mein. Sirf personal / non-commercial use.",
              license="Stability AI Non-Commercial", tags=["gpu"]),
    ModelInfo("dreamshaperxl", "DreamShaper XL Turbo (HD 1024px)", "sdxl", "Lykon/dreamshaper-xl-v2-turbo",
              steps=6, cfg=2.0, turbo_native=True, size_gb=6.9,
              desc="Badi HD 1024px images, fast. 8GB+ GPU best.", license="OpenRAIL++",
              tags=["recommended-gpu", "gpu"]),
    ModelInfo("sdxlbase", "Stable Diffusion XL 1.0 (HD, original)", "sdxl",
              "stabilityai/stable-diffusion-xl-base-1.0", steps=30, cfg=6.5, size_gb=6.9,
              desc="Official SDXL. Turbo mode ke saath fast.", license="OpenRAIL++", tags=["gpu"]),
    ModelInfo("animaginexl", "Animagine XL 3.1 (HD Anime)", "sdxl", "cagliostrolab/animagine-xl-3.1",
              steps=28, cfg=7.0, size_gb=6.9,
              desc="Best quality anime. Prompt mein tags likhein: '1girl, smile, ...'",
              license="Fair AI Public License", tags=["gpu"]),
    ModelInfo("fluxschnell", "FLUX.1 Schnell (Top quality, text bhi likhta hai)", "flux",
              "black-forest-labs/FLUX.1-schnell", steps=4, cfg=0.0, turbo_native=True, size_gb=33.0,
              desc="Sabse achhi quality + image mein sahi text. 12GB+ GPU aur 32GB RAM chahiye. ~33GB download.",
              license="Apache 2.0", tags=["pro"]),
]
CATALOG_BY_KEY = {m.key: m for m in CATALOG}


# ---------------------------------------------------------------- config (custom repos)
def load_config() -> dict:
    try:
        return json.loads(paths.CONFIG.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_config(cfg: dict) -> None:
    paths.CONFIG.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")


def add_custom_repo(repo: str, family: str) -> None:
    cfg = load_config()
    repos = cfg.setdefault("custom_repos", {})
    repos[repo] = family
    save_config(cfg)


# ---------------------------------------------------------------- detection helpers
def family_from_class(class_name: str) -> str | None:
    c = class_name or ""
    if "Flux" in c:
        return "flux"
    if "XL" in c:
        return "sdxl"
    if c in ("StableDiffusionPipeline", "LatentConsistencyModelPipeline", "StableDiffusionImg2ImgPipeline"):
        return "sd15"
    return None


def family_of_safetensors(path: Path) -> str | None:
    """safetensors header padh ke pata karo ki SD1.5 hai ya SDXL (poora file load kiye bina)."""
    try:
        with open(path, "rb") as f:
            n = struct.unpack("<Q", f.read(8))[0]
            if n > 100_000_000:
                return None
            keys = json.loads(f.read(n)).keys()
    except Exception:
        return None
    ks = " ".join(list(keys)[:5000])
    if "double_blocks." in ks or "single_transformer_blocks" in ks:
        return "flux"
    if "conditioner.embedders.1" in ks or "add_embedding" in ks:
        return "sdxl"
    if "model.diffusion_model" in ks or "cond_stage_model" in ks:
        return "sd15"
    return None


def hf_cached(repo: str) -> bool:
    """Kya ye HF repo already download ho chuka hai?"""
    from huggingface_hub import try_to_load_from_cache
    try:
        r = try_to_load_from_cache(repo, "model_index.json")
        return isinstance(r, str)
    except Exception:
        return False


def hf_file_cached(repo: str, filename: str) -> bool:
    from huggingface_hub import try_to_load_from_cache
    try:
        return isinstance(try_to_load_from_cache(repo, filename), str)
    except Exception:
        return False


def turbo_lora_available(family: str) -> bool:
    repo = LCM_LORA.get(family)
    return bool(repo) and hf_file_cached(repo, "pytorch_lora_weights.safetensors")


def single_file_supported(family: str) -> bool:
    repo = CONFIG_REPOS.get(family)
    return bool(repo) and hf_cached(repo)


# ---------------------------------------------------------------- list all usable models
def available_models() -> list[ModelInfo]:
    out: list[ModelInfo] = []
    for m in CATALOG:
        if hf_cached(m.source):
            out.append(m)
    for repo, fam in load_config().get("custom_repos", {}).items():
        if hf_cached(repo) and repo not in {m.source for m in out}:
            out.append(ModelInfo("hf:" + repo, f"{repo} (custom)", fam, repo, steps=25,
                                 cfg=7.0 if fam != "flux" else 0.0))
    for d in sorted(paths.DIFFUSERS.iterdir()):
        idx = d / "model_index.json"
        if d.is_dir() and idx.exists():
            try:
                fam = family_from_class(json.loads(idx.read_text()).get("_class_name", ""))
            except Exception:
                fam = None
            if fam:
                out.append(ModelInfo("dir:" + d.name, f"📁 {d.name}", fam, str(d), kind="folder",
                                     steps=4 if fam == "flux" else 25,
                                     cfg=0.0 if fam == "flux" else 7.0))
    for f in sorted(paths.CHECKPOINTS.iterdir()):
        if f.suffix.lower() in (".safetensors", ".ckpt"):
            fam = family_of_safetensors(f) if f.suffix.lower() == ".safetensors" else "sd15"
            if fam in ("sd15", "sdxl"):
                out.append(ModelInfo("file:" + f.name, f"📄 {f.stem} ({'SDXL' if fam == 'sdxl' else 'SD1.5'})",
                                     fam, str(f), kind="single", steps=25, cfg=7.0))
    return out


def list_loras() -> list[str]:
    return sorted(f.name for f in paths.LORA.iterdir() if f.suffix.lower() == ".safetensors")
