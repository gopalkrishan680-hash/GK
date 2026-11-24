# Faceless Studio (Android)

Faceless YouTube channels ke liye auto video editor. Aap sirf **script + voiceover + AI images** do,
baaki sab app karta hai: scenes, animations, transitions, captions, music ducking, sound effects aur MP4 export.

## Kaise use karein (4 steps)

1. **Script** – script paste karo. `# Heading` likhoge to chapter / checklist card banega.
2. **Voice** – voiceover file chuno (mp3/m4a/wav). App aawaz ke pauses detect karke har line ko sahi time par sync karta hai.
   Background music optional hai (voice aate hi music apne aap dheemi ho jati hai).
3. **Images** – AI images script ke order me add karo. "AI Prompts" button har scene ke liye ready prompt deta hai.
4. **Style** – size (YouTube 16:9 / Shorts 9:16 / Square), color theme, editing speed, captions, SFX, grain, channel handle.

**Video Generate Karo** dabao → Editor khulega → preview dekho → **Export** (720p / 1080p, background me render hota hai,
video `Movies/FacelessStudio` me save hoti hai).

## Auto-animation kaise decide hota hai

App har line padh kar khud layout chunta hai, aur ek hi layout lagatar repeat nahi karta:

| Script me ye ho | Animation |
|---|---|
| `# Heading` | Checklist card (✓ progress) ya chapter title |
| Lagatar sawal (`Kya? Kab? Kaise?`) | Typewriter question list (red underline) |
| Number (`723,335`, `50%`, `₹500`) | Number counter + rays |
| Date / time (`3 Dec 1984`, `12:00 AM`) | Date stamp + blinking digital clock |
| `A vs B` | Versus split boxes |
| `Title: a, b aur c` | Mind-map tree |
| `"quote"` | Word-by-word quote |
| `kyunki / isliye / because` | Cause → effect arrow |
| Chhoti line (≤4 words) | Kinetic typography (pop / stack / slide) |
| Baaki lines | Cinematic Ken Burns, image + red banner, headline on image, photo stack, phone mockup, paper note, spotlight text |

Manual control: line ke shuru me tag likho – `[stat] [list] [quote] [note] [phone] [headline] [image] [photos] [banner] [kinetic] [spotlight] [arrow] [mindmap] [date] [vs]`.
`---` = hard cut.

**18 layouts × har ek ke kai variants × 11 transitions × 6 themes**, sab random seed se. "Shuffle animations" dabao to poori
video ka naya look ban jata hai (timing same rehti hai). Har scene ka layout, transition aur image editor me badal sakte ho, ya 🎲 se sirf us scene ko re-roll karo.

## Extra features

- Word-by-word karaoke captions (emphasis words highlight)
- Auto SFX: whoosh (transitions), pop (kinetic text), boom (chapters / numbers) – procedural, koi copyright issue nahi
- Music auto-ducking + fade in/out
- Film grain, vignette, camera shake, light leaks, progress bar, channel watermark, subscribe outro
- `.srt` captions export aur YouTube chapters (timestamps) copy
- Project save / duplicate / delete
- Hindi (Devanagari) text support (Poppins + Kalam fonts)

## Build

```bash
cd FacelessStudio
./gradlew assembleRelease     # APK: app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # analyzer + render tests (Robolectric)
```

GitHub par push karte hi `.github/workflows/faceless-studio-apk.yml` APK build karke Actions artifact me rakhta hai.

Release APK abhi debug key se sign hai (direct install ke liye). Play Store ke liye apni keystore use karein.

Min Android 8.0 (API 26). Fonts: Poppins, Kalam, Anton – SIL Open Font License (`OFL-fonts.txt`).
