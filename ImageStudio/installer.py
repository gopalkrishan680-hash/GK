"""Installer: venv ke andar chalta hai (install.bat / install.sh ise chalate hain).

1. Aapke GPU ke hisaab se PyTorch install
2. Baaki libraries install
3. Models download (menu)
4. Windows par Desktop shortcut
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
        raise SystemExit(f"\n❌ Command fail hua (code {r}). Internet check karke install dobara chalaein.")
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
    print("\n========== 1/3  PyTorch install (AI engine) ==========")
    system = platform.system()
    gpu = nvidia_gpu() if system != "Darwin" else None
    if gpu:
        name, vram, cap = gpu
        print(f"🟢 NVIDIA GPU mila: {name} ({vram:.0f} GB, compute {cap})")
        # Naye GPU (RTX 20/30/40/50) -> naya CUDA; purane GPU (GTX 10xx etc.) -> cu126/cu118
        indexes = ["cu128", "cu130", "cu126"] if cap >= 7.5 else ["cu126", "cu118"]
        for idx in indexes:
            print(f"\n⬇️  PyTorch ({idx}) download ho raha hai (~2.5 GB, thoda time lagega)...")
            if pip("torch", "torchvision", "--index-url", TORCH_URL + idx, check=False) == 0 and torch_ok(True):
                print("✅ PyTorch GPU ke saath ready")
                return
        print("⚠️  GPU wala PyTorch nahi chala. CPU version install kar rahe hain (slow hoga).")
    elif system == "Darwin":
        print("🍎 Mac mila — Apple Silicon (M1/M2/M3/M4) par Metal GPU use hoga.")
        pip("torch", "torchvision")
        return
    else:
        print("🟡 NVIDIA GPU nahi mila — CPU mode. (Turbo mode se image phir bhi ban jayegi.)")
    if system == "Linux":
        if pip("torch", "torchvision", "--index-url", TORCH_URL + "cpu", check=False) == 0:
            return
    pip("torch", "torchvision")


def install_requirements() -> None:
    print("\n========== 2/3  Baaki libraries install ==========")
    pip("-r", str(ROOT / "requirements.txt"))
    print("✅ Libraries ready")


def download_models() -> None:
    print("\n========== 3/3  AI Models download ==========")
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
        print("🖥️  Desktop par 'GK Image Studio' shortcut ban gaya.")


def main() -> None:
    print(f"Python {platform.python_version()} · {platform.system()} {platform.machine()}")
    if sys.version_info < (3, 10):
        raise SystemExit("❌ Python 3.10 ya naya chahiye (3.12 best).")
    pip("pip", "setuptools", "wheel", check=False)
    if "--skip-torch" not in sys.argv:
        install_torch()
    install_requirements()
    if "--skip-models" not in sys.argv:
        download_models()
    desktop_shortcut()
    print("\n🎉🎉 Installation complete! 🎉🎉")
    print("App chalane ke liye: Windows -> run.bat (ya Desktop shortcut) | Mac/Linux -> ./run.sh")
    print("Ab internet ki zaroorat nahi hai.\n")


if __name__ == "__main__":
    main()
