// Keeps the app itself (HTML/JS/engine) available offline. Models are cached separately by js/sd.js.
const VERSION = "gk-app-v3";
const FILES = [
  "./", "index.html", "manifest.webmanifest", "icons/icon-192.png", "icons/icon-512.png",
  "js/app.js", "js/sd.js", "js/styles.js", "js/db.js", "js/tokenizer.js", "js/onnx-external.js",
  "vendor/ort/ort.all.min.mjs", "vendor/ort/ort-wasm-simd-threaded.jsep.mjs",
  "vendor/ort/ort-wasm-simd-threaded.jsep.wasm",
  "model/tokenizer/vocab.json", "model/tokenizer/merges.txt",
];
self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(
    keys.filter((k) => k.startsWith("gk-app-") && k !== VERSION).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return; // model downloads go straight to the network
  // Big, rarely changing files (engine, tokenizer, icons): cache first.
  const immutable = /\/(vendor|model|icons)\//.test(url.pathname);
  e.respondWith(caches.open(VERSION).then(async (c) => {
    const hit = await c.match(e.request, { ignoreSearch: true });
    if (immutable && hit) return hit;
    try { // app code: network first (so updates arrive), cache as offline fallback
      const r = await fetch(e.request);
      if (r.ok) c.put(e.request, r.clone());
      return r;
    } catch {
      return hit || (await c.match("index.html")) || new Response("Offline", { status: 503 });
    }
  }));
});
