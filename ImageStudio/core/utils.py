"""Image saving (with settings), history, PNG info, upscaling, background removal."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageFilter
from PIL.PngImagePlugin import PngInfo

from . import paths


def save_image(img: Image.Image, meta: dict) -> str:
    day = paths.OUTPUTS / time.strftime("%Y-%m-%d")
    day.mkdir(parents=True, exist_ok=True)
    n = len(list(day.glob("*.png"))) + 1
    f = day / f"{n:04d}_{meta.get('seed', 0)}.png"
    while f.exists():
        n += 1
        f = day / f"{n:04d}_{meta.get('seed', 0)}.png"
    info = PngInfo()
    info.add_text("parameters", a1111_text(meta))
    info.add_text("gk_studio", json.dumps(meta, ensure_ascii=False))
    img.save(f, pnginfo=info)
    return str(f)


def a1111_text(m: dict) -> str:
    return (f"{m.get('prompt', '')}\nNegative prompt: {m.get('negative', '')}\n"
            f"Steps: {m.get('steps')}, Sampler: {m.get('sampler')}, CFG scale: {m.get('cfg')}, "
            f"Seed: {m.get('seed')}, Size: {m.get('width')}x{m.get('height')}, Model: {m.get('model')}"
            + (", Turbo: on" if m.get("turbo") else ""))


def read_meta(path_or_img) -> dict:
    try:
        img = path_or_img if isinstance(path_or_img, Image.Image) else Image.open(path_or_img)
        info = getattr(img, "text", None) or img.info
        if "gk_studio" in info:
            return json.loads(info["gk_studio"])
        if "parameters" in info:
            return {"parameters": info["parameters"]}
    except Exception:
        pass
    return {}


def history(limit: int = 200) -> list[str]:
    files = sorted(paths.OUTPUTS.rglob("*.png"), key=lambda p: p.stat().st_mtime, reverse=True)
    return [str(f) for f in files[:limit]]


def open_folder(path: Path) -> None:
    try:
        if sys.platform.startswith("win"):
            os.startfile(str(path))  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            subprocess.Popen(["open", str(path)])
        else:
            subprocess.Popen(["xdg-open", str(path)])
    except Exception:
        pass


def simple_upscale(img: Image.Image, scale: float = 2.0) -> Image.Image:
    big = img.convert("RGB").resize((int(img.width * scale), int(img.height * scale)), Image.LANCZOS)
    return big.filter(ImageFilter.UnsharpMask(radius=1.5, percent=60, threshold=2))


def remove_background(img: Image.Image) -> Image.Image:
    try:
        from rembg import new_session, remove
    except ImportError as e:
        raise RuntimeError("rembg is not installed. Please run the installer again.") from e
    have_model = any(paths.REMBG.rglob("u2net.onnx"))
    if not have_model and os.environ.get("HF_HUB_OFFLINE") == "1":
        raise RuntimeError("Background remover model is not downloaded. Connect to the internet "
                           "and run 'download_models' (option: rembg).")
    return remove(img, session=new_session("u2net"))


def mask_from_editor(value) -> tuple[Image.Image | None, Image.Image | None]:
    """Gradio ImageEditor value -> (original image, mask where white = repaint)."""
    if not value:
        return None, None
    bg = value.get("background")
    layers = value.get("layers") or []
    if bg is None:
        return None, None
    bg = bg if isinstance(bg, Image.Image) else Image.open(bg)
    mask = Image.new("L", bg.size, 0)
    for layer in layers:
        lay = layer if isinstance(layer, Image.Image) else Image.open(layer)
        lay = lay.convert("RGBA").resize(bg.size)
        alpha = lay.split()[-1].point(lambda v: 255 if v > 10 else 0)
        mask.paste(255, mask=alpha)
    if mask.getbbox() is None:
        return bg.convert("RGB"), None
    mask = mask.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.GaussianBlur(4))
    return bg.convert("RGB"), mask
