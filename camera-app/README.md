# GK Camera

Moto Edge 60 Pro ke liye banayi gayi Android camera app (CameraX par). Android 10+ wale kisi bhi phone par chalegi.

## Install (sirf phone se)

1. Phone ke browser mein kholo: `https://github.com/gopalkrishan680-hash/GK/releases/tag/gk-camera-latest`
2. **GKCamera.apk** download karo aur open karo.
3. "Install unknown apps" ki permission maange to allow karo.
4. App kholo, Camera aur Microphone permission do.

Photos aur videos `DCIM/GKCamera` folder mein save hote hain.

## Features

**PHOTO**
- Ultra HDR photos (HDR screen par zyada bright highlights), max-quality capture, JPEG quality 100
- Phone ki apni processing: HDR+, NIGHT, PORTRAIT, AI AUTO (jo bhi Motorola third-party apps ko deta hai)
- 50MP full-resolution mode, 4:3 / 16:9, flash, timer (3s / 10s), grid
- Tap to focus, pinch zoom, lens buttons (0.5x / 1x / 2x / 3x ...), EV slider

**VIDEO**
- 4K / 1080p / 720p, 30 ya 60 fps, high bitrate
- HDR 10-bit (HLG) video, stabilisation (STEADY), pause/resume, torch, mic on/off

**PRO**
- RAW (DNG) + JPEG
- Manual ISO, shutter speed, manual focus, white balance presets

Volume button se bhi photo / recording start hoti hai.

Jo feature phone ya lens support nahi karta uska button dim dikhta hai.

## Build

Har push par GitHub Actions (`.github/workflows/camera-app.yml`) APK banata hai aur
`gk-camera-latest` release par daal deta hai. Local build: `./gradlew assembleRelease`.
