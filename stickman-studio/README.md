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
   - Copy the generated image (right-click → Copy image), **click the card it belongs to** (it turns yellow)
     and press `Ctrl+V`. The yellow box at the bottom right always shows which card the paste goes to.
     The image is cut into its 9 scenes and the next empty card is selected for you.
   - Clicking a card that already has an image and pressing `Ctrl+V` replaces that image.
   - Wrong image pasted? Press **Remove** on that card, then `Ctrl+V` the right image. It goes back into the same card.
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
   **Export video**. In Chrome/Edge the app draws every frame at its exact time and writes a normal MP4
   (H.264), so picture and voice stay in sync for the whole video and the file can be scrubbed in any
   player. It is usually faster than the video length.
   Older browsers fall back to live recording, which needs the tab to stay open and in front.

## Extra features
- 16:9, 9:16 (Shorts) and 1:1. 3×3 or 2×2 grid (2×2 = higher quality per scene).
- If the last batch has fewer scenes, it gets a 2×2 or single-image prompt.
- A gentle Ken Burns zoom/pan on every scene so the video never looks frozen (no-freeze rule).
- Big captions (Baloo 2, white with black outline, pop animation). By default they sit in a dark bar
  below the picture, so they never cover the drawing. They can also go on the picture (top, center, bottom) or be turned off.
- **Picture fit:** if a grid panel has a different shape than the video (ChatGPT often makes 3:2 images),
  the whole panel is shown with blurred sides instead of cutting its edges off.
- Grid cutting looks for black or white lines between panels, and for straight edges where panels touch
  without any line.
- **Smart Sync** first pins every sentence (ending in `.` `।` `?` `!`) to the longer pauses of the voiceover,
  for the whole script at once, then spreads the comma pieces inside each sentence by their length and snaps
  them to small gaps. Pauses are judged against the voice level around them, so background music does not
  confuse it, and missing pauses at commas do not make it fall apart. Mistakes stay inside one sentence
  instead of adding up over the video.
- Projects saved with an older version get their scene timing redone once when they are opened.
- Transitions: quick fade, hard cut, white flash, slide, zoom punch.
- Autosaves in the browser. **Project save** stores the whole project (images + audio) in one file.
- The "Add channel characters" button adds the looks of Rohan, Maa, Papa and Aman to the prompt.

## Tips
- ChatGPT images are often 1536×1024, so in a 3×3 grid each scene is about 512px wide.
  For sharper scenes use the 2×2 grid, or have Gemini generate a larger image.
- White-background styles ask for **black lines** between panels and dark styles ask for **white lines**,
  so the app can find the lines easily.
