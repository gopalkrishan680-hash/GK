"""
Stickman Studio voice server.

Runs on a Google Colab GPU (see StickmanVoice.ipynb) and turns a script into a voiceover with
Chatterbox Multilingual (Resemble AI, MIT licence): Hindi and 22 other languages, zero-shot voice
cloning from a short recording, and an "exaggeration" knob for emotion.

Stickman Studio (the web page and the Android app) talks to it over HTTP:

    GET  /health                    -> {"ok": true, "engine": ..., "languages": [...]}
    POST /jobs                      -> {"job_id": ...}       start a voiceover
    GET  /jobs/{id}                 -> status, progress and, when done, per-line timings
    GET  /jobs/{id}/audio           -> the finished WAV (24 kHz mono)
    POST /jobs/{id}/redo            -> regenerate one line with a new seed, then rebuild the WAV

Script tags understood inside each line:
    [pause] [pause 1.5] [short pause]          silence (seconds optional)
    [laugh] [chuckle] [sigh] [gasp] [hmm] [cry] the speaker's own recorded sound if provided,
                                               otherwise a short spoken fallback
    [normal] [happy] [excited] [sad] [angry] [calm] [whisper] [serious]
                                               mood for the following text (stays until changed);
                                               uses that mood's own reference recording if one was given

Set STICKMAN_FAKE_TTS=1 to run without a GPU/model (beeps instead of speech) for testing.
"""
import base64
import copy
import io
import os
import random
import re
import tempfile
import threading
import time
import uuid
from typing import Dict, List, Optional

import numpy as np
import soundfile as sf
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel

SR = 24000  # Chatterbox output rate; Stickman Studio also sends its recordings at 24 kHz

# exaggeration (emotion strength), cfg_weight (lower = freer, more expressive pacing), temperature
MOODS = {
    "normal": dict(ex=0.5, cfg=0.5, temp=0.8),
    "happy": dict(ex=0.75, cfg=0.4, temp=0.85),
    "excited": dict(ex=0.95, cfg=0.3, temp=0.9),
    "sad": dict(ex=0.35, cfg=0.5, temp=0.7),
    "angry": dict(ex=0.9, cfg=0.3, temp=0.85),
    "calm": dict(ex=0.3, cfg=0.55, temp=0.7),
    "whisper": dict(ex=0.3, cfg=0.5, temp=0.7),
    "serious": dict(ex=0.4, cfg=0.55, temp=0.7),
}
SOUND_FALLBACK = {  # spoken stand-ins when the user has not recorded the sound
    "hi": {"laugh": "हा हा हा!", "chuckle": "हे हे।", "sigh": "हाय...", "gasp": "अरे!", "hmm": "हम्म...", "cry": "उफ़..."},
    "en": {"laugh": "Ha ha ha!", "chuckle": "Heh heh.", "sigh": "Haah...", "gasp": "Oh!", "hmm": "Hmm...", "cry": "Oh no..."},
}
SOUND_MOOD = {"laugh": "excited", "chuckle": "happy", "sigh": "sad", "gasp": "excited", "hmm": "calm", "cry": "sad"}
TAG_RE = re.compile(r"\[\s*([a-zA-Z]+)(?:\s+([a-zA-Z]+|\d+(?:\.\d+)?))?\s*\]")
STRONG_END = re.compile(r"[.!?।|…][\"'”’)»]*\s*$")


# ---------------------------------------------------------------- engines

class FakeEngine:
    """Beeps whose length follows the text, for testing without a GPU."""
    name = "fake"
    device = "cpu"

    def synth(self, text, lang, ref_path, ex, cfg, temp, seed):
        n = max(1, len(re.sub(r"\W", "", text)))
        dur = 0.3 + 0.06 * n
        t = np.arange(int(SR * dur)) / SR
        f = 180 + (seed % 7) * 15 + 60 * ex
        wav = 0.3 * np.sin(2 * np.pi * f * t) * (0.6 + 0.4 * np.sin(2 * np.pi * 3 * t))
        pad = np.zeros(int(SR * 0.15))
        return np.concatenate([pad, wav, pad]).astype(np.float32)


class ChatterboxEngine:
    name = "chatterbox-multilingual"

    def __init__(self):
        import torch
        from chatterbox.mtl_tts import ChatterboxMultilingualTTS, SUPPORTED_LANGUAGES
        self.torch = torch
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.model = ChatterboxMultilingualTTS.from_pretrained(device=self.device)
        self.languages = sorted(SUPPORTED_LANGUAGES.keys())
        self.default_conds = copy.deepcopy(self.model.conds)  # the built-in voice
        self.cond_cache: Dict[str, object] = {}

    def _conds_for(self, ref_path):
        if not ref_path:
            return copy.deepcopy(self.default_conds)
        if ref_path not in self.cond_cache:
            self.model.prepare_conditionals(ref_path, exaggeration=0.5)
            self.cond_cache[ref_path] = copy.deepcopy(self.model.conds)
        return copy.deepcopy(self.cond_cache[ref_path])

    def synth(self, text, lang, ref_path, ex, cfg, temp, seed):
        torch = self.torch
        torch.manual_seed(seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(seed)
        self.model.conds = self._conds_for(ref_path)
        wav = self.model.generate(text, language_id=lang, exaggeration=float(ex), cfg_weight=float(cfg), temperature=float(temp))
        wav = wav.squeeze(0).detach().cpu().numpy().astype(np.float32)
        if self.model.sr != SR:
            import librosa
            wav = librosa.resample(wav, orig_sr=self.model.sr, target_sr=SR)
        return wav


# ---------------------------------------------------------------- audio helpers

def silence(sec):
    return np.zeros(max(0, int(SR * sec)), dtype=np.float32)


def trim(wav, thresh=0.012, pad=0.04):
    """Cut quiet head/tail so the app controls the pauses itself."""
    if wav.size == 0:
        return wav
    env = np.abs(wav)
    win = int(SR * 0.01)
    if win > 1 and env.size > win:
        env = np.convolve(env, np.ones(win) / win, mode="same")
    loud = np.where(env > thresh)[0]
    if loud.size == 0:
        return wav[:0]
    a = max(0, loud[0] - int(SR * pad))
    b = min(wav.size, loud[-1] + int(SR * pad))
    return wav[a:b]


def decode_b64_wav(b64):
    data, sr = sf.read(io.BytesIO(base64.b64decode(b64)), dtype="float32", always_2d=True)
    data = data.mean(axis=1)
    if sr != SR:
        try:
            import librosa
            data = librosa.resample(data, orig_sr=sr, target_sr=SR)
        except ImportError:  # fake mode: plain linear resample is enough
            x = np.linspace(0, 1, num=int(len(data) * SR / sr))
            data = np.interp(x, np.linspace(0, 1, num=len(data)), data).astype(np.float32)
    return data.astype(np.float32)


def wav_bytes(wav):
    buf = io.BytesIO()
    sf.write(buf, wav, SR, format="WAV", subtype="PCM_16")
    return buf.getvalue()


def split_long(text, limit=220):
    """Chatterbox stays reliable on short inputs: split long lines at commas, then at spaces."""
    text = text.strip()
    if len(text) <= limit:
        return [text] if text else []
    parts, cur = [], ""
    for piece in re.split(r"(?<=[,;:—])\s+", text):
        if cur and len(cur) + len(piece) + 1 > limit:
            parts.append(cur)
            cur = piece
        else:
            cur = (cur + " " + piece).strip()
    if cur:
        parts.append(cur)
    out = []
    for p in parts:
        while len(p) > limit:
            cut = p.rfind(" ", 0, limit)
            cut = cut if cut > limit // 2 else limit
            out.append(p[:cut].strip())
            p = p[cut:].strip()
        if p:
            out.append(p)
    return out


def parse_line(text, mood):
    """Turn one line with tags into ops: ("say", text, mood) / ("pause", sec) / ("sound", name, mood)."""
    ops, pos = [], 0
    for m in TAG_RE.finditer(text):
        before = text[pos:m.start()]
        if before.strip():
            ops.append(("say", before.strip(), mood))
        pos = m.end()
        tag, arg = m.group(1).lower(), (m.group(2) or "").lower()
        if tag == "pause":
            sec = {"": 0.7, "short": 0.3, "long": 1.2}.get(arg)
            if sec is None:
                try:
                    sec = min(5.0, float(arg))
                except ValueError:
                    sec = 0.7
            ops.append(("pause", sec))
        elif tag == "short" and arg == "pause":
            ops.append(("pause", 0.3))
        elif tag in SOUND_MOOD:
            ops.append(("sound", tag, mood))
        elif tag in MOODS:
            mood = tag
        # unknown tags are ignored
    rest = text[pos:]
    if rest.strip():
        ops.append(("say", rest.strip(), mood))
    return ops, mood


# ---------------------------------------------------------------- jobs

class JobIn(BaseModel):
    units: List[str]
    language: str = "hi"
    voice: Dict[str, str] = {}      # mood -> base64 WAV of the user's voice in that mood
    sounds: Dict[str, str] = {}     # laugh/sigh/... -> base64 WAV
    expressiveness: float = 1.0     # multiplies every mood's exaggeration
    sentence_pause: float = 0.45
    comma_pause: float = 0.15
    seed: int = 7


class RedoIn(BaseModel):
    unit: int
    seed: Optional[int] = None


class Job:
    def __init__(self, spec: JobIn):
        self.id = uuid.uuid4().hex[:12]
        self.spec = spec
        self.status = "queued"
        self.message = ""
        self.done = 0
        self.total = len(spec.units)
        self.unit_audio: List[Optional[np.ndarray]] = [None] * self.total
        self.unit_mood_in: List[str] = ["normal"] * self.total
        self.unit_seed: List[int] = [spec.seed + i for i in range(self.total)]
        self.timings: List[dict] = []
        self.audio: Optional[bytes] = None
        self.duration = 0.0
        self.tmp = tempfile.mkdtemp(prefix="ssvoice_")
        self.refs: Dict[str, str] = {}
        for mood, b64 in spec.voice.items():
            if mood in MOODS and b64:
                path = os.path.join(self.tmp, f"ref_{mood}.wav")
                sf.write(path, trim(decode_b64_wav(b64)), SR)
                self.refs[mood] = path
        self.sounds = {k: trim(decode_b64_wav(v)) for k, v in spec.sounds.items() if k in SOUND_MOOD and v}


class VoiceServer:
    def __init__(self, engine):
        self.engine = engine
        self.jobs: Dict[str, Job] = {}
        self.queue: List[tuple] = []
        self.cv = threading.Condition()
        threading.Thread(target=self._worker, daemon=True).start()

    # --- synthesis of one line
    def _say(self, job, text, mood, seed):
        p = MOODS.get(mood, MOODS["normal"])
        ref = job.refs.get(mood) or job.refs.get("normal")
        ex = max(0.25, min(2.0, p["ex"] * job.spec.expressiveness))
        lang = job.spec.language
        expected = 1.0 + 0.11 * len(re.sub(r"\s", "", text))
        wav = None
        for attempt in range(3):  # retry when the model rambles far past the text length
            wav = trim(self.engine.synth(text, lang, ref, ex, p["cfg"], p["temp"], seed + 1000 * attempt))
            if 0 < wav.size / SR <= expected * 2.5 + 2:
                break
        return wav

    def _render_unit(self, job, i):
        ops, mood_out = parse_line(job.spec.units[i], job.unit_mood_in[i])
        pieces = []
        seed = job.unit_seed[i]
        lang = job.spec.language if job.spec.language in SOUND_FALLBACK else "en"
        for op in ops:
            if op[0] == "pause":
                pieces.append(silence(op[1]))
            elif op[0] == "sound":
                clip = job.sounds.get(op[1])
                if clip is None:
                    clip = self._say(job, SOUND_FALLBACK[lang][op[1]], SOUND_MOOD[op[1]], seed)
                pieces.append(clip)
                pieces.append(silence(0.12))
            else:
                subs = split_long(op[1])
                for k, sub in enumerate(subs):
                    pieces.append(self._say(job, sub, op[2], seed + 17 * k))
                    if k < len(subs) - 1:
                        pieces.append(silence(job.spec.comma_pause))
        job.unit_audio[i] = np.concatenate(pieces) if pieces else silence(0.2)
        return mood_out

    def _assemble(self, job):
        out = [silence(0.3)]
        t = 0.3
        job.timings = []
        rnd = random.Random(job.spec.seed)
        for i, wav in enumerate(job.unit_audio):
            start = t
            out.append(wav)
            t += wav.size / SR
            job.timings.append({"unit": i, "start": round(start, 3), "end": round(t, 3), "text": job.spec.units[i]})
            if i < len(job.unit_audio) - 1:
                base = job.spec.sentence_pause if STRONG_END.search(job.spec.units[i]) else job.spec.comma_pause * 2
                gap = max(0.05, base * rnd.uniform(0.85, 1.15))  # small natural variation
                out.append(silence(gap))
                t += gap
        out.append(silence(0.5))
        wav = np.concatenate(out)
        peak = float(np.max(np.abs(wav))) if wav.size else 0.0
        if peak > 0:
            wav = wav * (0.95 / peak)
        job.audio = wav_bytes(wav.astype(np.float32))
        job.duration = round(wav.size / SR, 3)

    def _run(self, job):
        job.status = "running"
        mood = "normal"
        for i in range(job.total):
            job.unit_mood_in[i] = mood
            mood = self._render_unit(job, i)
            job.done = i + 1
        self._assemble(job)
        job.status = "done"

    def _redo(self, job, unit, seed):
        job.status = "running"
        job.unit_seed[unit] = seed
        self._render_unit(job, unit)
        self._assemble(job)
        job.status = "done"

    def _worker(self):
        while True:
            with self.cv:
                while not self.queue:
                    self.cv.wait()
                kind, job, args = self.queue.pop(0)
            try:
                if kind == "run":
                    self._run(job)
                else:
                    self._redo(job, *args)
            except Exception as e:  # report to the app instead of dying
                job.status = "error"
                job.message = f"{type(e).__name__}: {e}"

    def submit(self, kind, job, args=()):
        with self.cv:
            self.queue.append((kind, job, args))
            self.cv.notify()


def create_app(engine):
    server = VoiceServer(engine)
    app = FastAPI(title="Stickman Studio voice server")
    # The app runs from file://, from the Android app and from GitHub Pages, so allow any origin.
    app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

    @app.get("/health")
    def health():
        return {"ok": True, "engine": engine.name, "device": getattr(engine, "device", "?"),
                "languages": getattr(engine, "languages", ["hi", "en"]), "busy": len(server.queue)}

    @app.post("/jobs")
    def create_job(spec: JobIn):
        if not spec.units:
            raise HTTPException(400, "The script is empty")
        if len(spec.units) > 2000:
            raise HTTPException(400, "Too many lines")
        job = Job(spec)
        server.jobs[job.id] = job
        server.submit("run", job)
        return {"job_id": job.id, "total": job.total}

    def get(job_id):
        job = server.jobs.get(job_id)
        if not job:
            raise HTTPException(404, "Unknown job (was the server restarted?)")
        return job

    @app.get("/jobs/{job_id}")
    def status(job_id: str):
        job = get(job_id)
        res = {"status": job.status, "done": job.done, "total": job.total, "message": job.message}
        if job.status == "done":
            res["timings"] = job.timings
            res["duration"] = job.duration
        return res

    @app.get("/jobs/{job_id}/audio")
    def audio(job_id: str):
        job = get(job_id)
        if job.audio is None:
            raise HTTPException(409, "Not ready yet")
        return Response(job.audio, media_type="audio/wav")

    @app.post("/jobs/{job_id}/redo")
    def redo(job_id: str, body: RedoIn):
        job = get(job_id)
        if not 0 <= body.unit < job.total:
            raise HTTPException(400, "No such line")
        if job.status == "running":
            raise HTTPException(409, "Still working")
        seed = body.seed if body.seed is not None else random.randint(1, 10 ** 6)
        job.status = "queued"
        server.submit("redo", job, (body.unit, seed))
        return {"ok": True, "seed": seed}

    return app


def load_engine():
    if os.environ.get("STICKMAN_FAKE_TTS") == "1":
        return FakeEngine()
    return ChatterboxEngine()


def start(port=8000):
    """Load the model and serve in a background thread (used by the Colab notebook)."""
    import uvicorn
    engine = load_engine()
    app = create_app(engine)
    cfg = uvicorn.Config(app, host="0.0.0.0", port=port, log_level="warning")
    srv = uvicorn.Server(cfg)
    threading.Thread(target=srv.run, daemon=True).start()
    for _ in range(100):
        if srv.started:
            break
        time.sleep(0.1)
    return engine


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(create_app(load_engine()), host="0.0.0.0", port=int(os.environ.get("PORT", "8000")))
