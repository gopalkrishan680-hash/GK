// Turns an ONNX model with embedded weights into a tiny "graph only" model whose large tensors point
// (as ONNX external data) at byte ranges of the ORIGINAL file. The original bytes are then given to
// ONNX Runtime as the external-data buffer, so the weights are uploaded to the GPU directly instead
// of being copied into WebAssembly memory. This cuts peak RAM for the 1.7 GB UNet by more than half.
// No weights are copied: only the protobuf structure (a few MB) is rewritten.

function readVarint(b, p) {
  let v = 0, mul = 1, byte;
  do { byte = b[p++]; v += (byte & 0x7f) * mul; mul *= 128; } while (byte & 0x80);
  return [v, p];
}
function varint(v) {
  const out = [];
  while (v > 127) { out.push((v % 128) | 0x80); v = Math.floor(v / 128); }
  out.push(v);
  return out;
}
const enc = new TextEncoder();

// Iterate protobuf fields in b[start, end). Yields {field, wire, start, end, dataStart, dataEnd}.
function* fields(b, start, end) {
  let p = start;
  while (p < end) {
    const s = p;
    let key; [key, p] = readVarint(b, p);
    const field = Math.floor(key / 8), wire = key & 7;
    let ds = p, de;
    if (wire === 0) { [, p] = readVarint(b, p); de = p; }
    else if (wire === 1) { p += 8; de = p; }
    else if (wire === 5) { p += 4; de = p; }
    else if (wire === 2) { let len; [len, ds] = readVarint(b, p); de = ds + len; p = de; }
    else throw new Error("Unsupported protobuf wire type " + wire);
    yield { field, wire, start: s, end: p, dataStart: ds, dataEnd: de };
  }
  if (p !== end) throw new Error("Corrupt protobuf");
}

class Builder {
  constructor() { this.parts = []; this.size = 0; }
  bytes(u8) { this.parts.push(u8); this.size += u8.length; }
  raw(arr) { this.bytes(Uint8Array.from(arr)); }
  lenField(field, payloadBuilder) {
    this.raw([...varint(field * 8 + 2), ...varint(payloadBuilder.size)]);
    for (const p of payloadBuilder.parts) this.bytes(p);
  }
  str(field, s) { const u = enc.encode(s); this.raw([...varint(field * 8 + 2), ...varint(u.length)]); this.bytes(u); }
  vint(field, v) { this.raw([...varint(field * 8), ...varint(v)]); }
  concat() {
    const out = new Uint8Array(this.size); let o = 0;
    for (const p of this.parts) { out.set(p, o); o += p.length; }
    return out;
  }
}

const TENSOR_RAW_DATA = 9, TENSOR_EXTERNAL = 13, TENSOR_LOCATION = 14;

function rewriteTensor(b, start, end, location, minBytes) {
  const out = new Builder();
  let ext = null;
  for (const f of fields(b, start, end)) {
    if (f.field === TENSOR_RAW_DATA && f.wire === 2 && f.dataEnd - f.dataStart >= minBytes) {
      ext = [f.dataStart, f.dataEnd - f.dataStart];
      continue;
    }
    out.bytes(b.subarray(f.start, f.end));
  }
  if (!ext) return null;
  for (const [k, v] of [["location", location], ["offset", String(ext[0])], ["length", String(ext[1])]]) {
    const e = new Builder(); e.str(1, k); e.str(2, v);
    out.lenField(TENSOR_EXTERNAL, e);
  }
  out.vint(TENSOR_LOCATION, 1); // EXTERNAL
  return out;
}

function rewriteGraph(b, start, end, location, minBytes, stats) {
  const out = new Builder();
  for (const f of fields(b, start, end)) {
    if (f.field === 5 && f.wire === 2) { // initializer
      const t = rewriteTensor(b, f.dataStart, f.dataEnd, location, minBytes);
      if (t) { out.lenField(5, t); stats.tensors++; continue; }
    }
    out.bytes(b.subarray(f.start, f.end));
  }
  return out;
}

/** Returns { graph: Uint8Array, tensors } or throws if the model has no embedded weights to move. */
export function externalizeModel(modelBytes, location = "weights.bin", minBytes = 1024) {
  const out = new Builder();
  const stats = { tensors: 0 };
  for (const f of fields(modelBytes, 0, modelBytes.length)) {
    if (f.field === 7 && f.wire === 2) { // graph
      out.lenField(7, rewriteGraph(modelBytes, f.dataStart, f.dataEnd, location, minBytes, stats));
    } else out.bytes(modelBytes.subarray(f.start, f.end));
  }
  if (!stats.tensors) throw new Error("No embedded weights found");
  return { graph: out.concat(), tensors: stats.tensors };
}
