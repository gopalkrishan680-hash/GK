"""Model catalog + local model discovery.

Three kinds of models are supported:
1. Catalog models (downloaded once from Hugging Face, then offline)
2. .safetensors files placed in models/checkpoints/ (from Civitai etc.)
3. diffusers-format folders placed in models/diffusers/
"""
from __future__ import annotations

import json
import struct
from dataclasses import dataclass, field
from pathlib import Path

from . import paths

# Base family -> default resolution
BASE_RES = {"sd15": 512, "sdxl": 1024, "flux": 1024}
# Approx. VRAM (GB) needed to run comfortably without offloading
NEED_VRAM = {"sd15": 4, "sdxl": 9, "flux": 24}

# Turbo (LCM-LoRA): runs any SD1.5 / SDXL model in 4-8 steps
LCM_LORA = {
    "sd15": "latent-consistency/lcm-lora-sdv1-5",
    "sdxl": "latent-consistency/lcm-lora-sdxl",
}
# Config files needed to load single-file .safetensors models offline
CONFIG_REPOS = {
    "sd15": "stable-diffusion-v1-5/stable-diffusion-v1-5",
    "sdxl": "stabilityai/stable-diffusion-xl-base-1.0",
}


@dataclass
class ModelInfo:
    key: str
    name: str
    family: str                 # sd15 | sdxl | flux
    source: str                 # HF repo id or local path
    kind: str = "hf"            # hf | single | folder
    steps: int = 25
    cfg: float = 7.0
    turbo_native: bool = False  # model is already fast (no LCM-LoRA needed)
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
              desc="Any kind of image: photo, art, fantasy, anime. Works on CPU with Turbo mode.",
              license="CreativeML OpenRAIL-M", tags=["recommended", "cpu"]),
    ModelInfo("realvision51", "Realistic Vision 5.1 (Real photo, SD1.5)", "sd15",
              "SG161222/Realistic_Vision_V5.1_noVAE", steps=25, cfg=6.0, size_gb=2.1,
              desc="Lifelike photos of people and products. Works on CPU with Turbo.",
              license="CreativeML OpenRAIL-M", tags=["cpu"]),
    ModelInfo("anything5", "Anything V5 (Anime, SD1.5)", "sd15", "stablediffusionapi/anything-v5",
              steps=25, cfg=7.0, size_gb=2.1,
              desc="Anime / manga / cartoon style.", license="CreativeML OpenRAIL-M", tags=["cpu"]),
    ModelInfo("sdxlturbo", "SDXL Turbo (Super fast, 512px)", "sdxl", "stabilityai/sdxl-turbo",
              steps=2, cfg=0.0, turbo_native=True, size_gb=6.9,
              desc="Images in 1-4 steps, about a second on a 6GB+ GPU. Personal / non-commercial use only.",
              license="Stability AI Non-Commercial", tags=["gpu"]),
    ModelInfo("dreamshaperxl", "DreamShaper XL Turbo (HD 1024px)", "sdxl", "Lykon/dreamshaper-xl-v2-turbo",
              steps=6, cfg=2.0, turbo_native=True, size_gb=6.9,
              desc="Large HD 1024px images, fast. Best on an 8GB+ GPU.", license="OpenRAIL++",
              tags=["recommended-gpu", "gpu"]),
    ModelInfo("sdxlbase", "Stable Diffusion XL 1.0 (HD, original)", "sdxl",
              "stabilityai/stable-diffusion-xl-base-1.0", steps=30, cfg=6.5, size_gb=6.9,
              desc="Official SDXL. Fast with Turbo mode.", license="OpenRAIL++", tags=["gpu"]),
    ModelInfo("animaginexl", "Animagine XL 3.1 (HD Anime)", "sdxl", "cagliostrolab/animagine-xl-3.1",
              steps=28, cfg=7.0, size_gb=6.9,
              desc="Best quality anime. Write tags in the prompt: '1girl, smile, ...'",
              license="Fair AI Public License", tags=["gpu"]),
    ModelInfo("fluxschnell", "FLUX.1 Schnell (Top quality, renders text)", "flux",
              "black-forest-labs/FLUX.1-schnell", steps=4, cfg=0.0, turbo_native=True, size_gb=33.0,
              desc="Best quality + accurate text in images. Needs a 12GB+ GPU and 32GB RAM. ~33GB download.",
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
    """Read the safetensors header to tell SD1.5 from SDXL (without loading the whole file)."""
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
    """Is this HF repo already downloaded?"""
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
