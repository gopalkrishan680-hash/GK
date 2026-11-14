"""Models download karo (sirf YAHI step internet maangta hai, ek baar).

Use:
  python download_models.py                  -> menu se chuno
  python download_models.py --recommended    -> aapke PC ke hisaab se best set
  python download_models.py dreamshaper8 turbo configs rembg
  python download_models.py --repo username/model-name   -> koi bhi HF diffusers model
"""
from __future__ import annotations

import argparse
import sys

from core import paths

paths.setup_env(offline=False)
import os  # noqa: E402

os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"

from core.models import CATALOG, CATALOG_BY_KEY, CONFIG_REPOS, LCM_LORA, add_custom_repo, family_from_class, hf_cached  # noqa: E402

FAILED: list[str] = []


def _fail(name: str, e: Exception) -> None:
    FAILED.append(name)
    msg = str(e)
    print(f"❌ {name}: {msg[:300]}")
    if "401" in msg or "403" in msg or "gated" in msg.lower():
        print("   ↳ Ye model login/permission maang raha hai, ya network block hai. Doosra model try karein.")
    elif "connect" in msg.lower() or "resolve" in msg.lower() or "timed out" in msg.lower():
        print("   ↳ Internet connection check karein, phir dobara chalaein (download wahin se resume hoga).")


EXTRAS = {
    "turbo": "⚡ Turbo LoRA (LCM) — SD1.5/SDXL models ko 4-8 steps mein chalata hai (~0.6 GB)  [zaroori]",
    "configs": "🧩 Config files — .safetensors (Civitai) models offline chalane ke liye (~5 MB)  [zaroori]",
    "rembg": "✂️ Background remover model (~170 MB)",
}


def download_pipeline(repo: str) -> bool:
    from diffusers import DiffusionPipeline
    print(f"\n⬇️  Download: {repo}")
    for variant in ("fp16", None):
        try:
            DiffusionPipeline.download(repo, variant=variant)
            print(f"✅ {repo} ready" + (" (fp16)" if variant else ""))
            return True
        except Exception as e:
            err = e
            if variant:
                print("   (fp16 version nahi mila, normal version try kar rahe hain...)")
    _fail(repo, err)
    return False


def download_turbo() -> None:
    from huggingface_hub import hf_hub_download
    for fam, repo in LCM_LORA.items():
        print(f"\n⬇️  Turbo LoRA ({fam}): {repo}")
        try:
            hf_hub_download(repo, "pytorch_lora_weights.safetensors")
            print("✅ ready")
        except Exception as e:
            _fail(repo, e)


def download_configs() -> None:
    from huggingface_hub import snapshot_download
    for fam, repo in CONFIG_REPOS.items():
        print(f"\n⬇️  Config files ({fam}): {repo}")
        try:
            snapshot_download(repo, allow_patterns=["*.json", "*.txt", "tokenizer*/*"],
                              ignore_patterns=["*.safetensors", "*.bin", "*.ckpt", "*.onnx", "*.msgpack"])
            print("✅ ready")
        except Exception as e:
            _fail(repo, e)


def download_rembg() -> None:
    print("\n⬇️  Background remover (u2net)")
    try:
        from rembg import new_session
        new_session("u2net")
        print("✅ ready")
    except Exception as e:
        _fail("rembg", e)


def recommended() -> list[str]:
    try:
        import torch
        if torch.cuda.is_available():
            vram = torch.cuda.get_device_properties(0).total_memory / 1024**3
            if vram >= 7.5:
                return ["dreamshaper8", "dreamshaperxl", "turbo", "configs", "rembg"]
            return ["dreamshaper8", "turbo", "configs", "rembg"]
    except Exception:
        pass
    return ["dreamshaper8", "turbo", "configs", "rembg"]


def run(keys: list[str]) -> None:
    for k in keys:
        if k == "turbo":
            download_turbo()
        elif k == "configs":
            download_configs()
        elif k == "rembg":
            download_rembg()
        elif k in CATALOG_BY_KEY:
            download_pipeline(CATALOG_BY_KEY[k].source)
        else:
            print(f"⚠️  '{k}' naam ka model nahi mila")


def add_repo(repo: str) -> None:
    if download_pipeline(repo):
        import json
        from huggingface_hub import hf_hub_download
        cls = json.load(open(hf_hub_download(repo, "model_index.json"))).get("_class_name", "")
        fam = family_from_class(cls)
        if fam:
            add_custom_repo(repo, fam)
            print(f"✅ '{repo}' app mein add ho gaya ({fam})")
        else:
            print(f"⚠️  '{cls}' type ka model abhi supported nahi hai (sirf SD1.5 / SDXL / FLUX).")


def menu() -> list[str]:
    rec = recommended()
    items = [(m.key, f"{m.name} — ~{m.size_gb:.1f} GB\n        {m.desc}") for m in CATALOG]
    items += list(EXTRAS.items())
    print("\n================  MODELS DOWNLOAD  ================")
    print("Sirf yahi step internet use karta hai. Iske baad app poori tarah OFFLINE chalegi.\n")
    for i, (k, d) in enumerate(items, 1):
        done = " ✅(already hai)" if (k in CATALOG_BY_KEY and hf_cached(CATALOG_BY_KEY[k].source)) else ""
        star = " ⭐" if k in rec else ""
        print(f" {i:2d}. {d}{star}{done}")
    print(f"\n⭐ = aapke computer ke liye recommended: {', '.join(rec)}")
    ans = input("\nNumbers likhein (jaise: 1 9 10) ya ENTER dabaein recommended ke liye, 0 = skip: ").strip()
    if ans == "0":
        return []
    if not ans:
        return rec
    keys = []
    for tok in ans.replace(",", " ").split():
        if tok.isdigit() and 1 <= int(tok) <= len(items):
            keys.append(items[int(tok) - 1][0])
    return keys


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("keys", nargs="*")
    ap.add_argument("--recommended", action="store_true")
    ap.add_argument("--repo")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    if a.list:
        for m in CATALOG:
            print(f"{m.key:15s} {m.name}  {'✅' if hf_cached(m.source) else ''}")
        for k, d in EXTRAS.items():
            print(f"{k:15s} {d}")
        return
    if a.repo:
        add_repo(a.repo)
        return
    keys = a.keys or (recommended() if a.recommended else menu())
    run(keys)
    if FAILED:
        print(f"\n⚠️  Ye download nahi hue: {', '.join(FAILED)}")
        print("   Baaki sab ready hai. Baad mein download_models dobara chala sakte hain.")
        sys.exit(1)
    print("\n🎉 Download complete. Ab app offline chala sakte hain.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(1)
