# 🎨 GK AI Image Studio — bina internet ke AI image generator

Prompt likho, image banao. **Sab kuch aapke computer par chalta hai**: na internet, na login, na
API key, na har image ka paisa, na koi limit. Internet sirf **ek baar** chahiye, install aur
model download ke time.

## ✨ Features

| Feature | Kya karta hai |
|---|---|
| 🎨 **Text → Image** | Prompt se kisi bhi type ki image: photo, anime, 3D cartoon, painting, logo, thumbnail… |
| 🎭 **22 Style presets** | Real Photo, Cinematic, Anime, Pixar-3D, Madhubani/Indian Art, Silhouette, YouTube Thumbnail, Logo, Pixel Art, Sticker, Coloring book… |
| 📐 **Size presets** | 1:1 Insta, 9:16 Reels/Shorts, 16:9 YouTube, Poster, ya custom size |
| ⚡ **Turbo mode** | LCM-LoRA se 4-8 steps mein image, **bina GPU ke CPU par bhi** |
| 🧠 **8 models + apne models** | DreamShaper, Realistic Vision, Anime, SDXL, SDXL Turbo, FLUX.1 Schnell; Civitai ke `.safetensors` bhi chalte hain |
| 🔁 **Image → Image** | Apni photo ko naye style mein badlo |
| 🖌️ **Inpaint** | Photo ke kisi hisse par brush chalao, wahan nayi cheez banao |
| 🔍 **Upscale** | 2x fast upscale + AI upscale (naya detail add karta hai) |
| ✂️ **Background hatao** | Ek click mein transparent PNG |
| 🎭 **LoRA** | Apne character/style LoRA lagao (strength control ke saath) |
| ✨ **Prompt sudharo / 🎲 Surprise me** | Chhote prompt ko detailed banao, ya random idea lo |
| 🖼️ **History** | Har image settings ke saath save hoti hai; ek click mein wahi settings wapas |
| 🔎 **PNG Info** | Kisi bhi bani image ka prompt/seed dekho |
| 🎲 **Seed control + batch** | Ek saath 8 tak images; same seed = same image |
| ⏹️ **Stop button** | Beech mein rokna |
| 📱 **Phone se use** | `run.bat --listen` → same WiFi par phone ke browser se |
| 🖥️ **Auto hardware** | NVIDIA GPU / Apple M-chip / CPU apne aap pehchanta hai, kam VRAM par memory-saver mode |

## 💻 Kitna powerful computer chahiye?

| Aapka computer | Kya chalega | Ek image ka time (lagbhag) |
|---|---|---|
| Sirf CPU, 8 GB+ RAM | DreamShaper 8 / Realistic Vision + ⚡ Turbo | 15-60 sec (512px) |
| NVIDIA 4-6 GB (GTX 1650, RTX 3050) | SD1.5 models, SDXL Turbo | 2-10 sec |
| NVIDIA 8-12 GB (RTX 3060/4060) | Sab SDXL models (HD 1024px) | 3-15 sec |
| NVIDIA 16 GB+ / 32 GB RAM | FLUX.1 Schnell bhi (best quality + text) | 10-30 sec |
| Mac M1/M2/M3/M4 (16 GB+) | SD1.5 + SDXL | 5-30 sec |

Disk: kam se kam **10 GB khaali** (har SD1.5 model ~2 GB, SDXL ~7 GB, FLUX ~33 GB).

## 🚀 Install (ek baar, internet ke saath)

### Windows
1. Is `ImageStudio` folder ko apne PC par rakhein (jaise `D:\ImageStudio`). Path mein Hindi/special
   characters na hon to behtar hai.
2. **`install.bat`** par double-click karein.
   - Python nahi hai to apne aap install ho jayega.
   - NVIDIA GPU hai to GPU wala PyTorch aayega, warna CPU wala.
   - End mein models ka menu aayega: **ENTER** dabaiye (aapke PC ke hisaab se recommended models).
3. Ho gaya! Desktop par **GK Image Studio** shortcut ban jayega.

### Mac / Linux
```bash
cd ImageStudio
./install.sh
```

## ▶️ Chalana (offline)

- **Windows:** Desktop shortcut ya **`run.bat`**
- **Mac/Linux:** `./run.sh`

Browser apne aap khulega: http://127.0.0.1:7860 . Ye page aapke hi computer se aa raha hai, internet se nahi.
Band karne ke liye kaali (terminal) window band kar dein.

## 📦 Aur models chahiye?

- App ke **📦 Models** tab se (internet ke saath), ya `download_models.bat` / `./download_models.sh`
- **Civitai** jaisi sites se SD1.5/SDXL `.safetensors` file → `models/checkpoints/` mein daalo
- **LoRA** `.safetensors` → `models/lora/`
- Phir app mein **🔄** dabao.

Saare models `models/` folder ke andar hi rehte hain. Poora `ImageStudio` folder (venv + models)
pen drive se doosre PC (same OS) par copy karke bhi chala sakte hain.

## 📝 Achhe prompt ke tips

- Prompt **English** mein likhein (models English samajhte hain).
- Formula: `[kya] + [kahan] + [mood / light] + [style]`
  - `a little girl flying a kite on a rooftop, sunset, warm golden light, makar sankranti festival`
- **Negative prompt** mein jo nahi chahiye: `text, watermark, extra fingers, blurry`
- Pasand aayi image ka **seed** note karein; same seed + thoda badla prompt = milti-julti image.
- Text likhi image (poster, logo mein naam) chahiye to **FLUX.1 Schnell** use karein.

## 🛠️ Problem aaye to

| Problem | Hal |
|---|---|
| `GPU memory kam pad gayi` | Chhota size, 1 image, ya SD1.5 model. |
| Bahut slow (CPU) | ⚡ Turbo ON, 1:1 size, DreamShaper 8. |
| Model list khaali | 📦 Models tab se download karein (internet ke saath), phir 🔄. |
| Black / kharab image | Steps 20-30, CFG 5-8 rakhein; Turbo mode mein CFG 1-2. |
| Install fail | Internet check karein, `install.bat` dobara chalaein (jo ho chuka wo skip hoga). |

## 📜 Licenses

App ka code free hai. Har model ka apna license hai (Models tab mein likha hai). *SDXL Turbo*
sirf personal / non-commercial use ke liye hai. Baaki zyadatar models (OpenRAIL, Apache-2.0)
commercial use bhi allow karte hain. Bechne se pehle model ka license padh lein.
