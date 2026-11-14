"""Installer: runs inside the venv (started by install.bat / install.sh).

1. Install PyTorch matching your GPU
2. Install the other libraries
3. Download models (menu)
4. Desktop shortcut on Windows
"""
from __future__ import annotations

import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PY = sys.executable
TORCH_URL = "https://download.pytorch.org/whl/"


def sh(args: list[str], check: bool = True) -> int:
    print("   $", " ".join(args))
    r = subprocess.call(args)
    if check and r != 0:
        raise SystemExit(f"\n❌ Command failed (code {r}). Check your internet and run the installer again.")
    return r


def pip(*args: str, check: bool = True) -> int:
    return sh([PY, "-m", "pip", "install", "--upgrade", *args], check=check)


def nvidia_gpu() -> tuple[str, float, float] | None:
    exe = shutil.which("nvidia-smi")
    if not exe:
        return None
    try:
        out = subprocess.check_output([exe, "--query-gpu=name,memory.total,compute_cap",
                                       "--format=csv,noheader,nounits"], text=True, timeout=20)
        name, mem, cap = [x.strip() for x in out.strip().splitlines()[0].split(",")]
        return name, float(mem) / 1024, float(cap)
    except Exception:
        try:
            out = subprocess.check_output([exe, "--query-gpu=name,memory.total",
                                           "--format=csv,noheader,nounits"], text=True, timeout=20)
            name, mem = [x.strip() for x in out.strip().splitlines()[0].split(",")]
            return name, float(mem) / 1024, 7.5
        except Exception:
            return None


def torch_ok(want_cuda: bool) -> bool:
    code = "import torch,sys; sys.exit(0 if (torch.cuda.is_available() or not %s) else 1)" % want_cuda
    return subprocess.call([PY, "-c", code]) == 0


def install_torch() -> None:
    print("\n========== 1/3  Installing PyTorch (AI engine) ==========")
    system = platform.system()
    gpu = nvidia_gpu() if system != "Darwin" else None
    if gpu:
        name, vram, cap = gpu
        print(f"🟢 NVIDIA GPU found: {name} ({vram:.0f} GB, compute {cap})")
        # Newer GPUs (RTX 20/30/40/50) -> newer CUDA; older GPUs (GTX 10xx etc.) -> cu126/cu118
        indexes = ["cu128", "cu130", "cu126"] if cap >= 7.5 else ["cu126", "cu118"]
        for idx in indexes:
            print(f"\n⬇️  PyTorch ({idx}) downloading (~2.5 GB, this takes a while)...")
            if pip("torch", "torchvision", "--index-url", TORCH_URL + idx, check=False) == 0 and torch_ok(True):
                print("✅ PyTorch ready with GPU support")
                return
        print("⚠️  GPU PyTorch did not work. Installing the CPU version (slower).")
    elif system == "Darwin":
        print("🍎 Mac detected — the Metal GPU will be used on Apple Silicon (M1/M2/M3/M4).")
        pip("torch", "torchvision")
        return
    else:
        print("🟡 No NVIDIA GPU found — CPU mode. (Turbo mode still makes images.)")
    if system == "Linux":
        if pip("torch", "torchvision", "--index-url", TORCH_URL + "cpu", check=False) == 0:
            return
    pip("torch", "torchvision")


def install_requirements() -> None:
    print("\n========== 2/3  Installing libraries ==========")
    pip("-r", str(ROOT / "requirements.txt"))
    print("✅ Libraries ready")


def download_models() -> None:
    print("\n========== 3/3  Downloading AI models ==========")
    auto = "--recommended" if os.environ.get("GK_AUTO") == "1" else None
    sh([PY, str(ROOT / "download_models.py")] + ([auto] if auto else []), check=False)


def desktop_shortcut() -> None:
    if platform.system() != "Windows":
        return
    target = ROOT / "run.bat"
    ps = ("$d=[Environment]::GetFolderPath('Desktop');"
          "$s=(New-Object -ComObject WScript.Shell).CreateShortcut(\"$d\\GK Image Studio.lnk\");"
          f"$s.TargetPath='{target}';$s.WorkingDirectory='{ROOT}';"
          "$s.IconLocation='imageres.dll,67';$s.Save()")
    if subprocess.call(["powershell", "-NoProfile", "-Command", ps]) == 0:
        print("🖥️  'GK Image Studio' shortcut created on the Desktop.")


def main() -> None:
    print(f"Python {platform.python_version()} · {platform.system()} {platform.machine()}")
    if sys.version_info < (3, 10):
        raise SystemExit("❌ Python 3.10 or newer is required (3.12 recommended).")
    pip("pip", "setuptools", "wheel", check=False)
    if "--skip-torch" not in sys.argv:
        install_torch()
    install_requirements()
    if "--skip-models" not in sys.argv:
        download_models()
    desktop_shortcut()
    print("\n🎉🎉 Installation complete! 🎉🎉")
    print("To start the app: Windows -> run.bat (or the Desktop shortcut) | Mac/Linux -> ./run.sh")
    print("No internet needed from now on.\n")


if __name__ == "__main__":
    main()
