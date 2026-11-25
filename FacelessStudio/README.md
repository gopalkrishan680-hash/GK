# Faceless Studio (Android)

An automatic video editor for faceless YouTube channels. You provide **a script, a voiceover and AI images**;
the app does the rest: scenes, animations, transitions, captions, music ducking, sound effects and MP4 export.

## How to use (5 steps)

1. **Script** – paste your script. A `# Heading` line becomes a chapter / checklist card.
2. **Voice** – pick a voiceover file (mp3/m4a/wav). The app detects pauses in the voice and syncs each line to the right moment.
   Background music is optional (it automatically ducks while the voice is speaking).
3. **Look** – choose the **video style** (Explainer Canvas, Clean Sketch, Creative Whiteboard, Animated) and the **size**
   (16:9 / 9:16 / 1:1) *before* making images. The card "What you will make" shows which images the style needs and
   which animations it gives; the image prompts are written for that style automatically.
4. **Images** – made with **your own ChatGPT or Gemini**, grid style (a banner shows which style the images are for):
   - Explainer Canvas: first the **AI Visual Plan**, then one element per panel;
   - other styles: optional **AI Scene Director**, then grids (art style, panel line, scenes per image, characters);
   - **Copy next prompt** → paste in ChatGPT/Gemini → **Share → Faceless Studio** (or Copy → Paste image) → the app cuts
     the grid into panels. Or use your own ready-made images.
5. **Finish** – colour theme, editing speed, captions, SFX, grain, channel handle.

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

## Explainer Canvas style (AI Visual Plan)

**Video style → Explainer Canvas.** Like hand-drawn animated explainers: cute characters and objects are drawn
**one by one on an endless white canvas** while you speak — first the ink outline, then the colour is wiped in from the
top like a marker. Elements **move** next to each other (a virus attacks a cell, money goes to a bank), hand-drawn
**arrows** connect them, **key words** are handwritten in pink / orange / blue, and the **camera keeps gliding and
zooming** so the newest things are always framed. A `new` step moves the camera to a fresh part of the canvas; `bg`
tints the paper when the mood or place changes. Characters gently bob, and an element that is mentioned again gives a
small bounce. Not every line needs a drawing.

How to make one:
1. Images step → **AI Visual Plan** → *Copy plan prompt* → paste into ChatGPT/Gemini → copy the whole reply →
   *Paste reply* → *Apply*. The AI designs the **elements** (E1, E2 … each with a drawing description, re-used whenever
   the same thing comes back) and, for every line, the actions:
   `E3` draw · `E2 > E1` move · `arrow E3 > E1` · `text "memory"` · `new` · `bg blue` · `@word` = start on that spoken word.
   Long scripts are split into parts; later parts re-use the elements already designed.
2. The grid cards now ask for **elements**: each square panel is one character/object as a cut-out on plain white, in a
   cute explainer-doodle style, so ChatGPT/Gemini draws exactly what the plan needs. Share/paste the grid as usual.
3. Before the pictures arrive, the preview shows each element's name in a sketched circle, so the plan can be checked.

Layout rules the renderer follows: every part of the canvas is a **balanced composition that fills the screen** —
each time something is added or moves, the board is re-arranged into a pattern that suits the number of things (one
big in the middle, side by side with a slight stagger, a triangle, 2×2, 3+2…, different per board) and everything
glides calmly (~2 s) to its new place, so there are no gaps or lopsided lines. **Sizes follow the plan**: main
characters big, other characters normal, props small (the plan prompt asks the AI for big / normal / small). A
character that goes to another joins it side by side, travelling along a gentle arc over the others. Nothing is drawn
on top of anything else; arrows bend around things in between and are skipped when two things already touch.
Handwritten words are sized to their element and stick to it when it moves. Two versions of the same person (e.g.
"Karna" and "Karna with mace") never stand side by side: the new one replaces the old with a soft cross-fade.

Calm transitions: the camera re-frames over ~1.8 s and travels to a new part of the canvas over ~2.6 s while the old
board fades out (no half-cut things at the edges); camera moves that come close together are merged so it never
jitters. The camera arrives first, then the drawing starts (ink outline ~1 s, colour ~0.9 s). Soft paper tints only,
at most every ~10 s; the first drawing starts right away; a ~2.5 s hold at the end lets the last drawing finish.
The prompts ask for one element per person and full-body characters with white margin on every side.

## Clean Sketch style (like hand-drawn explainer Shorts)

**Video style → Clean Sketch.** The whole video is drawn on **one endless canvas**. Each board sits at its own
spot on a big white sheet; when the next board starts, the camera glides there (smooth ease with a gentle zoom-out) and
the old boards stay on the sheet. At the end the camera pulls back to show the whole canvas.

On every board, things appear **as they are spoken**: the first line is hand-lettered as a big title (names/numbers in
red with a yellow outline, red scribble underline); the board's picture — just the subject, trimmed of empty white —
is **drawn straight in full colour** (no outline-first pass), following the picture's own lines or in brush patches;
the other line is written as a short handwritten note with a blue underline. Not every line gets a picture: Clean
Sketch uses **one picture per two script lines**, so the grid workflow asks for half as many pictures. A line with a
real number gets a yellow price tag ("85%", "$15T") tied to its drawing. Boards last at least ~5 s, and cuts follow the
pauses in your voiceover, so the video stays calm.

With Clean Sketch on, the grid prompts and the AI Scene Director ask ChatGPT/Gemini for **cut-out subjects with no
background at all** (pure white, no scenery, ground, shadow or frame) in a marker-doodle style.

## Whiteboard Doodle style

Pick **Video style → Whiteboard Doodle** (or the ✏️ chip in the editor). Lines share a **board** (at least ~6 s,
up to 3 lines; a chapter or a number starts a new one), so nothing is rushed: as each line is spoken, its picture is
drawn on the board and the line is handwritten beneath it.

Drawing looks natural and different every time: the **outline grows along the sketch's own lines** from 2–3 starting
points (shortest paths through the dark pencil lines), then **colour is painted** in one of four orders — patches,
following the pen, outward from a point, or wavy brush strokes. Numbers get a big circled handwritten board. Marker
underlines, arrows and stars; soft marker sound; board slides or page turns between boards. No hand graphic.

**Whiteboard variety:** every board is its own combination of layout (7 single-picture layouts + 2- and 3-picture
boards in 3 arrangements each) × frame (wobbly box, frameless cut-out, polaroid with tape, circle, torn paper) ×
draw method (outline then colour, long pencil then colour, direct brush, bold ink lines then colour, sketch dissolving
into colour) × text style (plain, highlighter, box, speech bubble, sticky note) × doodle (stars, shine lines, arrow,
swirl, ?/!, tick) × camera (still, zoom to the picture being drawn, pull back, drift) — never the same combination twice.
Transitions: slide, page turn, eraser wipe, fade, doors, circle, cut.

**Doodle-friendly pictures:** with Whiteboard Doodle on, the art style switches to *Whiteboard doodle* and both the AI
Scene Director prompt and the grid prompts ask for **only the main subject on a plain white background** (no scenery).
Pictures on white are drawn straight onto the board without a box.

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
