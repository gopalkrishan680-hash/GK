# Stickman Studio · AI voice server

Free text-to-speech for Stickman Studio, with optional cloning of **your own voice**. It runs
[Chatterbox Multilingual](https://github.com/resemble-ai/chatterbox) (Resemble AI, MIT licence:
Hindi + 22 more languages, zero-shot voice cloning, emotion strength control) on Google Colab's
free GPU. Stickman Studio (web page and Android app) sends the script to it and gets back the
voiceover plus the exact start/end of every sentence, so scenes line up with the voice.

## Start it
1. Open the notebook in Colab:
   https://colab.research.google.com/github/gopalkrishan680-hash/GK/blob/claude/stickman-video-voiceover-sync-ivss4s/stickman-voice/StickmanVoice.ipynb
2. **Runtime → Change runtime type → T4 GPU**.
3. Run cell 1 (install, restarts the runtime once), then cell 2.
4. Copy the `https://….trycloudflare.com` link into Stickman Studio → **Script + Voice → AI voice → Connect**.

The link changes on every start. Colab's free GPU stops after a few hours or a long idle time;
run cell 2 again and paste the new link.

## Writing the script
Write Hindi in Devanagari (हिंदी) for natural Hindi; English words can stay in English.
Tags (spoken, never shown in captions or image prompts):

| Tag | Effect |
|---|---|
| `[pause]`, `[pause 1.5]`, `[short pause]`, `[long pause]` | silence |
| `[laugh]` `[chuckle]` `[sigh]` `[gasp]` `[hmm]` `[cry]` | your own recorded sound if you recorded one, otherwise a short spoken stand-in |
| `[happy]` `[excited]` `[sad]` `[angry]` `[calm]` `[whisper]` `[serious]` `[normal]` | mood for the following text until the next mood tag; uses your recording in that mood when you made one |

Sentences get a natural pause after them (slightly varied), commas a shorter one; both are set in the app.
If one line comes out wrong (skipped or extra words), press **Redo** next to it in the app.

## Sample cleaning (automatic)
Every sample you upload or record is cleaned before it is used for cloning (`POST /refine`):

1. **Background noise** is reduced with spectral gating (`noisereduce`).
2. For speech samples, a speech recogniser (faster-whisper, `medium` on the GPU) finds every word;
   fillers such as *uh, um, hmm, अं, उम्म, हम्म*, plus coughs, breaths and clicks between words, are cut,
   and long silences are shortened. Real words like *हाँ* or *okay* stay. If too little speech would be
   left, the whole sample is kept.
3. **Crystal clear** (optional, on by default): a gentle studio chain, not an AI re-synthesis, so the
   voice stays yours: high-pass at 70 Hz, −2 dB at 300 Hz, +2.5 dB at 4 kHz, +1 dB above 9 kHz,
   2.5:1 compression above −24 dBFS and an even speech level.

Sounds (laugh, sigh…) only get the noise reduction and the polish; nothing is cut from them.
The app shows what was done (e.g. "background noise −19 dB · 4 fillers removed · clarity polished")
and keeps the original so you can compare. Both steps can be switched off in the app.

## Your voice
In Stickman Studio open **My voice and my sounds**, confirm that it is your voice (or that you have
the speaker's permission), and record the **Main voice** (10–20 s of normal speech). Optional: the same
in other moods, and real sounds such as your laugh. Recordings stay on that device and are not put
into project files. Only clone voices you have the right to use. Chatterbox adds Resemble AI's
inaudible watermark to its audio.

## API (for reference)
`GET /health` · `POST /jobs` `{units, language, voice{mood: wav-base64}, sounds{name: wav-base64}, expressiveness, sentence_pause, comma_pause, seed}` ·
`GET /jobs/{id}` (status, progress, timings) · `GET /jobs/{id}/audio` (24 kHz WAV) · `POST /jobs/{id}/redo` `{unit, seed?}`.

`POST /refine` `{audio, language, kind: voice|sound, enhance}` → `{audio, report}`.

Test without a GPU: `STICKMAN_FAKE_TTS=1 python server.py` (beeps instead of speech; cleaning then only reduces noise).
