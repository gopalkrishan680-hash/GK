// SD-Turbo in the browser with onnxruntime-web (WebGPU, falls back to WASM/CPU).
// Models are downloaded once from Hugging Face in resumable chunks and kept in Cache Storage,
// so everything works offline afterwards.
import * as ort from "../vendor/ort/ort.all.min.mjs";
import { ClipTokenizer } from "./tokenizer.js";
import { externalizeModel } from "./onnx-external.js";

ort.env.wasm.wasmPaths = new URL("../vendor/ort/", import.meta.url).href;
ort.env.wasm.numThreads = globalThis.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 2) : 1;
ort.env.logLevel = "error";

const params = new URLSearchParams(globalThis.location?.search || "");
export const MODEL_BASE = params.get("models") || "https://huggingface.co/schmuell/sd-turbo-ort-web/resolve/main/";
export const MODEL_FILES = [
  { key: "text_encoder", label: "Text encoder", path: "text_encoder/model.onnx", size: 681393168 },
  { key: "unet", label: "Image model (UNet)", path: "unet/model.onnx", size: 1733430199 },
  { key: "vae_decoder", label: "Image decoder (VAE)", path: "vae_decoder/model.onnx", size: 99094314 },
  { key: "vae_encoder", label: "Photo encoder (for Image → Image)", path: "vae_encoder/model.onnx", size: 68430468 },
];
const CACHE = "gk-models-v1";
const CHUNK = 32 * 1024 * 1024;
const LATENT_SCALE = 0.18215;

// ------------------------------------------------------------------ storage (resumable chunks)
const urlOf = (f) => MODEL_BASE + f.path;
// Cache Storage ignores the "#fragment" of a URL, so parts are told apart with a query parameter.
const partKey = (f, part) => urlOf(f) + (urlOf(f).includes("?") ? "&" : "?") + "gkpart=" + part;
const metaKey = (f) => partKey(f, "meta");
const chunkKey = (f, i) => partKey(f, "c" + i);

async function getMeta(cache, f) {
  const r = await cache.match(metaKey(f));
  return r ? r.json() : { chunks: 0, done: false };
}
const putMeta = (cache, f, m) =>
  cache.put(metaKey(f), new Response(JSON.stringify(m), { headers: { "content-type": "application/json" } }));

export async function modelStatus() {
  const cache = await caches.open(CACHE);
  const out = {};
  for (const f of MODEL_FILES) {
    const m = await getMeta(cache, f);
    out[f.key] = { ...m, bytes: m.done ? m.size : Math.min(m.chunks * CHUNK, f.size), total: f.size };
  }
  return out;
}

export async function modelsReady() {
  const s = await modelStatus();
  return ["text_encoder", "unet", "vae_decoder"].every((k) => s[k].done);
}

export async function downloadFile(f, onProgress, signal) {
  const cache = await caches.open(CACHE);
  let meta = await getMeta(cache, f);
  if (meta.done) { onProgress?.(f.size, f.size); return; }
  let idx = meta.chunks || 0;
  const offset = idx * CHUNK;
  let resp = null;
  if (offset) { // resume; some networks/CDNs refuse Range requests -> fall back to a full download and skip
    try {
      resp = await fetch(urlOf(f), { headers: { Range: `bytes=${offset}-` }, signal, cache: "no-store" });
      if (!resp.ok) resp = null;
    } catch (e) { if (e.name === "AbortError") throw e; resp = null; }
  }
  if (!resp) resp = await fetch(urlOf(f), { signal, cache: "no-store" });
  if (!resp.ok) throw new Error(`Download failed (${resp.status}) for ${f.label}`);
  let skip = offset && resp.status !== 206 ? offset : 0; // server ignored Range -> skip bytes we already have
  const reader = resp.body.getReader();
  const buf = new Uint8Array(CHUNK);
  let fill = 0, loaded = offset;
  onProgress?.(loaded, f.size);
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    let v = value;
    if (skip) {
      if (v.length <= skip) { skip -= v.length; continue; }
      v = v.subarray(skip); skip = 0;
    }
    let pos = 0;
    while (pos < v.length) {
      const n = Math.min(CHUNK - fill, v.length - pos);
      buf.set(v.subarray(pos, pos + n), fill);
      fill += n; pos += n; loaded += n;
      if (fill === CHUNK) {
        await cache.put(chunkKey(f, idx), new Response(buf.slice()));
        idx++; fill = 0;
        await putMeta(cache, f, { chunks: idx, done: false });
      }
    }
    onProgress?.(loaded, f.size);
  }
  if (fill) { await cache.put(chunkKey(f, idx), new Response(buf.slice(0, fill))); idx++; }
  if (loaded !== f.size) {
    await putMeta(cache, f, { chunks: Math.floor(loaded / CHUNK), done: false });
    throw new Error(`${f.label}: download incomplete (${loaded} of ${f.size} bytes). Tap Download again to resume.`);
  }
  await putMeta(cache, f, { chunks: idx, done: true, size: loaded });
}

// Memory: reading 1.7 GB from Cache Storage leaves big temporary copies behind until the garbage
// collector runs, and on phones Android kills Chrome before that happens. So the file is assembled
// in a short-lived Worker (terminating it frees its temporary memory at once), and after the weights
// are on the GPU the buffer is handed to another Worker that is terminated, freeing it instantly.
const WORKER_URL = new URL("./load-worker.js", import.meta.url);

function runWorker(msg, transfer = []) {
  return new Promise((resolve, reject) => {
    let w;
    try { w = new Worker(WORKER_URL); } catch (e) { reject(e); return; }
    w.onmessage = (e) => { w.terminate(); e.data.ok ? resolve(e.data) : reject(new Error(e.data.error)); };
    w.onerror = (e) => { w.terminate(); reject(new Error(e.message || "worker error")); };
    w.postMessage(msg, transfer);
  });
}

export function freeNow(u8) {
  if (!u8?.buffer || u8.buffer.detached || typeof Worker === "undefined") return;
  runWorker({ mode: "free" }, [u8.buffer]).catch(() => {});
}

async function loadBytes(f) {
  const cache = await caches.open(CACHE);
  const m = await getMeta(cache, f);
  if (!m.done) throw new Error(`${f.label} is not downloaded yet.`);
  const keys = Array.from({ length: m.chunks }, (_, i) => chunkKey(f, i));
  if (typeof Worker !== "undefined") {
    try {
      const r = await runWorker({ cacheName: CACHE, keys, size: m.size });
      return new Uint8Array(r.buffer);
    } catch (e) {
      if (/missing chunk|size mismatch/.test(e.message)) throw new Error(`${f.label} is damaged. Delete the model and download again.`);
      console.warn("Worker loading failed, loading on the page", e);
    }
  }
  const out = new Uint8Array(m.size);
  let pos = 0;
  for (const k of keys) {
    const r = await cache.match(k);
    if (!r) throw new Error(`${f.label} is damaged. Delete the model and download again.`);
    const b = new Uint8Array(await r.arrayBuffer());
    out.set(b, pos); pos += b.length;
  }
  return out;
}

export async function deleteModels() {
  await caches.delete(CACHE);
  await engine.releaseAll();
}

// ------------------------------------------------------------------ helpers
function mulberry32(a) {
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function randn(n, rand) {
  const out = new Float32Array(n);
  for (let i = 0; i < n; i += 2) {
    const u = Math.max(rand(), 1e-12), v = rand();
    const r = Math.sqrt(-2 * Math.log(u));
    out[i] = r * Math.cos(2 * Math.PI * v);
    if (i + 1 < n) out[i + 1] = r * Math.sin(2 * Math.PI * v);
  }
  return out;
}
const f32 = new Float32Array(1), u32 = new Uint32Array(f32.buffer);
function toHalf(v) {
  f32[0] = v; const x = u32[0];
  const sign = (x >>> 16) & 0x8000; let e = ((x >>> 23) & 0xff) - 127 + 15; let m = x & 0x7fffff;
  if (e <= 0) { if (e < -10) return sign; m |= 0x800000; return sign | ((m >> (1 - e)) + 0x1000) >> 13; }
  if (e >= 31) return sign | 0x7c00;
  const h = sign | (e << 10) | ((m + 0x1000) >> 13);
  return h;
}
function fromHalf(h) {
  const s = h & 0x8000 ? -1 : 1, e = (h >> 10) & 0x1f, m = h & 0x3ff;
  if (e === 0) return s * Math.pow(2, -14) * (m / 1024);
  if (e === 31) return m ? NaN : s * Infinity;
  return s * Math.pow(2, e - 15) * (1 + m / 1024);
}

// Euler scheduler sigmas for SD-Turbo ("trailing" timestep spacing).
// startT < 1000 is used for Image → Image: denoising starts from a partly-noised photo.
const AC = (() => {
  const ac = []; let prod = 1;
  for (let i = 0; i < 1000; i++) {
    const b = Math.pow(Math.sqrt(0.00085) + (Math.sqrt(0.012) - Math.sqrt(0.00085)) * i / 999, 2);
    prod *= 1 - b; ac.push(prod);
  }
  return ac;
})();
export function schedule(steps, startT = 1000) {
  const ts = [];
  for (let i = 0; i < steps; i++) ts.push(Math.max(0, Math.round(startT - (i * startT) / steps) - 1));
  const sig = ts.map((t) => Math.sqrt((1 - AC[t]) / AC[t]));
  sig.push(0);
  return { ts, sig };
}

// ------------------------------------------------------------------ engine
export class Engine {
  constructor() {
    this.sessions = {};
    this.backend = "webgpu";
    this.tokenizer = null;
    this.lowMemory = /Android|iPhone|iPad|Mobile/i.test(globalThis.navigator?.userAgent || "");
    this.ort = ort;
  }

  // The UNet is 1.7 GB: it only fits when its weights live on the GPU (WebGPU with 16-bit float support).
  async capability() {
    if (this._cap) return this._cap;
    let cap = { ok: false, reason: "This browser has no WebGPU. Use the latest Google Chrome (Android or PC)." };
    if (globalThis.navigator?.gpu) {
      try {
        const a = await navigator.gpu.requestAdapter({ powerPreference: "high-performance" });
        if (!a) cap.reason = "WebGPU is turned off or not available on this device's GPU.";
        else if (!a.features.has("shader-f16")) cap.reason = "This GPU/browser does not support 16-bit WebGPU (shader-f16), which the model needs. Update Chrome and try again.";
        else cap = { ok: true, gpu: [a.info?.vendor, a.info?.architecture].filter(Boolean).join(" ") || "GPU" };
      } catch (e) { cap.reason = "WebGPU error: " + e.message; }
    }
    return (this._cap = cap);
  }

  async info() {
    const c = await this.capability();
    return c.ok ? `GPU (WebGPU · ${c.gpu})` : "Not supported: " + c.reason;
  }

  async ensureTokenizer() {
    if (this.tokenizer) return;
    const base = new URL("../model/tokenizer/", import.meta.url);
    const [vocab, merges] = await Promise.all([
      fetch(new URL("vocab.json", base)).then((r) => r.json()),
      fetch(new URL("merges.txt", base)).then((r) => r.text()),
    ]);
    this.tokenizer = new ClipTokenizer(vocab, merges, { padToken: "!" });
  }

  async session(key, log) {
    if (this.sessions[key]) return this.sessions[key];
    const f = MODEL_FILES.find((x) => x.key === key);
    log?.(`Loading ${f.label}…`);
    let bytes = await loadBytes(f);
    // Low-RAM loading: weights stay in one buffer and go straight to the GPU (see onnx-external.js)
    const opts = {
      executionProviders: [this.backend], graphOptimizationLevel: "all",
      enableMemPattern: false, enableCpuMemArena: false,
      extra: { session: { disable_prepacking: "1", use_device_allocator_for_initializers: "1" } },
    };
    let s;
    try {
      const { graph } = externalizeModel(bytes, "weights.bin");
      s = await this.ort.InferenceSession.create(graph, { ...opts, externalData: [{ path: "weights.bin", data: bytes }] });
    } catch (e) {
      if (/memory|alloc/i.test(String(e?.message))) throw e;
      console.warn("External-data loading failed, loading normally", e);
      s = await this.ort.InferenceSession.create(bytes, opts);
    }
    freeNow(bytes); // weights are on the GPU now; free the 1.7 GB copy immediately
    bytes = null;
    this.sessions[key] = s;
    return s;
  }

  async release(key) {
    const s = this.sessions[key];
    if (s) { delete this.sessions[key]; try { await s.release(); } catch {} }
  }
  async releaseAll() { for (const k of Object.keys(this.sessions)) await this.release(k); }

  async encodePrompt(prompt, log) {
    await this.ensureTokenizer();
    const ids = this.tokenizer.tokenize(prompt);
    const te = await this.session("text_encoder", log);
    log?.("Reading your prompt…");
    const out = await te.run({ input_ids: new this.ort.Tensor("int32", ids, [1, ids.length]) });
    const emb = new Float32Array(out.last_hidden_state.data);
    for (const t of Object.values(out)) t.dispose?.();
    if (this.lowMemory) await this.release("text_encoder");
    return emb;
  }

  async encodeImage(imageData, w, h, log) {
    const enc = await this.session("vae_encoder", log);
    const n = w * h, px = imageData.data, half = new Uint16Array(3 * n);
    for (let i = 0; i < n; i++) {
      for (let c = 0; c < 3; c++) half[c * n + i] = toHalf(px[i * 4 + c] / 127.5 - 1);
    }
    const out = await enc.run({ sample: new this.ort.Tensor("float16", half, [1, 3, h, w]) });
    const t = out.latent_sample, d = t.data, lat = new Float32Array(d.length);
    const isHalf = t.type === "float16";
    for (let i = 0; i < d.length; i++) lat[i] = (isHalf ? fromHalf(d[i]) : d[i]) * LATENT_SCALE;
    t.dispose?.();
    if (this.lowMemory) await this.release("vae_encoder");
    return lat;
  }

  /**
   * Generate one image. Returns ImageData.
   * opts: { emb, width, height, steps, seed, initLatent, strength, onStep(i,total), stop() }
   */
  async generate(opts, log) {
    const { emb, width: W, height: H, steps, seed } = opts;
    const unet = await this.session("unet", log);
    const vae = await this.session("vae_decoder", log);
    const h = H / 8, w = W / 8, n = 4 * h * w;
    const img2img = !!opts.initLatent;
    // Image → Image: "strength" = how far back towards pure noise we go (1 = ignore the photo)
    const { ts, sig } = img2img ? schedule(Math.max(2, steps), Math.max(50, Math.round(1000 * opts.strength)))
      : schedule(steps);
    const nSteps = ts.length;
    const rand = mulberry32(seed >>> 0);
    const start = 0;
    let x;
    if (img2img) {
      const noise = randn(n, rand);
      x = new Float32Array(n);
      for (let i = 0; i < n; i++) x[i] = opts.initLatent[i] + noise[i] * sig[0];
    } else {
      x = randn(n, rand);
      for (let i = 0; i < n; i++) x[i] *= sig[0];
    }
    const hidden = new this.ort.Tensor("float32", emb, [1, 77, 1024]);
    const total = nSteps - start;
    for (let i = start; i < nSteps; i++) {
      if (opts.stop?.()) return null;
      opts.onStep?.(i - start + 1, total);
      const scale = 1 / Math.sqrt(sig[i] * sig[i] + 1);
      const inp = new Float32Array(n);
      for (let j = 0; j < n; j++) inp[j] = x[j] * scale;
      const out = await unet.run({
        sample: new this.ort.Tensor("float32", inp, [1, 4, h, w]),
        timestep: new this.ort.Tensor("int64", BigInt64Array.from([BigInt(ts[i])]), [1]),
        encoder_hidden_states: hidden,
      });
      const eps = out.out_sample.data;
      // Turbo sampling: predict the clean image, then re-noise to the next level
      const noise = sig[i + 1] > 0 ? randn(n, rand) : null;
      for (let j = 0; j < n; j++) {
        const den = x[j] - sig[i] * eps[j];
        x[j] = noise ? den + noise[j] * sig[i + 1] : den;
      }
      out.out_sample.dispose?.();
    }
    log?.("Painting pixels…");
    for (let j = 0; j < n; j++) x[j] /= LATENT_SCALE;
    const out = await vae.run({ latent_sample: new this.ort.Tensor("float32", x, [1, 4, h, w]) });
    const s = out.sample.data, img = new ImageData(W, H), P = W * H;
    for (let i = 0; i < P; i++) {
      for (let c = 0; c < 3; c++) {
        const v = (Math.max(-1, Math.min(1, s[c * P + i])) + 1) * 127.5;
        img.data[i * 4 + c] = Math.round(v);
      }
      img.data[i * 4 + 3] = 255;
    }
    out.sample.dispose?.();
    return img;
  }
}

export const engine = new Engine();
