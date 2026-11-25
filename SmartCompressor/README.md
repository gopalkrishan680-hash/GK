# 📦 Smart Compressor (Android) – 100% Offline

Badi **videos, photos, PDF, audio** aur koi bhi file ko phone par hi, bina internet ke, chhota karne wali app.
App me `INTERNET` permission hai hi nahi – aapki files kabhi phone se bahar nahi jaati.

## Install kaise karein
1. Is folder me `release/SmartCompressor-v1.0.apk` phone me download karo.
2. Kholo → "Unknown apps install karne do" allow karo → **Install**.
3. Android 8.0 (API 26) ya usse naya phone chahiye.

## Kya-kya kar sakti hai
| Type | Kaise compress hota hai | Aam bachat |
|---|---|---|
| 🎬 Video | H.265 (HEVC) hardware encoder, smart bitrate, zarurat ho to 4K→1080p/720p. HDR video ke liye auto fallback | Phone camera video: **80-95%** |
| 🖼️ Photo | Smart resize + JPG/WebP, EXIF rotation sahi | **70-90%** |
| 📄 PDF | *Smart*: andar ki images recompress, text same rahega. *Scan mode*: har page image – sabse chhota | Scanned/photo PDF: **70-95%** |
| 🎵 Audio | WAV/FLAC/MP3 → AAC (.m4a) | WAV/FLAC: **90%+**, MP3: 20-50% |
| 📦 Baaki | Lossless ZIP (max level) | File par depend |

### Extra features
- 🎯 **Target size mode** – slider se batao "90% chhota karo", app bitrate/quality/resolution khud adjust karega
- 3 levels: Best Quality / Balanced / Max Saving
- Ek saath **kai files** (batch queue)
- Kisi bhi app se **Share → Smart Compressor**
- Background me chalta hai, notification me progress; app band kar sakte ho
- Original file kabhi delete/change nahi hoti; nayi file `Download/SmartCompressor/` me
- File pehle se compressed ho to bekaar ki badi file nahi banata – bata deta hai
- History + "ab tak kitna MB/GB bachaya" counter, Open/Share buttons
- Advanced: video resolution, awaaz hatao, H.264 compatibility, WebP, PDF scan mode
- Dark mode + Material You colors, settings yaad rehti hain

## ⚠️ Sach baat – "90% bina quality loss"
Koi bhi app **har** file ko 90% tak bina quality loss ke chhota nahi kar sakti – ye maths/physics ka limit hai.
- Jo files **zyada bitrate pe bani hain** (phone camera video, scanner PDF, DSLR photo, WAV) – unme 85-95% bachat
  aisi quality ke saath milti hai jo aankh/kaan se farq pata nahi chalta.
- Jo files **pehle se compressed** hain (WhatsApp video, chhote text PDF, MP3, ZIP) – unme 90% sirf quality gira ke hi possible hai.
  Isliye app aise case me "pehle se compressed" bata deta hai, ya Target mode me jitna aap bolo utna kar deta hai.

## Khud build karna
```bash
cd SmartCompressor
./gradlew assembleRelease     # APK: app/build/outputs/apk/release/app-release.apk
```
GitHub par push karne pe `.github/workflows/smartcompressor-apk.yml` bhi APK bana deta hai (Actions → Artifacts).

Tech: Kotlin, Jetpack Compose, AndroidX Media3 Transformer, PdfBox-Android.
`signing/debug.keystore` sirf sideload ke liye standard debug key hai – Play Store pe daalna ho to apni release key banao.
