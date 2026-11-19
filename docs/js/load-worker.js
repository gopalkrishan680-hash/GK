// Runs in a short-lived Web Worker. Reading 1.7 GB from Cache Storage leaves a lot of temporary
// memory behind (response blobs, chunk buffers). Doing it here and then terminating the worker frees
// all of that instantly, instead of waiting for the browser's garbage collector.
self.onmessage = async (e) => {
  const { cacheName, keys, size, mode } = e.data;
  try {
    if (mode === "free") { self.postMessage({ ok: true }); return; } // receives a buffer only to drop it
    const cache = await caches.open(cacheName);
    const out = new Uint8Array(size);
    let pos = 0;
    for (const k of keys) {
      const r = await cache.match(k);
      if (!r) throw new Error("missing chunk");
      const b = new Uint8Array(await r.arrayBuffer());
      out.set(b, pos); pos += b.length;
    }
    if (pos !== size) throw new Error("size mismatch");
    self.postMessage({ ok: true, buffer: out.buffer }, [out.buffer]);
  } catch (err) {
    self.postMessage({ ok: false, error: String(err?.message || err) });
  }
};
