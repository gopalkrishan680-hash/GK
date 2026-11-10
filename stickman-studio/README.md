# Stickman Studio

An app for turning a script + voiceover into a stickman video. It is a single file (`index.html`) with nothing to install.
Open it in **Chrome or Edge** (double-click the file). Everything runs locally on your computer.

> **Simple mode (current):** the app takes your script, your voiceover and your images, and makes the video.
> The AI voice panel and the drawn stickman scenes are switched off and not shown; the sections about them below
> describe code that stays in the file, inactive.

## Layered explainer (default for new projects)
Step 1 has a **Video style** choice. *Layered explainer* makes the paper-cut-out explainer look: an old-paper
background, and cut-out pictures (the stickman in different poses, objects), bold keywords and icons that pop in one
by one on the word where they are spoken. The screen clears at every new **board**.

1. **Script + Voice:** paste the script, add the voiceover, press **Create scenes** (short scenes = exact timing).
2. **Layers → Plan the boards:** **Auto plan with Gemini** (free API key), or **Copy plan prompt** into ChatGPT /
   Claude / Gemini and paste the JSON reply, then **Apply plan**. **Quick plan (offline)** makes a simple plan
   without AI. The plan lists the pictures ("assets") and, per board, which picture / keyword / icon appears on
   which word. It can be edited by hand under *Edit the plan by hand (JSON)*.
3. **Layers → Pictures:** each **sheet prompt** asks for up to 9 pictures (3×3, square image) on one white image;
   *Pictures per sheet* can be set to 6 (3×2) for sharper pictures. Generate it in
   ChatGPT / Gemini and paste it into its sheet (or Share → Stickman Studio on Android). The app finds every
   picture on the sheet, cuts it out and removes the white (line art keeps its ink, the white inside becomes
   paper). A bad picture: click it, **Single prompt**, then **Replace…**. *Draw all with Gemini* does it by itself
   when the Google plan includes an image model.
   **My picture library:** every cut-out picture is kept inside the app (not in the gallery). When a new plan needs
   a picture the library already has (same art style and main character, same kind, same name or a very similar name
   and description), it is used again and left out of the sheet prompts, so only missing pictures are drawn. The plan
   prompt lists the library so the AI can pick those pictures. *Draw a new one* drops a library picture for one
   project. **Back up library** saves everything to one file; **Restore** brings it back on a new phone or after a
   reinstall.
4. **Preview + Export:** the same exact-frame MP4 export. Sound effects (pop, whoosh, swish, ding, buzz) are made
   in the app and placed on every entrance; background music gets quieter under the voice and fades in/out.

Plan format (what the AI returns):
```json
{"assets": [{"id": "hero_explain", "type": "character", "prompt": "the stickman explaining with one open hand"}],
 "boards": [{"scene": 1, "layout": "auto", "items": [
   {"scene": 1, "show": "hero_explain"},
   {"scene": 2, "show": "brain", "label": "Brain", "word": "brain"},
   {"scene": 3, "text": "*FORCE*", "icon": "x"},
   {"scene": 3, "icon": "equals"},
   {"scene": 4, "strike": "brain"},
   {"scene": 5, "title": "PHASE 2:"}]}]}
```
Icons: x, check, right, left, down, up, equals, plus, vs, question, exclaim. `"layout": "tree"` draws arrows from the
title to the items. `"row"` and `"big"` adjust the layout. Look settings (background, entrance style, board change,
idle motion, camera, captions, effects volume) are in the Layers tab.

## How to use (grid images)

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

## Drawn stickman scenes (offline, no images needed)
Every scene without an uploaded image gets a stickman drawing made by the app itself (setting
**Preview + Export → Pictures**: *My images; drawn where there is none* (default), *Always drawn*, or *Only my
images*). The figures are original, drawn in code with hand-drawn style lines; no image files, no internet.
**Drawing style** (same place) picks how they look; the default is **Chibi**: big head, big shiny eyes, full
hair (long hair or a bun for women, grey for grandparents), blush, round hands and shoes. Twelve other
styles: classic, pen sketch, bold cartoon, flat colour, expressive doodle, marker, chalkboard, notebook,
silhouette, bean body, neon and comic.
- **From the line:** names in the script (Rohan, Neha… keep the same look in every scene; female verbs such as
  *gayi / rahi / thi* give long hair and a dress), roles (papa → moustache, maa / dadi → bun, teacher → glasses,
  boss → tie, bachcha → smaller), crowds (*sab log, team, family* → 3 people).
- **Poses (51):** on a phone call, eating, writing, watching TV, clapping, namaste, thumbs up, stop, fist up,
  yawning, tired, headache, shouting, pushing, hugging, dancing, looking up, standing, hands on hips, talking, pointing, presenting, waving, walking, running, cheering,
  laughing, shocked, sad, crying, angry, scared, thinking, confused, shrugging, facepalm, phone, reading, chai,
  carrying, holding a sign, trophy, sitting, working at a laptop, sitting on the floor, kneeling, jumping,
  sleeping in bed, lying down.
- **Faces (13)**, **things** (desk + laptop, phone, book, chai, money bag, falling money, clock, chart up / down,
  rain cloud, idea bulb, door, house, tree, sun, blackboard, stairs, target, heart, trophy, bed, TV and sofa,
  food on a table, car, pile of books; *raat / night* adds a moon and stars) and
  **bubbles**: a "quote" in the line becomes a speech bubble; questions and *soch* get thought bubbles.
- **Talking:** *X ne kaha / poocha* puts the listener (the previous scene's main character) in the scene; a line
  that is only a "quote" continues with the same people and mood.
- **Real movement:** arms and legs animate (walk, run, wave, laugh, sob, shake with anger, tremble, hop, type,
  sip, scratch the head, snore…).
- **Nothing in the library for a line?** Its key word (e.g. *Share Market*, *Emergency Fund*, *Compounding*) is
  written onto a board on an easel while the character points at it. Type your own board text in ✏️ Drawing,
  or choose Thing → Nothing to leave the board out.
- **Change anything:** under each scene, **✏️ Drawing** has Pose, Face, Thing, People and Bubble menus
  (*auto* shows what was chosen). A scene with an uploaded image can be switched to the drawing and back.

## Smart animation (offline, automatic)
In **Preview + Export**, *Smart animation* (on by default) reads every scene's line and animates it by
itself, with no internet and no extra steps:
- **Mood from the line:** a voice tag (`[laugh]`, `[sad]`, `[angry]`, `[gasp]`…) wins; otherwise words in Hindi,
  Hinglish or English (achanak/अचानक, hans/हँस, udaas/उदास, gussa/गुस्सा, darr/डर, pyaar/प्यार, bhaag/भाग, soch/सोच…)
  and `!` / `?` decide.
- **Camera:** zoom punch + shake (shock), slow push-in (love, scared, question), slow zoom-out (sad), pan (running),
  gentle zoom/pan otherwise.
- **The stickman moves:** a drawing on plain paper is lifted off the paper, so only the drawing hops (happy),
  bounces (laugh), jolts (shock), trembles (angry, scared), sinks (sad), sways (thinking) or breathes (calm).
  Pictures with a full background move as a whole, more gently.
- **Cartoon effects:** "!" (shock), "?" (question), sparkles, hearts, tears, sweat drop, 💢 and speed lines. The picture's own colours are never changed. An effect shows on the first scene of a run of the
  same mood.
- **Word-by-word captions:** each word appears as it is spoken (timed from the word lengths), the current word in yellow.

Every scene in the scene list has an **Animation** menu: *auto* shows what was detected; pick another mood or
*No motion* to change it. *Animation strength* makes all of it calmer or stronger.

## AI voice with Google Gemini (most natural)
In **Script + Voice → AI voice**, choose **Voice engine: Google Gemini**. It uses Gemini 3.8 TTS (the
speech model in Google AI Studio) straight from the app; no Colab needed.

1. Get a free API key at https://aistudio.google.com/apikey and paste it (it stays on this device).
2. Pick **Style** (Narrative, Promo – High, Dramatic storyteller, Documentary, Conversational, Calm or
   your own words), **Pace** (Natural / Slow / Fast) and **Accent** (Neutral / Neutral Indian / Native Hindi).
3. **Voice**: one of Google's 30 voices, or your own voice in one of two ways:
   - **My voice – free**: Gemini speaks in a base voice close to yours (Charon, Puck, Kore…), then the free
     voice model on the Colab notebook (server v13+) turns each part into your voice. Needs your voice sample
     and the Colab link connected (box below the voice options).
   - **My voice – Google cloning**: needs a paid Google tier (Paid Quota Tier 1, i.e. billing turned on for
     the API key's project). Add your voice sample
   (10–20 s, clean) and record Google's consent sentence in the same room:
   *"I am the owner of this voice and I consent to Google using this voice to create a synthetic voice model."*
   The app creates a private voice key (Google does not store the voice: `store=false`).
4. **Generate voiceover**. Consecutive sentences are spoken together (about a paragraph at a time) so the
   delivery flows naturally; `[laugh]`, `[sigh]`, `[pause]` become Gemini's `<laugh>`, `<sigh>`,
   `<short pause>`, and `[happy]`/`[sad]`… change the mood of the following part. Scenes are synced from the
   pauses inside each part. **Redo** speaks the part holding that line again and puts it in place.

Free keys have per-minute limits; the app waits and continues by itself.

## AI voice on Colab (optional)
No voiceover yet? In **Script + Voice → AI voice** the app turns the script into one, in a ready-made
voice or **your own cloned voice**, with `[pause]`, `[laugh]`, `[sigh]`, `[happy]`, `[sad]`… tags.
The voice model runs free on Google Colab; see `../stickman-voice/README.md` for the 3-step start.
The voiceover is set as the video's audio and every scene is timed from the voice itself.
**Download voiceover** saves it as a WAV file. Each line can be played or redone on its own.
Your voice samples are cleaned first (background noise, uh/um, coughs, long gaps removed, gentle
"crystal clear" polish that keeps your real voice); the original is kept for comparison.

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

- **Picture quality:** small panels are upscaled to the size they are shown at, JPEG blocks are smoothed,
  edges are sharpened and near-black lines / near-white backgrounds are cleaned up. It runs on any computer
  (no GPU or download needed) and can be turned off under *Picture quality*.
- Each grid card shows how many pixels wide one scene is.

## On a phone
A downloaded HTML file is hard to open on a phone (iPhone cannot run it from the Files app at all), so put
the app online once and open the link instead. The repository is public, so GitHub Pages is free:
GitHub → **Settings → Pages → Build and deployment → Deploy from a branch**, pick the branch and `/ (root)`,
then open `https://gopalkrishan680-hash.github.io/GK/stickman-studio/`.

What changes on a phone:
- Copy the image in ChatGPT/Gemini (long-press → Copy), tap the card, then tap **Paste image**.
  If the phone will not share the copied image, save it and use **Choose file…**.
- Tap Sync uses the big **Next scene** button. Timeline lines can be dragged with a finger; swipe to scroll.
- Export defaults to 720p (faster, less memory). Keep the screen on and the browser in front while it runs.
- Projects are saved in that phone's browser only. Use **Project save / Project open** to move a project
  between phone and computer.

## Tips
- Each scene gets a ninth of the image. Gemini can generate 4K images (about 1280×720 per scene, already sharp).
  ChatGPT makes at most 1536×1024 (about 500px per scene); the app sharpens those panels. The prompts ask for
  the highest resolution available.
- A browser AI upscaler (ESRGAN-slim) was tried too. On stickman line art it looked about the same as the
  built-in sharpening, but needs a GPU and a 3 MB model, so it is not included.
- White-background styles ask for **black lines** between panels and dark styles ask for **white lines**,
  so the app can find the lines easily.
