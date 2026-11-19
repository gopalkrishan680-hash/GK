import { STYLES, SIZES, applyStyle, enhancePrompt, randomPrompt } from "./styles.js";
import { engine, MODEL_FILES, modelStatus, modelsReady, downloadFile, deleteModels } from "./sd.js";
import { addImage, listImages, deleteImage, clearImages } from "./db.js";

const $ = (id) => document.getElementById(id);
const LS = {
  get(k, d) { try { const v = localStorage.getItem("gk." + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem("gk." + k, JSON.stringify(v)); } catch {} },
};
const state = {
  style: LS.get("style", 0), size: LS.get("size", 0), steps: LS.get("steps", 1),
  init: null, busy: false, stop: false, modal: null,
};

function toast(msg, ms = 2600) {
  const t = $("toast"); t.textContent = msg; t.classList.add("on");
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.remove("on"), ms);
}
const fmtGB = (b) => (b / 1024 ** 3).toFixed(2) + " GB";
const fmtMB = (b) => Math.round(b / 1024 ** 2) + " MB";

// ------------------------------------------------------------------ navigation & theme
document.querySelectorAll("nav button").forEach((b) => b.addEventListener("click", () => showView(b.dataset.view)));
function showView(v) {
  document.querySelectorAll("nav button").forEach((b) => b.classList.toggle("on", b.dataset.view === v));
  document.querySelectorAll(".view").forEach((s) => s.classList.toggle("on", s.id === "v-" + v));
  if (v === "history") renderHistory();
  if (v === "settings") renderFiles();
  window.scrollTo(0, 0);
}
function setTheme(t) {
  if (t === "auto") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.dataset.theme = t;
  document.querySelectorAll("#theme button").forEach((b) => b.classList.toggle("on", b.dataset.theme === t));
  LS.set("theme", t);
}
document.querySelectorAll("#theme button").forEach((b) => b.addEventListener("click", () => setTheme(b.dataset.theme)));
setTheme(LS.get("theme", "auto"));

// ------------------------------------------------------------------ pickers
function pills(container, items, current, onPick) {
  container.innerHTML = "";
  items.forEach((label, i) => {
    const b = document.createElement("button");
    b.className = "pill" + (i === current ? " on" : "");
    b.textContent = label;
    b.onclick = () => {
      container.querySelectorAll(".pill").forEach((x) => x.classList.remove("on"));
      b.classList.add("on"); onPick(i);
    };
    container.appendChild(b);
  });
}
pills($("styles"), STYLES.map((s) => s[0]), state.style, (i) => { state.style = i; LS.set("style", i); });
pills($("sizes"), SIZES.map((s) => `${s[0]}`), state.size, (i) => { state.size = i; LS.set("size", i); });
function setSteps(n) {
  state.steps = n; LS.set("steps", n);
  document.querySelectorAll("#quality button").forEach((b) => b.classList.toggle("on", +b.dataset.steps === n));
}
document.querySelectorAll("#quality button").forEach((b) => b.addEventListener("click", () => setSteps(+b.dataset.steps)));
setSteps(state.steps);

$("prompt").value = LS.get("prompt", "");
$("prompt").addEventListener("input", () => LS.set("prompt", $("prompt").value));
$("enhanceBtn").onclick = () => { $("prompt").value = enhancePrompt($("prompt").value); LS.set("prompt", $("prompt").value); };
$("surpriseBtn").onclick = () => { $("prompt").value = randomPrompt(); LS.set("prompt", $("prompt").value); };
$("clearBtn").onclick = () => { $("prompt").value = ""; LS.set("prompt", ""); $("prompt").focus(); };
$("seedBtn").onclick = () => { $("seed").value = Math.floor(Math.random() * 2 ** 31); };
$("strength").oninput = () => { $("strengthVal").textContent = $("strength").value + "%"; };

// ------------------------------------------------------------------ init photo (img2img)
async function setInitFromBlob(blob) {
  const img = await createImageBitmap(blob);
  state.init = img;
  $("initThumb").src = URL.createObjectURL(blob);
  $("initThumb").style.display = "block";
  $("initClear").classList.remove("hidden");
  document.querySelector("details").open = true;
}
$("initFile").onchange = async (e) => { const f = e.target.files[0]; if (f) await setInitFromBlob(f); };
$("initClear").onclick = () => {
  state.init = null; $("initFile").value = ""; $("initThumb").style.display = "none"; $("initClear").classList.add("hidden");
};
function sizeLike(w, h) { // keep aspect, ~512×512 area, multiples of 64
  const area = 512 * 512, r = w / h;
  let W = Math.round(Math.sqrt(area * r) / 64) * 64, H = Math.round(Math.sqrt(area / r) / 64) * 64;
  return [Math.min(768, Math.max(256, W)), Math.min(768, Math.max(256, H))];
}
function bitmapToImageData(bmp, W, H) {
  const c = new OffscreenCanvas(W, H), ctx = c.getContext("2d");
  // center-crop to target aspect
  const r = W / H, br = bmp.width / bmp.height;
  let sw = bmp.width, sh = bmp.height, sx = 0, sy = 0;
  if (br > r) { sw = bmp.height * r; sx = (bmp.width - sw) / 2; } else { sh = bmp.width / r; sy = (bmp.height - sh) / 2; }
  ctx.drawImage(bmp, sx, sy, sw, sh, 0, 0, W, H);
  return ctx.getImageData(0, 0, W, H);
}

// ------------------------------------------------------------------ model download
let downloading = false;
async function downloadAll() {
  if (downloading) return;
  downloading = true;
  const prog = [$("setupProg"), $("files")];
  try { await navigator.storage?.persist?.(); } catch {}
  let lock = null;
  try { lock = await navigator.wakeLock?.request("screen"); } catch {}
  const total = MODEL_FILES.reduce((a, f) => a + f.size, 0);
  const done = {};
  try {
    for (const f of MODEL_FILES) {
      await downloadFile(f, (loaded) => {
        done[f.key] = loaded;
        const sum = Object.values(done).reduce((a, b) => a + b, 0);
        const pct = Math.floor((sum / total) * 100);
        $("setupProg").innerHTML = `<div class="bar"><i style="width:${pct}%"></i></div>
          ${pct}% · ${fmtGB(sum)} of ${fmtGB(total)} · ${f.label}`;
        const el = document.querySelector(`[data-file="${f.key}"]`);
        if (el) el.querySelector("i").style.width = Math.floor((loaded / f.size) * 100) + "%";
      });
    }
    toast("✅ Model ready. The app now works offline!");
  } catch (e) {
    console.error(e);
    $("setupProg").textContent = "⚠️ Download stopped (" + (navigator.onLine ? e.message : "no internet") +
      "). Tap Download again — it continues from where it stopped.";
    toast("Download stopped. Tap again to resume.");
  } finally {
    downloading = false;
    try { await lock?.release(); } catch {}
    refreshReady();
    if ($("v-settings").classList.contains("on")) renderFiles();
    void prog;
  }
}
$("setupBtn").onclick = downloadAll;
$("dlBtn").onclick = async () => { await downloadAll(); renderFiles(); };
$("delBtn").onclick = async () => {
  if (!confirm("Delete the downloaded AI model (2.5 GB)? You will need internet to download it again.")) return;
  await deleteModels(); toast("Model deleted"); renderFiles(); refreshReady();
};

async function renderFiles() {
  const st = await modelStatus();
  $("files").innerHTML = MODEL_FILES.map((f) => {
    const s = st[f.key], pct = Math.floor((s.bytes / f.size) * 100);
    return `<div class="f" data-file="${f.key}"><b>${s.done ? "✅" : "⬇️"} ${f.label}</b>
      <span class="muted"> · ${fmtMB(f.size)}</span><div class="bar"><i style="width:${s.done ? 100 : pct}%"></i></div></div>`;
  }).join("");
  try {
    const e = await navigator.storage.estimate();
    const persisted = await navigator.storage.persisted?.();
    $("storage").textContent = `Storage used by this app: ${fmtGB(e.usage || 0)} (free for apps: ${fmtGB((e.quota || 0) - (e.usage || 0))})`
      + (persisted ? " · protected from auto-cleanup" : "");
  } catch {}
  $("engineInfo").textContent = "Engine: " + (await engine.info());
}

async function refreshReady() {
  const cap = await engine.capability();
  $("capCard").classList.toggle("hidden", cap.ok);
  $("capReason").textContent = cap.reason || "";
  const ready = (await modelsReady()) && cap.ok;
  $("setupCard").classList.toggle("hidden", await modelsReady());
  $("genBtn").disabled = !ready;
  if (!cap.ok) $("status").textContent = "Image generation is not supported on this browser (see above).";
  else if (!ready) $("status").textContent = "Download the model once to start (see above).";
  else if ($("status").textContent.startsWith("Download the model")) $("status").textContent = "Write a prompt and tap Generate.";
  return ready;
}

// ------------------------------------------------------------------ settings
$("lowMem").checked = LS.get("lowMem", engine.lowMemory);
engine.lowMemory = $("lowMem").checked;
$("lowMem").onchange = () => { engine.lowMemory = $("lowMem").checked; LS.set("lowMem", engine.lowMemory); };

// ------------------------------------------------------------------ generation
function setBusy(b) {
  state.busy = b;
  $("genBtn").classList.toggle("hidden", b);
  $("stopBtn").classList.toggle("hidden", !b);
}
$("stopBtn").onclick = () => { state.stop = true; $("status").textContent = "Stopping after this step…"; };

function imageDataToBlob(img) {
  const c = new OffscreenCanvas(img.width, img.height);
  c.getContext("2d").putImageData(img, 0, 0);
  return c.convertToBlob({ type: "image/png" });
}

$("genBtn").onclick = async () => {
  if (state.busy) return;
  const prompt = $("prompt").value.trim();
  if (!prompt && !state.init) { toast("Write a prompt first"); $("prompt").focus(); return; }
  if (!(await refreshReady())) { toast((await engine.capability()).ok ? "Download the model first" : "Not supported on this browser"); return; }
  const full = applyStyle(state.style, prompt);
  let [, W, H] = SIZES[state.size];
  const count = +$("count").value;
  const baseSeed = $("seed").value === "" ? Math.floor(Math.random() * 2 ** 31) : (+$("seed").value >>> 0);
  const steps = state.steps;
  const strength = +$("strength").value / 100;
  setBusy(true); state.stop = false;
  let lock = null; try { lock = await navigator.wakeLock?.request("screen"); } catch {}
  const log = (m) => { $("status").textContent = m; };
  const bar = (p) => { $("genBar").style.width = Math.round(p * 100) + "%"; };
  const t0 = performance.now();
  try {
    bar(0.02);
    let initLatent = null;
    if (state.init) {
      [W, H] = sizeLike(state.init.width, state.init.height);
      const data = bitmapToImageData(state.init, W, H);
      log("Reading your photo…");
      initLatent = await engine.encodeImage(data, W, H, log);
    }
    const emb = await engine.encodePrompt(full, log);
    for (let k = 0; k < count && !state.stop; k++) {
      const seed = (baseSeed + k) >>> 0;
      const ti = performance.now();
      const img = await engine.generate({
        emb, width: W, height: H, steps, seed, initLatent, strength,
        stop: () => state.stop,
        onStep: (i, total) => {
          log(`🎨 Image ${k + 1}/${count} · step ${i}/${total}`);
          bar((k + (i - 0.5) / total) / count);
        },
      }, log);
      if (!img) break;
      const blob = await imageDataToBlob(img);
      const rec = { blob, prompt, style: STYLES[state.style][0], styleIdx: state.style, fullPrompt: full, width: W, height: H,
        steps, seed, mode: initLatent ? "img2img" : "txt2img", strength: initLatent ? strength : null, time: Date.now(),
        seconds: +((performance.now() - ti) / 1000).toFixed(1) };
      rec.id = await addImage(rec);
      showResult(rec);
      bar((k + 1) / count);
    }
    const secs = ((performance.now() - t0) / 1000).toFixed(1);
    log(state.stop ? "⏹ Stopped." : `✅ Done in ${secs}s · seed ${baseSeed} · ${W}×${H} · ${await engine.info()}`);
  } catch (e) {
    console.error(e);
    const msg = String(e?.message || e);
    log("⚠️ " + (/memory|alloc|OOM|lost/i.test(msg)
      ? "Not enough memory. Turn on Low-memory mode in Settings, use Square size, or close other apps."
      : msg));
    await engine.releaseAll().catch(() => {});
  } finally {
    setBusy(false); state.stop = false;
    try { await lock?.release(); } catch {}
    setTimeout(() => bar(0), 800);
  }
};

// ------------------------------------------------------------------ results, history, viewer
function thumb(rec, container, prepend = true) {
  const im = document.createElement("img");
  im.src = URL.createObjectURL(rec.blob);
  im.alt = rec.prompt || "generated image";
  im.loading = "lazy";
  im.onclick = () => openModal(rec);
  if (prepend) container.prepend(im); else container.appendChild(im);
}
function showResult(rec) { thumb(rec, $("results")); }

async function renderHistory() {
  const items = await listImages();
  const g = $("history"); g.innerHTML = "";
  items.forEach((r) => thumb(r, g, false));
  $("histEmpty").classList.toggle("hidden", items.length > 0);
}
$("clearHist").onclick = async () => {
  if (!confirm("Delete all images from history?")) return;
  await clearImages(); renderHistory(); $("results").innerHTML = "";
};

function openModal(rec) {
  state.modal = rec;
  $("mImg").src = URL.createObjectURL(rec.blob);
  const when = new Date(rec.time).toLocaleString();
  $("mMeta").innerHTML = `<b>${escapeHtml(rec.prompt || "(no prompt)")}</b><br>
    ${escapeHtml(rec.style)} · ${rec.width}×${rec.height} · ${rec.steps} step(s) · seed ${rec.seed}
    ${rec.mode === "img2img" ? " · from photo" : ""} · ${rec.seconds}s<br>${when}`;
  $("modal").classList.add("on");
}
const escapeHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
$("mClose").onclick = () => $("modal").classList.remove("on");
$("modal").addEventListener("click", (e) => { if (e.target.id === "modal") $("modal").classList.remove("on"); });
const fileName = (r) => `gk-${r.seed}-${new Date(r.time).toISOString().slice(0, 19).replace(/[:T]/g, "-")}.png`;
$("mSave").onclick = () => {
  const r = state.modal, a = document.createElement("a");
  a.href = URL.createObjectURL(r.blob); a.download = fileName(r); a.click();
};
$("mShare").onclick = async () => {
  const r = state.modal, file = new File([r.blob], fileName(r), { type: "image/png" });
  if (navigator.canShare?.({ files: [file] })) {
    try { await navigator.share({ files: [file], title: "GK Image Studio" }); } catch {}
  } else toast("Sharing is not supported here — use Save");
};
$("mUse").onclick = async () => {
  await setInitFromBlob(state.modal.blob);
  $("modal").classList.remove("on"); showView("create"); toast("Photo set. Change the prompt and tap Generate.");
};
$("mReuse").onclick = () => {
  const r = state.modal;
  $("prompt").value = r.prompt || ""; LS.set("prompt", $("prompt").value);
  $("seed").value = r.seed; setSteps(r.steps);
  state.style = r.styleIdx ?? 0; pills($("styles"), STYLES.map((s) => s[0]), state.style, (i) => { state.style = i; LS.set("style", i); });
  const si = SIZES.findIndex((s) => s[1] === r.width && s[2] === r.height);
  if (si >= 0) { state.size = si; pills($("sizes"), SIZES.map((s) => s[0]), si, (i) => { state.size = i; LS.set("size", i); }); }
  $("modal").classList.remove("on"); showView("create"); toast("Settings restored");
};
$("mDel").onclick = async () => {
  await deleteImage(state.modal.id);
  $("modal").classList.remove("on"); renderHistory(); toast("Deleted");
};

// ------------------------------------------------------------------ offline / install
function netChip() {
  const c = $("netChip");
  c.textContent = navigator.onLine ? "Online" : "Offline ✓";
  c.className = "chip " + (navigator.onLine ? "" : "ok");
}
addEventListener("online", netChip); addEventListener("offline", netChip); netChip();

let deferredInstall = null;
addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); deferredInstall = e; $("installBtn").classList.remove("hidden"); });
$("installBtn").onclick = async () => {
  if (!deferredInstall) return;
  deferredInstall.prompt(); await deferredInstall.userChoice; deferredInstall = null; $("installBtn").classList.add("hidden");
};
if ("serviceWorker" in navigator && location.protocol !== "file:") {
  navigator.serviceWorker.register("sw.js").catch((e) => console.warn("SW failed", e));
}

refreshReady();
// expose for automated tests
window.__gk = { engine, state };
