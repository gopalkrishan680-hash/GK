"""GK AI Image Studio — bina internet ke AI image generator.

Chalane ke liye: Windows par run.bat, Mac/Linux par ./run.sh
Ya: python app.py [--listen] [--port 7860] [--inbrowser]
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time

from core import paths

paths.setup_env(offline=True)

import gradio as gr  # noqa: E402
from PIL import Image  # noqa: E402

from core import utils  # noqa: E402
from core.engine import ENGINE, SAMPLERS, GenRequest  # noqa: E402
from core.hardware import HW  # noqa: E402
from core.models import (BASE_RES, CATALOG, available_models, hf_cached, list_loras,  # noqa: E402
                         turbo_lora_available)
from core.styles import (ASPECT_NAMES, STYLE_NAMES, apply_style, enhance_prompt,  # noqa: E402
                         random_prompt, size_for, size_like)

APP_TITLE = "GK AI Image Studio"
MODELS: dict = {}


def refresh_models() -> list[str]:
    MODELS.clear()
    for m in available_models():
        MODELS[m.name] = m
    return list(MODELS.keys())


def base_res(info) -> int:
    return 512 if info.key == "sdxlturbo" else BASE_RES[info.family]


# ============================================================== handlers
def on_model_change(name, turbo):
    info = MODELS.get(name)
    if not info:
        return gr.update(), gr.update(), gr.update(), ""
    can_turbo = info.family in ("sd15", "sdxl") and not info.turbo_native and turbo_lora_available(info.family)
    t = bool(turbo and can_turbo)
    steps, cfg = (6, 1.5) if t else (info.steps, info.cfg)
    note = f"**{info.name}** — {info.desc or info.family.upper()}"
    if info.turbo_native:
        note += "  \n⚡ Ye model khud hi fast hai (Turbo ki zaroorat nahi)."
    elif not can_turbo:
        note += "  \n_(Turbo LoRA download nahi hai — Models tab se 'turbo' download karein)_"
    return (gr.update(value=t, interactive=can_turbo), gr.update(value=steps), gr.update(value=cfg), note)


def on_turbo_change(turbo, name):
    info = MODELS.get(name)
    if not info or info.turbo_native:
        return gr.update(), gr.update()
    return (gr.update(value=6), gr.update(value=1.5)) if turbo else (gr.update(value=info.steps),
                                                                        gr.update(value=info.cfg))


def generate(prompt, negative, style, use_base_neg, aspect, width, height, count, model_name, turbo,
             steps, cfg, sampler, seed, loras, lora_w, mode, init_img, strength, editor,
             progress=gr.Progress()):
    info = MODELS.get(model_name)
    if not info:
        raise gr.Error("Koi model select nahi hai. 📦 Models tab se model download karein.")
    # Band accordion ke andar wale fields kabhi None aa jaate hain -> default values
    prompt, negative = prompt or "", negative or ""
    style = style or STYLE_NAMES[0]
    use_base_neg = True if use_base_neg is None else use_base_neg
    aspect = aspect or ASPECT_NAMES[0]
    width, height = width or 768, height or 768
    count = count or 1
    steps = steps or info.steps
    cfg = info.cfg if cfg is None else cfg
    sampler = sampler or "Default"
    seed = -1 if seed is None else seed
    lora_w = 0.8 if lora_w is None else lora_w
    mode = mode or "Text → Image"
    strength = strength or 0.6
    if not prompt.strip() and mode == "Text → Image":
        raise gr.Error("Prompt khaali hai. Kuch likhiye, jaise: 'a cute cat astronaut on the moon'")

    req_mode = {"Text → Image": "txt2img", "Image → Image": "img2img", "Inpaint (hissa badlo)": "inpaint"}[mode]
    base = base_res(info)
    init = mask = None
    if req_mode == "img2img":
        if init_img is None:
            raise gr.Error("Image → Image ke liye pehle ek photo upload karein.")
        init = init_img
        w, h = size_like(init.width, init.height, base)
    elif req_mode == "inpaint":
        init, mask = utils.mask_from_editor(editor)
        if init is None:
            raise gr.Error("Inpaint ke liye photo upload karein.")
        if mask is None:
            raise gr.Error("Brush se us hisse ko paint karein jo badalna hai.")
        w, h = size_like(init.width, init.height, base)
    elif aspect.startswith("✏️"):
        w, h = int(width) // 64 * 64, int(height) // 64 * 64
    else:
        w, h = size_for(aspect, base)

    pos, neg = apply_style(style, prompt, negative, use_base_neg)
    log_lines: list[str] = []

    def log(msg):
        log_lines.append(msg)
        progress(0, desc=msg)

    if ENGINE.info is None or ENGINE.info.key != info.key:
        progress(0, desc=f"⏳ {info.name} load ho raha hai (pehli baar thoda time lagta hai)...")
    with ENGINE.lock:
        try:
            ENGINE.load(info, log=log)
        except Exception as e:
            raise gr.Error(f"Model load nahi hua: {e}")

        lora_list = [(f, float(lora_w)) for f in (loras or [])]
        req = GenRequest(prompt=pos, negative=neg, mode=req_mode, width=w, height=h, steps=int(steps),
                         cfg=float(cfg), seed=int(seed), count=int(count), sampler=sampler, turbo=bool(turbo),
                         loras=lora_list, init_image=init, mask=mask, strength=float(strength))
        n = int(count)

        def on_step(i, step, total):
            progress((i * total + step) / max(1, n * total), desc=f"🎨 Image {i + 1}/{n} · step {step}/{total}")

        out_paths, seeds = [], []
        t0 = time.time()
        try:
            for img, s in ENGINE.generate(req, on_step=on_step, log=log):
                meta = dict(prompt=prompt, negative=negative, style=style, full_prompt=pos, full_negative=neg,
                            steps=int(steps), cfg=float(cfg), sampler=sampler, seed=s, width=w, height=h,
                            model=info.name, model_key=info.key, turbo=bool(turbo), mode=req_mode,
                            loras=lora_list, strength=float(strength) if req_mode != "txt2img" else None)
                out_paths.append(utils.save_image(img, meta))
                seeds.append(s)
                status = (f"✅ {len(out_paths)}/{n} ban gayi · {time.time() - t0:.1f}s · {w}×{h} · "
                          f"seed: {', '.join(map(str, seeds))}")
                yield out_paths, status, out_paths[-1]
        except Exception as e:
            if HW.device == "cuda" and "out of memory" in str(e).lower():
                raise gr.Error("GPU memory kam pad gayi. Chhota size, kam images ya SD1.5 model try karein.")
            raise gr.Error(f"Error: {e}")
    if ENGINE.cancel:
        yield out_paths, f"⏹️ Rok diya gaya. {len(out_paths)} image save hui.", (out_paths[-1] if out_paths else None)


def stop():
    ENGINE.cancel = True
    return "⏹️ Rok rahe hain..."


def on_gallery_select(evt: gr.SelectData, files):
    try:
        v = evt.value
        if isinstance(v, dict):
            v = (v.get("image") or {}).get("path") or v.get("path")
        return v or files[evt.index]
    except Exception:
        return None


def last_seed_from(path):
    m = utils.read_meta(path) if path else {}
    return m.get("seed", -1)


def to_img2img(path):
    if not path:
        raise gr.Error("Pehle gallery mein ek image par click karein.")
    return gr.update(value="Image → Image"), Image.open(path).convert("RGB"), gr.update(open=True)


def to_inpaint(path):
    if not path:
        raise gr.Error("Pehle gallery mein ek image par click karein.")
    img = Image.open(path).convert("RGB")
    return (gr.update(value="Inpaint (hissa badlo)"), {"background": img, "layers": [], "composite": img},
            gr.update(open=True))


def to_extras(path):
    if not path:
        raise gr.Error("Pehle ek image select karein.")
    return Image.open(path).convert("RGB"), gr.update(selected="extras")


def on_mode(mode):
    return (gr.update(visible=mode == "Image → Image"), gr.update(visible=mode == "Inpaint (hissa badlo)"),
            gr.update(visible=mode != "Text → Image"))


# ---------------- history
def load_history():
    return utils.history()


def history_select(evt: gr.SelectData, files):
    path = on_gallery_select(evt, files)
    meta = utils.read_meta(path) if path else {}
    return path, json.dumps(meta, indent=2, ensure_ascii=False) if meta else "Is image mein settings nahi mili."


def apply_history(path):
    m = utils.read_meta(path) if path else {}
    if "prompt" not in m:
        raise gr.Error("Is image mein settings nahi hain.")
    upd = [m.get("prompt", ""), m.get("negative", ""), gr.update(value=m.get("style")) if m.get("style") in STYLE_NAMES
           else gr.update(), m.get("steps", 25), m.get("cfg", 7.0), m.get("seed", -1),
           gr.update(value="✏️ Custom (khud size do)"), m.get("width", 512), m.get("height", 512)]
    model = next((n for n, i in MODELS.items() if i.key == m.get("model_key")), None)
    upd.append(gr.update(value=model) if model else gr.update())
    upd.append(gr.update(value=bool(m.get("turbo"))))
    upd.append(gr.update(selected="create"))
    return upd


# ---------------- extras
def ex_upscale(img):
    if img is None:
        raise gr.Error("Image upload karein.")
    out = utils.simple_upscale(img, 2.0)
    return out, utils.save_image(out, {"prompt": "upscale 2x", "seed": 0, "mode": "upscale"})


def ex_ai_upscale(img, prompt, strength, model_name, turbo, progress=gr.Progress()):
    if img is None:
        raise gr.Error("Image upload karein.")
    info = MODELS.get(model_name)
    if not info:
        raise gr.Error("Model select karein (Banao tab mein).")
    with ENGINE.lock:
        ENGINE.load(info, log=lambda m: progress(0, desc=m))
        out = ENGINE.ai_upscale(img, prompt, strength=float(strength), turbo=bool(turbo),
                                steps=8 if turbo else 25,
                                on_step=lambda i, s, t: progress(s / t, desc=f"✨ Detail add: step {s}/{t}"))
    return out, utils.save_image(out, {"prompt": f"AI upscale: {prompt}", "seed": 0, "mode": "ai-upscale",
                                       "model": info.name})


def ex_rembg(img):
    if img is None:
        raise gr.Error("Image upload karein.")
    try:
        out = utils.remove_background(img)
    except Exception as e:
        raise gr.Error(str(e))
    return out, utils.save_image(out, {"prompt": "background removed", "seed": 0, "mode": "rembg"})


def png_info(path):
    if not path:
        return ""
    m = utils.read_meta(path)
    return json.dumps(m, indent=2, ensure_ascii=False) if m else "Is image mein koi AI settings nahi mili."


# ---------------- models tab
def models_table():
    rows = ["| Model | Type | Size | Status | Kiske liye |", "|---|---|---|---|---|"]
    for m in CATALOG:
        st = "✅ Ready" if hf_cached(m.source) else "⬇️ Download karein"
        rows.append(f"| {m.name} | {m.family.upper()} | ~{m.size_gb:.0f} GB | {st} | {m.desc} |")
    t1 = "✅" if turbo_lora_available("sd15") else "⬇️"
    t2 = "✅" if turbo_lora_available("sdxl") else "⬇️"
    extra = f"\n\n**Turbo LoRA:** SD1.5 {t1} · SDXL {t2}"
    local = [n for n, i in MODELS.items() if i.kind in ("single", "folder") or i.key.startswith("hf:")]
    if local:
        extra += "\n\n**Aapke apne models:** " + ", ".join(local)
    return "\n".join(rows) + extra


def run_download(keys, repo):
    args = [sys.executable, "-u", str(paths.ROOT / "download_models.py")]
    if repo and repo.strip():
        args += ["--repo", repo.strip()]
    elif keys:
        args += [k.split(" ")[0] for k in keys]
    else:
        raise gr.Error("Kuch select karein ya repo naam likhein.")
    log = "⏳ Download shuru... (internet chahiye)\n"
    yield log
    proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            encoding="utf-8", errors="replace", cwd=str(paths.ROOT))
    for line in proc.stdout:
        log += line
        yield log[-6000:]
    proc.wait()
    yield log[-6000:] + "\n🔄 Ab '🔄 Model list refresh' dabaein."


def do_refresh():
    names = refresh_models()
    return (gr.update(choices=names, value=names[0] if names else None), models_table(),
            gr.update(choices=list_loras()))


# ============================================================== UI
HELP_MD = """
## 🙏 Kaise use karein

1. **Prompt likhein (English mein)** — jaise: `a cute baby elephant playing holi, colorful, happy`
2. **Style chunein** — Real Photo, Anime, 3D Cartoon, Thumbnail… style apne aap prompt ko behtar bana deta hai.
3. **Size chunein** — Reels/Shorts ke liye 9:16, YouTube ke liye 16:9.
4. **🎨 Banao** dabaein. Image `outputs/` folder mein apne aap save ho jaati hai (settings ke saath).

### ⚡ Speed tips
- **CPU (bina GPU) par:** *DreamShaper 8* + **⚡ Turbo ON** rakhein → ek image ~10-40 second.
- **NVIDIA GPU par:** *DreamShaper XL Turbo* ya *SDXL Turbo* → kuch hi second.
- **Seed** same rakhenge to wahi image dobara banegi. `-1` = har baar nayi.

### ✨ Features
- **✨ Prompt sudharo** — chhote prompt mein lighting, camera aur quality words jod deta hai.
- **🎲 Surprise me** — random creative idea.
- **Image → Image** — apni photo ko naye style mein badlo (strength kam = original jaisa, zyada = zyada badlav).
- **Inpaint** — photo ke kisi hisse par brush chalao aur prompt mein likho wahan kya chahiye.
- **🛠️ Extras** — 2x Upscale, AI Upscale (detail), Background hatao, PNG Info (purani image ki settings).
- **🖼️ History** — sab purani images; *Ye settings wapas lao* se same settings dobara.
- **LoRA** — `models/lora/` mein `.safetensors` LoRA daalo (character/style ke liye).
- **Apne models** — Civitai etc. se `.safetensors` (SD1.5/SDXL) `models/checkpoints/` mein daalo → *🔄 refresh*.
- **Mobile se use karna?** `run.bat --listen` chalaein, phir phone (same WiFi) par `http://<PC ka IP>:7860` kholein.

### 📝 Achhe prompt ka formula
`[kya] + [kahan] + [kaisa mood/light] + [style]`
Example: `an old fisherman, sitting on a boat at sunrise, golden light, mist, cinematic`

**Negative prompt** mein wo likhein jo NAHI chahiye: `blurry, extra fingers, text`.
"""

CSS = """
#gen-btn {min-height: 58px; font-size: 1.15rem; font-weight: 700;}
#title {text-align:center; margin-bottom:0}
#hw {text-align:center; opacity:.85}
footer {display:none !important}
"""


def build_ui():
    names = refresh_models()
    default_model = names[0] if names else None
    cpu = HW.device == "cpu"

    with gr.Blocks(title=APP_TITLE) as demo:
        gr.Markdown(f"# 🎨 {APP_TITLE}\n100% offline · aapke computer par · koi limit nahi", elem_id="title")
        gr.Markdown(HW.label, elem_id="hw")
        if not names:
            gr.Markdown("### ⚠️ Abhi koi model download nahi hai. **📦 Models** tab mein jaake 'dreamshaper8' "
                        "aur 'turbo' download karein (sirf ek baar internet chahiye), phir 🔄 dabaein.")
        selected = gr.State(None)

        with gr.Tabs() as tabs:
            # ------------------------------------------------ CREATE
            with gr.Tab("🎨 Banao", id="create"):
                with gr.Row():
                    with gr.Column(scale=5):
                        prompt = gr.Textbox(label="Prompt (aapko kya chahiye — English mein)", lines=3,
                                            placeholder="a royal bengal tiger walking in a misty jungle at sunrise")
                        with gr.Row():
                            enhance_btn = gr.Button("✨ Prompt sudharo", size="sm")
                            surprise_btn = gr.Button("🎲 Surprise me", size="sm")
                            clear_btn = gr.Button("🧹 Saaf karo", size="sm")
                        with gr.Accordion("🚫 Negative prompt (kya NAHI chahiye)", open=False):
                            negative = gr.Textbox(label="Negative", lines=2, placeholder="text, watermark, blurry")
                            use_base_neg = gr.Checkbox(True, label="Auto quality negative jodo (recommended)")
                        style = gr.Dropdown(STYLE_NAMES, value=STYLE_NAMES[0], label="🎭 Style")
                        aspect = gr.Radio(ASPECT_NAMES, value=ASPECT_NAMES[0], label="📐 Size / Shape")
                        with gr.Row():
                            model = gr.Dropdown(names, value=default_model, label="🧠 Model", scale=3)
                            refresh_btn = gr.Button("🔄", scale=0, min_width=50)
                        model_note = gr.Markdown()
                        with gr.Row():
                            turbo = gr.Checkbox(cpu, label="⚡ Turbo mode (4-8 steps, bahut fast)")
                            count = gr.Slider(1, 8, value=1, step=1, label="Kitni images")
                        gen_btn = gr.Button("🎨 Banao", variant="primary", elem_id="gen-btn")
                        stop_btn = gr.Button("⏹️ Roko", variant="stop", size="sm")

                        with gr.Accordion("⚙️ Advanced settings", open=False):
                            with gr.Row():
                                steps = gr.Slider(1, 80, value=25, step=1, label="Steps (zyada = detail, slow)")
                                cfg = gr.Slider(0, 20, value=7, step=0.5, label="CFG (prompt kitna follow kare)")
                            with gr.Row():
                                sampler = gr.Dropdown(SAMPLERS, value="Default", label="Sampler")
                                seed = gr.Number(-1, label="Seed (-1 = random)", precision=0)
                                rnd_seed = gr.Button("🎲", scale=0, min_width=50)
                                reuse_seed = gr.Button("♻️ Last seed", scale=0, min_width=110)
                            with gr.Row():
                                width = gr.Slider(256, 2048, value=768, step=64, label="Width (Custom size)")
                                height = gr.Slider(256, 2048, value=768, step=64, label="Height (Custom size)")
                            with gr.Row():
                                loras = gr.Dropdown(list_loras(), multiselect=True, label="🎭 LoRA (models/lora)")
                                lora_w = gr.Slider(0, 1.5, value=0.8, step=0.05, label="LoRA strength")

                        with gr.Accordion("🖌️ Photo se banao / Edit (Image→Image, Inpaint)", open=False) as edit_acc:
                            mode = gr.Radio(["Text → Image", "Image → Image", "Inpaint (hissa badlo)"],
                                            value="Text → Image", label="Mode")
                            init_img = gr.Image(type="pil", label="Apni photo", visible=False, height=320)
                            editor = gr.ImageEditor(type="pil", label="Photo upload karke badalne wale hisse par brush chalao",
                                                    visible=False, height=420,
                                                    brush=gr.Brush(colors=["#ffffff"], color_mode="fixed",
                                                                   default_size=40))
                            strength = gr.Slider(0.05, 1.0, value=0.6, step=0.05, visible=False,
                                                 label="Strength (kam = original jaisa, zyada = zyada badlav)")

                    with gr.Column(scale=6):
                        gallery = gr.Gallery(label="Result", columns=2, height=620, format="png",
                                             object_fit="contain", buttons=["download", "fullscreen"],
                                             interactive=False)
                        status = gr.Markdown("Prompt likhiye aur **🎨 Banao** dabaiye.")
                        with gr.Row():
                            b_i2i = gr.Button("🔁 Isi ko badlo (img2img)", size="sm")
                            b_inp = gr.Button("🖌️ Inpaint", size="sm")
                            b_up = gr.Button("🔍 Upscale / Extras", size="sm")
                            b_folder = gr.Button("📂 Folder kholo", size="sm")

            # ------------------------------------------------ HISTORY
            with gr.Tab("🖼️ History", id="history"):
                h_refresh = gr.Button("🔄 Refresh")
                with gr.Row():
                    h_gallery = gr.Gallery(columns=5, height=600, format="png", object_fit="cover", scale=3,
                                           interactive=False)
                    with gr.Column(scale=2):
                        h_meta = gr.Code(label="Settings", language="json")
                        h_apply = gr.Button("♻️ Ye settings wapas lao", variant="primary")
                h_sel = gr.State(None)
                h_files = gr.State([])

            # ------------------------------------------------ EXTRAS
            with gr.Tab("🛠️ Extras", id="extras"):
                with gr.Row():
                    with gr.Column():
                        ex_in = gr.Image(type="pil", label="Image")
                        with gr.Row():
                            ex_up_btn = gr.Button("🔍 2x Upscale (fast)")
                            ex_bg_btn = gr.Button("✂️ Background hatao")
                        with gr.Accordion("✨ AI Upscale 2x (detail badhao — model use hota hai)", open=True):
                            ex_prompt = gr.Textbox(label="Image kis cheez ki hai (optional)",
                                                   placeholder="portrait of a woman, detailed skin")
                            ex_strength = gr.Slider(0.1, 0.6, value=0.3, step=0.05,
                                                    label="Kitna naya detail (0.25-0.35 best)")
                            ex_ai_btn = gr.Button("✨ AI Upscale", variant="primary")
                    with gr.Column():
                        ex_out = gr.Image(label="Result", type="pil", format="png")
                        ex_file = gr.Textbox(label="Saved", interactive=False)
                gr.Markdown("### 🔎 PNG Info — purani image ki settings dekho")
                with gr.Row():
                    pi_in = gr.Image(type="filepath", label="Image (is app se bani)")
                    pi_out = gr.Code(language="json", label="Settings")

            # ------------------------------------------------ MODELS
            with gr.Tab("📦 Models", id="models"):
                gr.Markdown("Models **sirf ek baar** download hote hain (internet chahiye). Uske baad sab offline chalta hai.")
                m_table = gr.Markdown(models_table())
                dl_keys = gr.CheckboxGroup([f"{m.key} — {m.name}" for m in CATALOG]
                                           + ["turbo — ⚡ Turbo LoRA", "configs — 🧩 .safetensors support",
                                              "rembg — ✂️ Background remover"], label="Download karne ke liye chunein")
                dl_repo = gr.Textbox(label="Ya koi bhi Hugging Face diffusers model (repo id)",
                                     placeholder="username/model-name")
                with gr.Row():
                    dl_btn = gr.Button("⬇️ Download", variant="primary")
                    m_refresh = gr.Button("🔄 Model list refresh")
                dl_log = gr.Textbox(label="Log", lines=12, max_lines=20, autoscroll=True)
                gr.Markdown(f"""
**Apne models kaise daalein (offline):**
- Civitai / kahin se bhi `.safetensors` checkpoint (SD1.5 ya SDXL) → `{paths.CHECKPOINTS}`
- LoRA files → `{paths.LORA}`
- Diffusers-format folder → `{paths.DIFFUSERS}`

Phir **🔄 Model list refresh** dabaein.""")

            with gr.Tab("❓ Help", id="help"):
                gr.Markdown(HELP_MD)

        # ------------------------------------------------ wiring
        gen_inputs = [prompt, negative, style, use_base_neg, aspect, width, height, count, model, turbo,
                      steps, cfg, sampler, seed, loras, lora_w, mode, init_img, strength, editor]
        gen_btn.click(generate, gen_inputs, [gallery, status, selected], concurrency_limit=1)
        prompt.submit(generate, gen_inputs, [gallery, status, selected], concurrency_limit=1)
        stop_btn.click(stop, None, status, queue=False)

        enhance_btn.click(enhance_prompt, prompt, prompt)
        surprise_btn.click(random_prompt, None, prompt)
        clear_btn.click(lambda: ("", ""), None, [prompt, negative])
        rnd_seed.click(lambda: -1, None, seed)
        reuse_seed.click(last_seed_from, selected, seed)
        model.change(on_model_change, [model, turbo], [turbo, steps, cfg, model_note])
        turbo.input(on_turbo_change, [turbo, model], [steps, cfg])
        mode.change(on_mode, mode, [init_img, editor, strength])
        refresh_btn.click(do_refresh, None, [model, m_table, loras])

        gallery.select(on_gallery_select, gallery, selected)
        b_i2i.click(to_img2img, selected, [mode, init_img, edit_acc])
        b_inp.click(to_inpaint, selected, [mode, editor, edit_acc])
        b_up.click(to_extras, selected, [ex_in, tabs])
        b_folder.click(lambda: utils.open_folder(paths.OUTPUTS), None, None)

        h_refresh.click(load_history, None, h_gallery).then(lambda: utils.history(), None, h_files)
        h_gallery.select(history_select, h_files, [h_sel, h_meta])
        h_apply.click(apply_history, h_sel, [prompt, negative, style, steps, cfg, seed, aspect, width, height,
                                             model, turbo, tabs])

        ex_up_btn.click(ex_upscale, ex_in, [ex_out, ex_file])
        ex_bg_btn.click(ex_rembg, ex_in, [ex_out, ex_file])
        ex_ai_btn.click(ex_ai_upscale, [ex_in, ex_prompt, ex_strength, model, turbo], [ex_out, ex_file],
                        concurrency_limit=1)
        pi_in.change(png_info, pi_in, pi_out)

        dl_btn.click(run_download, [dl_keys, dl_repo], dl_log)
        m_refresh.click(do_refresh, None, [model, m_table, loras])

        demo.load(on_model_change, [model, turbo], [turbo, steps, cfg, model_note])
        demo.load(lambda: (utils.history(), utils.history()), None, [h_gallery, h_files])
    return demo


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", action="store_true", help="Same WiFi ke phone/laptop se bhi kholo")
    ap.add_argument("--port", type=int, default=7860)
    ap.add_argument("--inbrowser", action="store_true")
    a = ap.parse_args()
    demo = build_ui()
    print(f"\n🎨 {APP_TITLE} chalu ho raha hai...  {HW.label}")
    print(f"👉 Browser mein kholein: http://127.0.0.1:{a.port}\n   (band karne ke liye ye window band karein)\n")
    demo.queue(default_concurrency_limit=2).launch(
        server_name="0.0.0.0" if a.listen else "127.0.0.1", server_port=a.port, inbrowser=a.inbrowser,
        allowed_paths=[str(paths.OUTPUTS)], theme=gr.themes.Soft(primary_hue="orange", secondary_hue="indigo",
                                                                 font=["system-ui", "Segoe UI", "sans-serif"],
                                                                 font_mono=["Consolas", "monospace"]),
        css=CSS, footer_links=[], show_error=True)


if __name__ == "__main__":
    main()
