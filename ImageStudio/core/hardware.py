"""Computer ka hardware pehchaano: NVIDIA GPU / Apple Silicon / sirf CPU."""
import os
import platform

import torch


class Hardware:
    def __init__(self) -> None:
        self.device = "cpu"
        self.name = platform.processor() or platform.machine() or "CPU"
        self.vram_gb = 0.0
        self.bf16 = False
        if torch.cuda.is_available():
            self.device = "cuda"
            props = torch.cuda.get_device_properties(0)
            self.name = props.name
            self.vram_gb = props.total_memory / 1024**3
            self.bf16 = torch.cuda.is_bf16_supported()
        elif getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
            self.device = "mps"
            self.name = "Apple Silicon (Metal)"
        else:
            torch.set_num_threads(max(1, os.cpu_count() or 1))
        self.ram_gb = _ram_gb()

    def dtype(self, family: str) -> torch.dtype:
        if self.device == "cpu":
            return torch.float32
        if family == "flux":
            return torch.bfloat16 if (self.device == "mps" or self.bf16) else torch.float16
        return torch.float16

    @property
    def label(self) -> str:
        if self.device == "cuda":
            return f"🟢 NVIDIA GPU: {self.name} ({self.vram_gb:.0f} GB VRAM)"
        if self.device == "mps":
            return f"🟢 {self.name} · RAM {self.ram_gb:.0f} GB"
        return (f"🟡 CPU mode ({self.ram_gb:.0f} GB RAM) — GPU nahi mila. "
                "⚡ Turbo mode ON rakhein aur chhota/fast model use karein.")


def _ram_gb() -> float:
    try:
        if hasattr(os, "sysconf") and "SC_PHYS_PAGES" in os.sysconf_names:
            return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 1024**3
        import ctypes  # Windows

        class MEMSTAT(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                        ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                        ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                        ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                        ("sullAvailExtendedVirtual", ctypes.c_ulonglong)]
        st = MEMSTAT()
        st.dwLength = ctypes.sizeof(MEMSTAT)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(st))
        return st.ullTotalPhys / 1024**3
    except Exception:
        return 0.0


HW = Hardware()
