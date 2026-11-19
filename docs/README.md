# 📱 GK Image Studio — phone / browser version

Offline AI image generator that runs **inside Chrome** on your phone or PC, on the device's own GPU
(WebGPU). No server, no API key, no per-image cost. Prompts and images never leave the device.

- **Model:** SD-Turbo (512px, 1–4 steps), ~2.5 GB, downloaded once from Hugging Face and stored in the browser.
- **Works offline** after the first visit + model download.

## Requirements
- Google Chrome (latest) on Android or a PC.
- A GPU with **WebGPU 16-bit float (`shader-f16`)** support. Check on https://webgpureport.org —
  `shader-f16` must be listed under *features*. The app also checks this itself and tells you.
- ~3 GB free storage, 8 GB+ RAM recommended.

## Hosting (GitHub Pages, free)
1. On GitHub: **Settings → Pages → Build and deployment → Source: Deploy from a branch**.
2. Pick the branch that has this `docs/` folder and the folder **/docs**, then **Save**.
3. After a minute the app is at `https://<your-username>.github.io/<repo>/`.

## First use on the phone
1. Open the link in Chrome (on WiFi).
2. Tap **⬇ Download model (2.5 GB)**. If it stops, tap again — it resumes.
3. Chrome menu (⋮) → **Add to Home screen** / **Install app**.
4. From now on open it from the home-screen icon, even with internet off.

## Files
| Path | What it is |
|---|---|
| `index.html` | The whole UI (English) |
| `js/sd.js` | Model download/cache + SD-Turbo pipeline (text encoder → UNet → VAE) |
| `js/tokenizer.js` | CLIP tokenizer (pure JS, verified against Hugging Face's) |
| `js/app.js`, `js/styles.js`, `js/db.js` | UI logic, style presets, image history (IndexedDB) |
| `sw.js`, `manifest.webmanifest`, `icons/` | Offline support + installable app |
| `vendor/ort/` | ONNX Runtime Web (MIT), bundled for offline use |
| `model/tokenizer/` | Tokenizer vocabulary |

The SD-Turbo model is under Stability AI's non-commercial research license — personal use only.
