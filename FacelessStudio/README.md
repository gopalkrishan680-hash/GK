# Faceless Studio (Android)

An automatic video editor for faceless YouTube channels. You provide **a script, a voiceover and AI images**;
the app does the rest: scenes, animations, transitions, captions, music ducking, sound effects and MP4 export.

## How to use (4 steps)

1. **Script** – paste your script. A `# Heading` line becomes a chapter / checklist card.
2. **Voice** – pick a voiceover file (mp3/m4a/wav). The app detects pauses in the voice and syncs each line to the right moment.
   Background music is optional (it automatically ducks while the voice is speaking).
3. **Images** – made with **your own ChatGPT or Gemini**, grid style:
   - pick an art style, the line between panels, scenes per image (4 / 6 / 9) and describe your characters once
     (they are kept the same in every picture);
   - optional **AI Scene Director**: copy a part's prompt into ChatGPT/Gemini, paste the reply, Apply → every scene
     gets an English visual description (long scripts are split into parts);
   - **Copy next prompt** → paste in ChatGPT/Gemini → it draws one image with several scenes as a grid →
     **Share → Faceless Studio** (or Copy → Paste image) → the app cuts it into panels and puts each panel on its own scene,
     then selects the next card. Missing scenes fall back to text animations.
   - Or use your own ready-made images.
4. **Style** – size (YouTube 16:9 / Shorts 9:16 / Square), color theme, editing speed, captions, SFX, grain, channel handle.

Tap **Generate Video** → the editor opens → preview it → **Export** (720p / 1080p, renders in the background,
the video is saved to `Movies/FacelessStudio`).

## How animations are chosen automatically

The app reads every line, picks a layout for it, and never repeats the same layout back to back:

| If the script has | Animation |
|---|---|
| `# Heading` | Checklist card (✓ progress) or chapter title |
| Back-to-back questions (`What? When? How?`) | Typewriter question list (red underline) |
| Number (`723,335`, `50%`, `₹500`) | Number counter + rays |
| Date / time (`3 Dec 1984`, `12:00 AM`) | Date stamp + blinking digital clock |
| `A vs B` | Versus split boxes |
| `Title: a, b and c` | Mind-map tree |
| Percentage (`65%`) | Animated donut chart |
| Two or more numbers (`India 140 crore, China 141 crore`) | Animated bar chart |
| Two or more years (`1983 … 1998 … 2005`) | Timeline |
| `"quote"` | Word-by-word quote |
| `because / so` | Cause → effect arrow |
| Short line (≤4 words) | Kinetic typography (pop / stack / slide) |
| Everything else | Key-points infographic, cinematic Ken Burns with an info layer (fact card / annotation circle / headline / side tag), image + red banner, headline on image, photo stack, phone mockup, paper note, spotlight text |

Manual control: start a line with a tag – `[stat] [list] [quote] [note] [phone] [headline] [image] [photos] [banner] [kinetic] [spotlight] [arrow] [mindmap] [date] [vs]`.
`---` = hard cut.

**22 layouts × several variants each × 11 transitions × 6 themes**, all driven by a random seed. Tap "Shuffle animations" to give the whole
video a new look (timing stays the same). In the editor you can change any scene's layout, transition and image, or tap 🎲 to re-roll just that scene.

## Whiteboard Doodle style

Pick **Video style → Whiteboard Doodle** (or the ✏️ chip in the editor). Lines share a **board** (at least ~6 s,
up to 3 lines; a chapter or a number starts a new one), so nothing is rushed: as each line is spoken, its picture is
drawn on the board and the line is handwritten beneath it.

Drawing looks natural and different every time: the **outline grows along the sketch's own lines** from 2–3 starting
points (shortest paths through the dark pencil lines), then **colour is painted** in one of four orders — patches,
following the pen, outward from a point, or wavy brush strokes. Numbers get a big circled handwritten board. Marker
underlines, arrows and stars; soft marker sound; board slides or page turns between boards. No hand graphic.

## Variety engine (no boring repeats)

Every scene is a **combination** of six independent choices:

| Dimension | Options |
|---|---|
| Layout | 34 (22 classic + Image-in-Text, Film Strip, Magnifier, Breaking News, Chat Message, Search Bar, Circle Reveal, Diagonal Split, Word Cloud, Gauge, Steps, Sticky Notes) |
| Text entrance | 12: pop, rise, slide, type, cascade, blur, drop, wipe, flip, bounce, glitch, zoom |
| Backdrop | 12: blob, grid, glow, image, particles, sweep, rays, dots, waves, bokeh, stripes, aurora |
| Camera | 9: still, push, pull, drift left/right, rise, tilt, handheld, breathe |
| Decoration | 11: none, light sweep, sparkles, corners, confetti, scanlines, frame, shapes, flare, spotlight, brackets |
| Transition | 16 (incl. dip, page turn, doors, blinds, pixelate) |

Rules the planner follows for every video: a layout is used **at most twice** (only in very long videos, after every
layout has been used twice, does the cap rise — evenly for all), the same layout never returns within 3 scenes, every
dimension prefers its least-used option, and **no full combination ever appears twice**. Layouts that match the line's
content (numbers, dates, questions, lists, quotes…) get priority. The editor shows the live variety status.

**Script-accurate visuals:** when every line has its own picture (grid workflow), each scene shows the picture made
for that line — picture layouts come first, and text layouts are drawn over that same picture (sharp, dimmed) instead
of a plain background. Question lines are not merged, so no line's picture is lost. Only real names, numbers and
terms pop up on screen; headlines are real phrases of the line, never filler words.

## Extra features

- Caption modes: **Keywords** (default – only key words pop up, the visuals carry the story), **Full** word-by-word captions, or **Off**
- Auto SFX: whoosh (transitions), pop (kinetic text), boom (chapters / numbers) – generated by the app, no copyright issues
- Music auto-ducking + fade in/out
- Film grain, vignette, camera shake, light leaks, progress bar, channel watermark, subscribe outro
- `.srt` caption export and copyable YouTube chapter timestamps
- Project save / duplicate / delete
- Hindi (Devanagari) text support (Poppins + Kalam fonts)

## Build

```bash
cd FacelessStudio
./gradlew assembleRelease     # APK: app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # analyzer + render tests (Robolectric)
```

On every push to GitHub, `.github/workflows/faceless-studio-apk.yml` builds the APK and uploads it as an Actions artifact.

The release APK is currently signed with the debug key (for direct install). Use your own keystore for the Play Store.

Min Android 8.0 (API 26). Fonts: Poppins, Kalam, Anton – SIL Open Font License (`OFL-fonts.txt`).
