import subprocess, sys, numpy as np
path = sys.argv[1]; fps = 4; w, h = 160, 90
raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-vf", f"fps={fps},scale={w}:{h},format=gray", "-f", "rawvideo", "-"],
                     capture_output=True).stdout
fr = np.frombuffer(raw, np.uint8).reshape(-1, h, w).astype(np.float32)
diff = np.abs(np.diff(fr, axis=0)).mean(axis=(1, 2))
still = diff < 0.6
runs, start = [], None
for i, s in enumerate(still):
    if s and start is None: start = i
    if (not s or i == len(still) - 1) and start is not None:
        end = i if not s else i + 1
        if (end - start) / fps >= 3.0: runs.append((start / fps, end / fps))
        start = None
for a, b in runs: print(f"STILL {int(a//60)}:{a%60:05.2f} - {int(b//60)}:{b%60:05.2f}  ({b-a:.1f}s)")
