"""All folders in one place. Everything lives inside the app folder (portable)."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"
HF_HOME = MODELS / "hf"               # Hugging Face cache (downloaded models)
CHECKPOINTS = MODELS / "checkpoints"  # put .safetensors checkpoints (e.g. from Civitai) here
LORA = MODELS / "lora"                # put LoRA files here
DIFFUSERS = MODELS / "diffusers"      # put diffusers-format model folders here
REMBG = MODELS / "rembg"              # background remover model
OUTPUTS = ROOT / "outputs"
CONFIG = ROOT / "config.json"

for _p in (HF_HOME, CHECKPOINTS, LORA, DIFFUSERS, REMBG, OUTPUTS):
    _p.mkdir(parents=True, exist_ok=True)


def setup_env(offline: bool = True) -> None:
    """Set environment variables. Call BEFORE importing huggingface / torch."""
    os.environ["HF_HOME"] = str(HF_HOME)
    os.environ["U2NET_HOME"] = str(REMBG)
    os.environ["GRADIO_ANALYTICS_ENABLED"] = "False"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["DO_NOT_TRACK"] = "1"
    os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
    flag = "1" if offline else "0"
    os.environ["HF_HUB_OFFLINE"] = flag
    os.environ["TRANSFORMERS_OFFLINE"] = flag
