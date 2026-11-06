# Stickman Studio

An app for turning a script + voiceover into a stickman video. It is a single file (`index.html`) with nothing to install.
Open it in **Chrome or Edge** (double-click the file). Everything runs locally on your computer.

## How to use

1. **Script + Voice:** Paste the script, drop in the voiceover (mp3/wav/m4a) and press **Create scenes**.
   The script is split at every `,` `.` `।` `?` `!` `;`. Each piece becomes one scene (one image).
   - Very short pieces (like "and said,") are joined with the next scene (*Min words per scene*).
   - Very long pieces are split into equal parts (*Max words per scene*).
   - To highlight a word in yellow in the captions, wrap it in stars: `*58 percent*`.
2. **Image Prompts:** Pick a style and characters. Each prompt asks for **one image with a 3×3 grid = 9 scenes**,
   so 120 scenes need only 14 prompts.
   - Press **Copy next prompt** and paste it into ChatGPT or Gemini.
   - Copy the generated image (right-click → Copy image) and press `Ctrl+V` anywhere in the app.
     It goes into the right card and is cut into its 9 scenes. Repeat until the progress bar is full.
   - You can also drop the downloaded file on its card.
   - *AI Scene Director:* give ChatGPT one prompt covering the whole script and it writes a visual for every scene.
     Paste the reply back and press **Apply** to get better prompts.
3. **Grid Upload:** Drop all grid images at once. The app finds the lines between panels and cuts each
   panel out. If a cut looks wrong, turn off *Auto-detect* or increase *Trim*.
   If one panel is bad, use **Single image** to replace just that scene.
4. **Voice Sync:**
   - **Smart Sync:** finds the pauses in the voiceover and changes scenes there.
   - **Tap Sync:** listen and press `Space` at the start of each new scene. This is the most accurate.
   - Drag the lines on the timeline, or edit the numbers, to fine-tune.
   - **SRT import/export:** take timing from an SRT file, or export an SRT for YouTube.
5. **Preview + Export:** set captions, transition, camera zoom/pan and background music, then press
   **Export video**. Export runs in real time, so keep the tab open.
   Chrome produces MP4. If a browser gives WebM instead:
   `ffmpeg -i video.webm -c:v libx264 -c:a aac video.mp4`

## Extra features
- 16:9, 9:16 (Shorts) and 1:1. 3×3 or 2×2 grid (2×2 = higher quality per scene).
- If the last batch has fewer scenes, it gets a 2×2 or single-image prompt.
- A gentle Ken Burns zoom/pan on every scene so the video never looks frozen (no-freeze rule).
- Big captions (Baloo 2, white with black outline, pop animation) at the top, center or bottom, or off.
- Transitions: quick fade, hard cut, white flash, slide, zoom punch.
- Autosaves in the browser. **Project save** stores the whole project (images + audio) in one file.
- The "Add channel characters" button adds the looks of Rohan, Maa, Papa and Aman to the prompt.

## Tips
- ChatGPT images are often 1536×1024, so in a 3×3 grid each scene is about 512px wide.
  For sharper scenes use the 2×2 grid, or have Gemini generate a larger image.
- White-background styles ask for **black lines** between panels and dark styles ask for **white lines**,
  so the app can find the lines easily.
