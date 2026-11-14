# 🎨 GK AI Image Studio — offline AI image generator

Type a prompt, get an image. **Everything runs on your own computer**: no internet, no login, no API
key, no per-image cost, no limits. Internet is needed **only once**, to install the app and download models.

## ✨ Features

| Feature | What it does |
|---|---|
| 🎨 **Text → Image** | Any kind of image from a prompt: photo, anime, 3D cartoon, painting, logo, thumbnail… |
| 🎭 **22 style presets** | Real Photo, Cinematic, Anime, 3D Cartoon, Indian Art (Madhubani/Miniature), Glowing Silhouette, YouTube Thumbnail, Logo, Pixel Art, Sticker, Coloring Book… |
| 📐 **Size presets** | 1:1 Instagram, 9:16 Reels/Shorts, 16:9 YouTube, poster, or a custom size |
| ⚡ **Turbo mode** | LCM-LoRA makes images in 4-8 steps, **even on a CPU without a GPU** |
| 🧠 **8 models + your own** | DreamShaper, Realistic Vision, Anything (anime), SDXL, SDXL Turbo, DreamShaper XL, Animagine XL, FLUX.1 Schnell; Civitai `.safetensors` files work too |
| 🔁 **Image → Image** | Restyle your own photo |
| 🖌️ **Inpaint** | Paint over part of a photo and generate something new there |
| 🔍 **Upscale** | Fast 2x upscale + AI upscale (adds new detail) |
| ✂️ **Remove background** | Transparent PNG in one click |
| 🎭 **LoRA** | Apply your own character/style LoRAs with a strength control |
| ✨ **Enhance prompt / 🎲 Surprise me** | Expand a short prompt, or get a random idea |
| 🖼️ **History** | Every image is saved with its settings; restore them in one click |
| 🔎 **PNG Info** | See the prompt/seed of any image made with the app |
| 🎲 **Seed control + batches** | Up to 8 images at once; same seed = same image |
| ⏹️ **Stop button** | Cancel mid-generation |
| 📱 **Use from your phone** | `run.bat --listen` → open it in a phone browser on the same WiFi |
| 🖥️ **Auto hardware detection** | NVIDIA GPU / Apple M-series / CPU; memory-saver mode on low-VRAM GPUs |

## 💻 System requirements

| Your computer | What runs | Time per image (approx.) |
|---|---|---|
| CPU only, 8 GB+ RAM | DreamShaper 8 / Realistic Vision + ⚡ Turbo | 15-60 s (512px) |
| NVIDIA 4-6 GB (GTX 1650, RTX 3050) | SD1.5 models, SDXL Turbo | 2-10 s |
| NVIDIA 8-12 GB (RTX 3060/4060) | All SDXL models (HD 1024px) | 3-15 s |
| NVIDIA 16 GB+ with 32 GB RAM | FLUX.1 Schnell too (best quality + text) | 10-30 s |
| Mac M1/M2/M3/M4 (16 GB+) | SD1.5 + SDXL | 5-30 s |

Disk: at least **10 GB free** (SD1.5 models ~2 GB each, SDXL ~7 GB, FLUX ~33 GB).

## 🚀 Install (once, with internet)

### Windows
1. Put the `ImageStudio` folder somewhere on your PC (e.g. `D:\ImageStudio`). Avoid special
   characters in the path.
2. Double-click **`install.bat`**.
   - Installs Python automatically if it is missing.
   - Installs the GPU build of PyTorch if you have an NVIDIA GPU, otherwise the CPU build.
   - At the end a model menu appears: press **ENTER** for the models recommended for your PC.
3. Done! A **GK Image Studio** shortcut is created on your Desktop.

### Mac / Linux
```bash
cd ImageStudio
./install.sh
```

## ▶️ Run (offline)

- **Windows:** the Desktop shortcut or **`run.bat`**
- **Mac/Linux:** `./run.sh`

Your browser opens at http://127.0.0.1:7860 . The page is served by your own computer, not the internet.
To quit, close the black terminal window.

## 📦 More models

- From the app's **📦 Models** tab (with internet), or `download_models.bat` / `./download_models.sh`
- SD1.5/SDXL `.safetensors` files from sites like **Civitai** → `models/checkpoints/`
- **LoRA** `.safetensors` → `models/lora/`
- Then press **🔄** in the app.

All models live inside the `models/` folder. You can copy the whole `ImageStudio` folder (venv +
models) to another PC with the same OS and run it there.

## 📝 Prompt tips

- Formula: `[subject] + [where] + [mood / lighting] + [style]`
  - `a little girl flying a kite on a rooftop, sunset, warm golden light, makar sankranti festival`
- Put what you don't want in the **negative prompt**: `text, watermark, extra fingers, blurry`
- Note the **seed** of an image you like; same seed + a slightly changed prompt = a similar image.
- Need readable text in the image (posters, names in logos)? Use **FLUX.1 Schnell**.

## 🛠️ Troubleshooting

| Problem | Fix |
|---|---|
| `Out of GPU memory` | Smaller size, 1 image, or an SD1.5 model. |
| Very slow (CPU) | Turbo ON, 1:1 size, DreamShaper 8. |
| Model list is empty | Download from the 📦 Models tab (with internet), then press 🔄. |
| Black / broken images | Steps 20-30, CFG 5-8; in Turbo mode CFG 1-2. |
| Install failed | Check your internet and run `install.bat` again (finished steps are skipped). |

## 📜 Licenses

The app code is free. Each model has its own license (shown in the Models tab). *SDXL Turbo* is for
personal / non-commercial use only. Most other models (OpenRAIL, Apache-2.0) allow commercial use.
Read the model's license before selling images.
