# GK Camera

Moto Edge 60 Pro ke liye banayi gayi Android camera app (CameraX par). Android 10+ wale kisi bhi phone par chalegi.

## Install (sirf phone se)

1. Phone ke browser mein kholo: `https://github.com/gopalkrishan680-hash/GK/releases/tag/gk-camera-latest`
2. **GKCamera.apk** download karo aur open karo.
3. "Install unknown apps" ki permission maange to allow karo.
4. App kholo, Camera aur Microphone permission do.

Photos aur videos `DCIM/GKCamera` folder mein save hote hain.

## Features

**Dono cameras (rear + selfie)**
- Lens buttons shutter ke upar: 0.5x (ultrawide) / 1x / 2x / 3x (tele) — jo lens phone deta hai
- 10 filters: Vivid, Vivid Warm, Vivid Cool, Dramatic, Dramatic Warm, Dramatic Cool, Mono,
  Silvertone, Noir (FILTER button) — live preview, photo aur video dono mein
- Tap to focus, pinch zoom, EV slider, grid, timer (3s / 10s)

**PHOTO**
- Ultra HDR photos, max-quality capture; default ~12MP binned (kam roshni mein saaf), 50MP button alag
- Phone ki apni processing: HDR+, NIGHT, PORTRAIT, AI AUTO (jo bhi Motorola third-party apps ko deta hai)
- NIGHT hamesha milta hai: phone ka night mode na ho to app khud slow shutter / low-light boost lagati hai
- DETAIL+ (halki sharpening), selfie ke liye SCREEN FLASH aur MIRROR
- 4:3 / 16:9, flash

**VIDEO**
- 4K / 1080p / 720p, 30 ya 60 fps, high bitrate
- NIGHT (andhere mein zyada bright video), LIGHT (rear: torch, selfie: screen fill-light)
- HDR 10-bit (HLG) video, stabilisation (STEADY), pause/resume, mic on/off

**PRO**
- RAW (DNG) + JPEG
- Manual ISO, shutter speed, manual focus, white balance presets

Volume button se bhi photo / recording start hoti hai.

Jo feature phone ya lens support nahi karta uska button dim dikhta hai.

## Build

Har push par GitHub Actions (`.github/workflows/camera-app.yml`) APK banata hai aur
`gk-camera-latest` release par daal deta hai. Local build: `./gradlew assembleRelease`.
