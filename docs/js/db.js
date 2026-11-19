// Image history in IndexedDB (stays on this device, works offline).
const DB = "gk-studio", STORE = "images";
let dbp = null;

function open() {
  if (dbp) return dbp;
  dbp = new Promise((res, rej) => {
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => r.result.createObjectStore(STORE, { keyPath: "id", autoIncrement: true });
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
  return dbp;
}

async function tx(mode, fn) {
  const db = await open();
  return new Promise((res, rej) => {
    const t = db.transaction(STORE, mode);
    const out = fn(t.objectStore(STORE));
    t.oncomplete = () => res(out?.result ?? out);
    t.onerror = () => rej(t.error);
  });
}

export const addImage = (rec) => tx("readwrite", (s) => s.add(rec));
export const deleteImage = (id) => tx("readwrite", (s) => s.delete(id));
export const clearImages = () => tx("readwrite", (s) => s.clear());
export async function listImages() {
  const all = await tx("readonly", (s) => s.getAll());
  return all.sort((a, b) => b.id - a.id);
}
