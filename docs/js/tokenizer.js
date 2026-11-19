// CLIP BPE tokenizer (same algorithm as OpenAI CLIP / OpenCLIP), no dependencies.
// Needs vocab.json + merges.txt from the model's tokenizer folder.

function bytesToUnicode() {
  const bs = [];
  for (let i = 33; i <= 126; i++) bs.push(i);
  for (let i = 161; i <= 172; i++) bs.push(i);
  for (let i = 174; i <= 255; i++) bs.push(i);
  const cs = bs.slice();
  let n = 0;
  for (let b = 0; b < 256; b++) {
    if (!bs.includes(b)) { bs.push(b); cs.push(256 + n); n++; }
  }
  const map = {};
  bs.forEach((b, i) => { map[b] = String.fromCharCode(cs[i]); });
  return map;
}

// '!' is the pad token of this model and is split out as a special token (matches HF behaviour)
const PAT = /<\|startoftext\|>|<\|endoftext\|>|!|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}!]+/giu;

export class ClipTokenizer {
  constructor(vocab, mergesText, { maxLength = 77, padToken = "!" } = {}) {
    this.encoder = vocab;
    this.byteEncoder = bytesToUnicode();
    const lines = mergesText.split("\n").slice(1).filter((l) => l.trim().length);
    this.ranks = new Map();
    // CLIP uses the first 49152-256-2 merges
    lines.slice(0, 49152 - 256 - 2).forEach((l, i) => this.ranks.set(l, i));
    this.cache = new Map();
    this.bos = vocab["<|startoftext|>"];
    this.eos = vocab["<|endoftext|>"];
    this.pad = vocab[padToken] ?? this.eos;
    this.maxLength = maxLength;
  }

  bpe(token) {
    if (this.cache.has(token)) return this.cache.get(token);
    let word = [...token.slice(0, -1)];
    word.push(token.slice(-1) + "</w>");
    if (word.length === 1) { this.cache.set(token, word); return word; }
    while (true) {
      let best = null, bestRank = Infinity;
      for (let i = 0; i < word.length - 1; i++) {
        const r = this.ranks.get(word[i] + " " + word[i + 1]);
        if (r !== undefined && r < bestRank) { bestRank = r; best = i; }
      }
      if (best === null) break;
      const a = word[best], b = word[best + 1];
      const next = [];
      for (let i = 0; i < word.length; i++) {
        if (i < word.length - 1 && word[i] === a && word[i + 1] === b) { next.push(a + b); i++; }
        else next.push(word[i]);
      }
      word = next;
      if (word.length === 1) break;
    }
    this.cache.set(token, word);
    return word;
  }

  encode(text) {
    const clean = text.replace(/\s+/g, " ").trim().toLowerCase();
    const ids = [];
    const utf8 = new TextEncoder();
    for (const m of clean.matchAll(PAT)) {
      if (m[0] === "!") { ids.push(this.pad); continue; }
      const tok = [...utf8.encode(m[0])].map((b) => this.byteEncoder[b]).join("");
      for (const piece of this.bpe(tok)) {
        const id = this.encoder[piece];
        if (id !== undefined) ids.push(id);
      }
    }
    return ids;
  }

  // Returns Int32Array of length maxLength: [bos, ...tokens, eos, pad...]
  __call__(text) { return this.tokenize(text); }
  tokenize(text) {
    const body = this.encode(text).slice(0, this.maxLength - 2);
    const out = new Int32Array(this.maxLength).fill(this.pad);
    out[0] = this.bos;
    body.forEach((id, i) => { out[i + 1] = id; });
    out[body.length + 1] = this.eos;
    return out;
  }
}
