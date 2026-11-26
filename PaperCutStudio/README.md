# PaperCut Studio (Android)

This app turns a script and a voice-over into a **paper cut-out animation video**. Every element of every image is cut out on the phone, animated by itself, and timed to the voice-over.

## How it works

| Step | What you do | What the app does |
|---|---|---|
| **1 Script** | Paste the script, add the voice-over (mp3/m4a/wav), optionally add an SRT, character and style notes | Splits the script into scenes, finds the elements (Hindi, Hinglish and English words) and syncs every scene and word to the voice-over |
| **2 Prompts** | Copy the image prompts (one at a time or all at once), make the images in any AI tool, then import them (one by one, or all together with **Import all images**: `scene_1, scene_2 …` are sorted by name) | Writes one paper-cut prompt per scene. The style is locked and characters stay consistent across scenes |
| **3 Layers** | Tap **Separate all scenes**. Optionally tap an object to cut it out, or change animations | Offline: ML Kit finds the main subjects, MobileSAM finds extra objects (and powers tap-to-cut), and LaMa repaints the background behind the cut-outs |
| **4 Export** | Preview, then tap **Export video** | Renders an MP4 (H.264 + AAC voice-over) with the phone's hardware encoder. Save it to the gallery or share it |

## Features
- **Offline element separation**: ML Kit Subject Segmentation, plus MobileSAM (deep scan and tap-to-cut) and LaMa in-painting through ONNX Runtime. Download the models once in **Settings** (~45 MB SAM, ~208 MB LaMa). Without LaMa, a fast built-in fill is used.
- **Voice-over sync**: the app detects pauses in the voice-over, so scene cuts land on real pauses and words are spread over spoken time only. With an SRT the timing is exact. You can fine-tune each scene's start in ±0.2 s steps.
- **Word-triggered animation**: each element appears on the word that mentions it ("…*sooraj* nikla" makes the sun rise at that moment). You can change the trigger word for any layer.
- **Paper look**: white cut edges, paper grain, soft drop shadows, a vignette, and an optional **stop-motion** feel (12 fps steps plus paper "boil" wobble).
- **Animations**: 11 entrances (pop, slide, drop with bounce, grow-up, spin, paper flip, wobble…), 8 loops (bob, sway, walk, float, pulse, shake, spin…), camera moves with parallax (zoom or pan) and 5 scene transitions (paper slide, paper flip, fold, zoom, cut).
- **Animated captions**: a paper-strip subtitle with the current word highlighted.
- **Formats**: 16:9 (YouTube), 9:16 (Shorts/Reels) and 1:1.
- **Optional AI Director**: with a free Gemini API key, the app writes richer English prompts and picks animations. Without a key, everything is offline. There is also a "chatbot helper" message you can paste into ChatGPT or Gemini chat.

## Build
- **GitHub Actions**: every push that touches `PaperCutStudio/` builds the APK. Download it from the run's **Artifacts** (`PaperCutStudio-apk`).
- **Locally**: open the folder in Android Studio, or run `./gradlew :app:assembleRelease`. The APK is written to `app/build/outputs/apk/release/`.

The release APK is signed with the debug key so it installs directly. Use your own signing key before you publish to the Play Store.

Requires Android 10+ (API 29). A phone with 6 GB+ RAM is recommended for LaMa.

## Code map
```
data/    Models, ProjectRepository (one folder per project), ScriptPlanner (scenes + prompts), GeminiDirector
sync/    AudioTools (decode + loudness envelope), SyncEngine (pause-snapped / SRT timing)
ml/      LayerSeparator (ML Kit), MobileSam, Inpainter (LaMa + push-pull), Masks
render/  PaperStyle, Motion (enter/idle/boil), FrameRenderer (shared by preview and export)
export/  VideoExporter (MediaCodec + MediaMuxer)
ui/      Compose screens + StudioViewModel
```
