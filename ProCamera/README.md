# ProCam – Android camera app

Kotlin + Jetpack Compose + CameraX se bana camera app.

## Features

| Feature | Kya karta hai |
|---|---|
| **Max quality capture** | `CAPTURE_MODE_MAXIMIZE_QUALITY`, JPEG quality 100, sensor ka highest 4:3 resolution |
| **NIGHT mode** | Phone me native Night mode (Samsung, Pixel, Xiaomi, OnePlus waghaira) ho to wahi use hota hai. Na ho to app khud **6 frames** leta hai, unhe align karta hai aur average karta hai. Isse noise kam hota hai, moving objects ki ghost image nahi banti, aur shadows thode bright ho jaate hain |
| **PORTRAIT** | Background blur (bokeh), agar phone support kare |
| **HDR** | Bright aur dark hisso ka balance, agar phone support kare |
| **PRO mode** | Manual ISO, shutter speed (2s tak), manual focus aur white balance (Sunny, Cloudy, Bulb, Tube) |
| **12 filters** | Original, Vivid, Vivid Warm, Vivid Cool, Dramatic, Drama Warm, Drama Cool, Cinematic, Film, Mono, Silvertone, Noir. Filter preview me live dikhta hai aur photo par bhi lagta hai |
| **Exposure (EV)** | Photo ko brighter/darker karne ka slider |
| Tap-to-focus, pinch zoom | Saath me 0.5x / 1x / 2x / 5x zoom buttons |
| Flash | Off / Auto / On |
| Timer | 3s / 10s |
| Grid | Rule-of-thirds lines |
| Volume button shutter | Volume up/down dabane se photo kheenchti hai |
| Auto rotation | Phone tedha karke li gayi photo bhi seedhi save hoti hai |
| Gallery | Photos `Pictures/ProCam` me save hoti hain. Neeche-left thumbnail dabane par last photo khulti hai |

## APK kaise milega

Har push par GitHub Actions apne aap APK banata hai:

1. GitHub repo me **Releases** kholiye. Wahan latest **"ProCam build N"** me `ProCam.apk` milegi.
   (Ya phir **Actions → Build ProCam APK →** latest run **→ Artifacts**.)
2. Phone par APK download kariye, kholiye, aur "Install unknown apps" allow kariye.

## Signing note

`app/procam-dev.keystore` ek **development key** hai, jiska password repo me hi hai. Isse har naya build purane
build ke upar seedha install ho jaata hai. Play Store par publish karna ho to apni private key banaiye aur use
GitHub Secrets me rakhiye.

## Local build

```
cd ProCamera
./gradlew assembleRelease
```
