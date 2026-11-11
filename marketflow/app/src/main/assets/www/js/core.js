/* Core helpers: storage, formatting, IST time, network bridge, SHA-256. */
(function (root) {
  'use strict';
  var MF = root.MF = root.MF || {};
  var IST = 19800;

  /* ---------- storage ---------- */
  var mem = {};
  MF.store = {
    get: function (k, def) {
      try { var v = root.localStorage ? root.localStorage.getItem('mf.' + k) : mem[k]; return v === null || v === undefined ? def : JSON.parse(v); }
      catch (e) { return k in mem ? JSON.parse(mem[k]) : def; }
    },
    set: function (k, v) {
      var s = JSON.stringify(v); mem[k] = s;
      try { if (root.localStorage) root.localStorage.setItem('mf.' + k, s); } catch (e) { /* storage unavailable */ }
    }
  };

  /* ---------- formatting ---------- */
  var F = MF.fmt = {};
  F.num = function (v, d) {
    if (v === null || v === undefined || isNaN(v)) return '–';
    if (!isFinite(v)) return '∞';
    return Number(v).toLocaleString('en-IN', { minimumFractionDigits: d === undefined ? 2 : d, maximumFractionDigits: d === undefined ? 2 : d });
  };
  F.price = function (v) { return F.num(v, 2); };
  F.pct = function (v, d) { if (v === null || v === undefined || isNaN(v)) return '–'; return (v > 0 ? '+' : '') + F.num(v, d === undefined ? 2 : d) + '%'; };
  F.signed = function (v, d) { if (v === null || v === undefined || isNaN(v)) return '–'; return (v > 0 ? '+' : '') + F.num(v, d === undefined ? 2 : d); };
  F.compact = function (v) {
    if (v === null || v === undefined || isNaN(v)) return '–';
    var a = Math.abs(v);
    if (a >= 1e7) return F.num(v / 1e7, 2) + ' Cr';
    if (a >= 1e5) return F.num(v / 1e5, 2) + ' L';
    if (a >= 1e3) return F.num(v / 1e3, 1) + ' K';
    return F.num(v, 0);
  };
  F.rupee = function (v, d) { if (v === null || v === undefined || isNaN(v)) return '–'; return (v < 0 ? '-' : '') + '₹' + F.num(Math.abs(v), d === undefined ? 0 : d); };
  function p2(n) { return (n < 10 ? '0' : '') + n; }
  var MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  F.istParts = function (t) { var d = new Date((t + IST) * 1000); return { y: d.getUTCFullYear(), mo: d.getUTCMonth(), d: d.getUTCDate(), h: d.getUTCHours(), mi: d.getUTCMinutes(), s: d.getUTCSeconds(), dow: d.getUTCDay() }; };
  F.date = function (t) { var p = F.istParts(t); return p2(p.d) + ' ' + MON[p.mo] + ' ' + p.y; };
  F.time = function (t) { var p = F.istParts(t); return p2(p.h) + ':' + p2(p.mi); };
  F.dateTime = function (t, intraday) { return intraday === false ? F.date(t) : F.date(t) + ' ' + F.time(t); };
  F.apiTime = function (t) { var p = F.istParts(t); return p.y + '-' + p2(p.mo + 1) + '-' + p2(p.d) + ' ' + p2(p.h) + ':' + p2(p.mi) + ':' + p2(p.s); };
  F.isoDate = function (t) { var p = F.istParts(t); return p.y + '-' + p2(p.mo + 1) + '-' + p2(p.d); };
  F.esc = function (s) { return String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, function (ch) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]; }); };

  /* ---------- market clock (NSE cash session 09:15 - 15:30 IST, Mon-Fri; exchange holidays are not known) ---------- */
  MF.clock = {
    now: function () { return Math.floor(Date.now() / 1000); },
    isMarketOpen: function (t) {
      var p = F.istParts(t === undefined ? MF.clock.now() : t), m = p.h * 60 + p.mi;
      return p.dow >= 1 && p.dow <= 5 && m >= 555 && m < 930;
    }
  };

  /* ---------- network ---------- */
  var cbs = {}, seq = 0;
  root.__mfHttpCb = function (id, status, body) { var cb = cbs[id]; if (cb) { delete cbs[id]; cb(status, body); } };
  MF.net = {
    native: function () { return !!root.AndroidBridge; },
    /* Resolves { status, body }. status 0 = network failure. */
    request: function (o) {
      var headers = o.headers || {}, method = o.method || 'GET', body = o.body || '';
      if (root.AndroidBridge) {
        return new Promise(function (res) {
          var id = 'r' + (++seq);
          cbs[id] = function (status, text) { res({ status: status, body: text }); };
          root.AndroidBridge.http(id, method, o.url, JSON.stringify(headers), body);
        });
      }
      // Browser development mode: tools/dev-server.js relays the request.
      return root.fetch('/proxy', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ method: method, url: o.url, headers: headers, body: body }) })
        .then(function (r) { return r.json(); })
        .catch(function (e) { return { status: 0, body: String(e) }; });
    }
  };

  /* ---------- SHA-256 (hex) — used for the Groww key+secret checksum ---------- */
  MF.sha256 = function (str) {
    var K = [], H = [], isPrime = function (n) { for (var f = 2; f * f <= n; f++) if (n % f === 0) return false; return true; };
    var frac = function (x) { return ((x - Math.floor(x)) * 4294967296) | 0; };
    for (var c = 2, cnt = 0; cnt < 64; c++) if (isPrime(c)) { if (cnt < 8) H[cnt] = frac(Math.pow(c, 1 / 2)); K[cnt++] = frac(Math.pow(c, 1 / 3)); }
    var bytes = [], i;
    var utf8 = unescape(encodeURIComponent(str));
    for (i = 0; i < utf8.length; i++) bytes.push(utf8.charCodeAt(i));
    var bitLen = bytes.length * 8;
    bytes.push(0x80);
    while (bytes.length % 64 !== 56) bytes.push(0);
    for (i = 7; i >= 0; i--) bytes.push(i >= 4 ? 0 : (bitLen >>> (i * 8)) & 255);
    var w = new Array(64), rotr = function (x, n) { return (x >>> n) | (x << (32 - n)); };
    for (var off = 0; off < bytes.length; off += 64) {
      for (i = 0; i < 16; i++) w[i] = (bytes[off + i * 4] << 24) | (bytes[off + i * 4 + 1] << 16) | (bytes[off + i * 4 + 2] << 8) | bytes[off + i * 4 + 3];
      for (i = 16; i < 64; i++) {
        var s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3), s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], cc = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (i = 0; i < 64; i++) {
        var S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25), ch = (e & f) ^ (~e & g), t1 = (h + S1 + ch + K[i] + w[i]) | 0;
        var S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22), maj = (a & b) ^ (a & cc) ^ (b & cc), t2 = (S0 + maj) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = cc; cc = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + cc) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    return H.map(function (x) { return ('00000000' + (x >>> 0).toString(16)).slice(-8); }).join('');
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = MF;
})(typeof window !== 'undefined' ? window : globalThis);
