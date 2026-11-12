# Desi Jokes 😂 (Android, offline)

Aajkal ke Indian style jokes — Hinglish mein, bina filter ke. Internet ki zaroorat nahi
(app mein INTERNET permission hi nahi hai).

## Install
1. `release/DesiJokes-v1.0.apk` phone pe download karo.
2. Kholo → "Unknown apps / Install from this source" allow karo → Install.

## Features
- 180+ jokes, 11 categories: Pati Patni, Desi Mummy Papa, Office, Dosti, Pappu Golu,
  Students, Love & Breakup, Aajkal Ka India, Rishtedaar & Shaadi, One Liners,
  aur 🔞 Double Meaning (18+ confirm karne ke baad hi khulta hai)
- Swipe left/right, 🎲 random, double-tap ya ❤️ se save, copy, WhatsApp share, search

## Naye jokes kaise daalein
`app/src/main/assets/jokes.json` mein kisi bhi category ki `jokes` list mein line add karo,
phir build karo:

```
./gradlew assembleRelease   # APK: app/build/outputs/apk/release/app-release.apk
```
(Android SDK chahiye; `local.properties` mein `sdk.dir=...` set karo.)
