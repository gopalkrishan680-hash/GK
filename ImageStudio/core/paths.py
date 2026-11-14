"""Saare folders ek jagah. App folder ke andar hi sab kuch rehta hai (portable)."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"
HF_HOME = MODELS / "hf"               # Hugging Face cache (downloaded models)
CHECKPOINTS = MODELS / "checkpoints"  # Civitai jaise .safetensors files yahan daalo
LORA = MODELS / "lora"                # LoRA files yahan daalo
DIFFUSERS = MODELS / "diffusers"      # diffusers-format model folders yahan daalo
REMBG = MODELS / "rembg"              # background remover model
OUTPUTS = ROOT / "outputs"
CONFIG = ROOT / "config.json"

for _p in (HF_HOME, CHECKPOINTS, LORA, DIFFUSERS, REMBG, OUTPUTS):
    _p.mkdir(parents=True, exist_ok=True)


def setup_env(offline: bool = True) -> None:
    """Environment variables set karo. Kisi bhi hugging face / torch import se PEHLE call karo."""
    os.environ["HF_HOME"] = str(HF_HOME)
    os.environ["U2NET_HOME"] = str(REMBG)
    os.environ["GRADIO_ANALYTICS_ENABLED"] = "False"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["DO_NOT_TRACK"] = "1"
    os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
    flag = "1" if offline else "0"
    os.environ["HF_HUB_OFFLINE"] = flag
    os.environ["TRANSFORMERS_OFFLINE"] = flag
