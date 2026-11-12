# Desi Jokes 😂 (Android, offline)

Aajkal ke Indian style jokes — Hinglish mein, bina filter ke. Internet ki zaroorat nahi
(app mein INTERNET permission hi nahi hai).

## Install
1. `release/DesiJokes-v1.1.apk` phone pe download karo.
2. Kholo → "Unknown apps / Install from this source" allow karo → Install.

## Features
- 270+ jokes, 12 categories: Pati Patni, Desi Mummy Papa, Office, Dosti, Pappu Golu,
  Students, Love & Breakup, Aajkal Ka India, Rishtedaar & Shaadi, Cricket & IPL, One Liners,
  aur 🔞 Double Meaning (18+ confirm karne ke baad hi khulta hai, menu se dobara lock bhi)
- 🖼️ Joke ko photo card bana ke share karo (WhatsApp/Insta status ke liye)
- 🔊 Suno — joke phone ki offline Hindi voice mein padh ke sunata hai
- ☀️ Aaj ka joke + 🔔 roz shaam 8 baje notification
- 📳 Phone hilao, naya joke
- ✍️ Apne jokes likho aur save karo (long-press karke delete)
- Unseen jokes pehle aate hain, taaki repeat kam ho
- Swipe, 🎲 random, double-tap = save, copy, share, search, text size chhota/bada

## Naye jokes kaise daalein
`app/src/main/assets/jokes.json` mein kisi bhi category ki `jokes` list mein line add karo,
phir build karo:

```
./gradlew assembleRelease   # APK: app/build/outputs/apk/release/app-release.apk
```
(Android SDK chahiye; `local.properties` mein `sdk.dir=...` set karo.)
