# Kahani 3D Studio (Android)

Script + voiceover se 3D style video banane wali Android app. Sab kuch phone par
hota hai — koi paid API ya server nahi chahiye.

## Kaam kaise karta hai

| Step | Kya hota hai |
|------|--------------|
| 1. Script | Script paste karo (Hindi / Hinglish / English). App use scenes me todta hai (har sentence, har line, ya smart ~8 sec). |
| 2. Voiceover | MP3 / M4A / WAV / OGG upload karo. App voice ke pauses dhoondhta hai aur scene cuts unhi par sync karta hai. |
| 3. Style | 8 3D styles (Pixar 3D, Realistic, Clay, Anime 3D, Miniature, Low Poly, Mythology, Horror) + 9:16 / 16:9 / 1:1 / 4:5. |
| 4. Prompts | Har scene ka image prompt. **Offline** (template) ya **AI** (free Gemini key — Hindi script se bhi badhiya English prompts). Copy All / Share .txt. |
| 5. Images | Prompts kisi bhi image generator me daalo, images ko `1.png, 2.png…` naam se save karo, **Bulk Upload** — order apne aap. |
| 6. Edit | Live preview, per-scene duration / 3D motion / transition, captions, filters, music. |
| 7. Export | 480p / 720p / 1080p, 24/30/60 fps MP4 → Gallery `Movies/Kahani3D`. Bina watermark. |

## Features

- **3D camera motion**: Zoom in/out, Pan, 3D Tilt, 3D Orbit, Dolly + Rotate, Handheld shake, Auto mix
- **Transitions**: Crossfade, Slide, Zoom Through, Flash, Dip to Black, 3D Cube Flip
- **Auto captions** script se, word-by-word timing: Karaoke highlight, Bold Box, Classic, Pop single word
- **Character Bible**: character ka look ek baar likho, jis scene me naam aaye uske prompt me jud jata hai (consistent faces)
- **Voice auto-sync**: voiceover ke pauses par scene cuts
- **Background music** with auto-ducking (bolte waqt music dheema)
- Color filters (Vivid, Warm, Cool, Teal & Orange, Vintage, B&W), vignette, letterbox, film grain, watermark
- Scene merge / split / reorder / delete, manual duration lock
- Projects auto-save, duplicate, delete

## APK kaise milega

- GitHub par **Actions → Build Android APK** chalta hai har push par; run kholke
  `Kahani3DStudio-apk` artifact download karo.
- Ya khud build karo: Android Studio me `android/` folder kholo, ya
  `cd android && ./gradlew assembleRelease`.

Min Android 8.0 (API 26).
