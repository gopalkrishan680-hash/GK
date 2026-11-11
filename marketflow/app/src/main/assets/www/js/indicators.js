/* Technical indicators. Every function is causal: the value at index i only uses bars 0..i. */
(function (root) {
  'use strict';
  var MF = root.MF = root.MF || {};
  var IST_OFFSET = 19800; // seconds

  function col(candles, k) {
    var out = new Array(candles.length);
    for (var i = 0; i < candles.length; i++) out[i] = candles[i][k];
    return out;
  }

  function sma(v, n) {
    var out = new Array(v.length).fill(null), sum = 0;
    for (var i = 0; i < v.length; i++) {
      sum += v[i];
      if (i >= n) sum -= v[i - n];
      if (i >= n - 1) out[i] = sum / n;
    }
    return out;
  }

  function ema(v, n) {
    var out = new Array(v.length).fill(null), k = 2 / (n + 1), sum = 0, prev = null;
    for (var i = 0; i < v.length; i++) {
      if (i < n - 1) { sum += v[i]; continue; }
      if (i === n - 1) { sum += v[i]; prev = sum / n; }
      else prev = v[i] * k + prev * (1 - k);
      out[i] = prev;
    }
    return out;
  }

  /* Wilder smoothing (RMA) */
  function rma(v, n, start) {
    start = start || 0;
    var out = new Array(v.length).fill(null), sum = 0, prev = null;
    for (var i = start; i < v.length; i++) {
      var j = i - start;
      if (j < n - 1) { sum += v[i]; continue; }
      if (j === n - 1) { sum += v[i]; prev = sum / n; }
      else prev = (prev * (n - 1) + v[i]) / n;
      out[i] = prev;
    }
    return out;
  }

  function rsi(close, n) {
    var gains = new Array(close.length).fill(0), losses = new Array(close.length).fill(0);
    for (var i = 1; i < close.length; i++) {
      var d = close[i] - close[i - 1];
      gains[i] = d > 0 ? d : 0;
      losses[i] = d < 0 ? -d : 0;
    }
    var ag = rma(gains, n, 1), al = rma(losses, n, 1);
    var out = new Array(close.length).fill(null);
    for (i = 0; i < close.length; i++) {
      if (ag[i] === null) continue;
      out[i] = al[i] === 0 ? 100 : 100 - 100 / (1 + ag[i] / al[i]);
    }
    return out;
  }

  function trueRange(c) {
    var out = new Array(c.length);
    for (var i = 0; i < c.length; i++) {
      if (i === 0) { out[i] = c[i].h - c[i].l; continue; }
      var pc = c[i - 1].c;
      out[i] = Math.max(c[i].h - c[i].l, Math.abs(c[i].h - pc), Math.abs(c[i].l - pc));
    }
    return out;
  }

  function atr(c, n) { return rma(trueRange(c), n, 0); }

  function macd(close, fast, slow, sig) {
    var ef = ema(close, fast), es = ema(close, slow);
    var line = new Array(close.length).fill(null), firstIdx = -1, vals = [];
    for (var i = 0; i < close.length; i++) {
      if (ef[i] !== null && es[i] !== null) {
        line[i] = ef[i] - es[i];
        if (firstIdx < 0) firstIdx = i;
        vals.push(line[i]);
      }
    }
    var sigVals = ema(vals, sig), signal = new Array(close.length).fill(null), hist = new Array(close.length).fill(null);
    for (i = 0; i < vals.length; i++) {
      if (sigVals[i] !== null) {
        signal[firstIdx + i] = sigVals[i];
        hist[firstIdx + i] = vals[i] - sigVals[i];
      }
    }
    return { line: line, signal: signal, hist: hist };
  }

  function bollinger(close, n, k) {
    var mid = sma(close, n), up = new Array(close.length).fill(null), lo = new Array(close.length).fill(null);
    for (var i = n - 1; i < close.length; i++) {
      var s = 0;
      for (var j = i - n + 1; j <= i; j++) s += (close[j] - mid[i]) * (close[j] - mid[i]);
      var sd = Math.sqrt(s / n);
      up[i] = mid[i] + k * sd;
      lo[i] = mid[i] - k * sd;
    }
    return { mid: mid, upper: up, lower: lo };
  }

  /* Supertrend. dir = 1 (up-trend) / -1 (down-trend) */
  function supertrend(c, n, mult) {
    var a = atr(c, n), len = c.length;
    var line = new Array(len).fill(null), dir = new Array(len).fill(null);
    var fu = null, fl = null, d = 1;
    for (var i = 0; i < len; i++) {
      if (a[i] === null) continue;
      var mid = (c[i].h + c[i].l) / 2, bu = mid + mult * a[i], bl = mid - mult * a[i];
      if (fu === null) { fu = bu; fl = bl; d = c[i].c >= mid ? 1 : -1; }
      else {
        var pc = c[i - 1].c;
        fu = (bu < fu || pc > fu) ? bu : fu;
        fl = (bl > fl || pc < fl) ? bl : fl;
        if (d === 1 && c[i].c < fl) d = -1;
        else if (d === -1 && c[i].c > fu) d = 1;
      }
      dir[i] = d;
      line[i] = d === 1 ? fl : fu;
    }
    return { line: line, dir: dir };
  }

  /* Highest high / lowest low of the PREVIOUS n bars (excludes the current bar). */
  function donchian(c, n) {
    var up = new Array(c.length).fill(null), lo = new Array(c.length).fill(null);
    for (var i = n; i < c.length; i++) {
      var hh = -Infinity, ll = Infinity;
      for (var j = i - n; j < i; j++) { if (c[j].h > hh) hh = c[j].h; if (c[j].l < ll) ll = c[j].l; }
      up[i] = hh; lo[i] = ll;
    }
    return { upper: up, lower: lo };
  }

  function istDayKey(t) { return Math.floor((t + IST_OFFSET) / 86400); }
  function istMinuteOfDay(t) { return Math.floor(((t + IST_OFFSET) % 86400) / 60); }

  /* Session VWAP, resets each IST trading day. Falls back to typical price when volume is 0 (indices). */
  function vwap(c) {
    var out = new Array(c.length).fill(null), pv = 0, vol = 0, day = null, n = 0, sumTp = 0;
    for (var i = 0; i < c.length; i++) {
      var d = istDayKey(c[i].t);
      if (d !== day) { day = d; pv = 0; vol = 0; n = 0; sumTp = 0; }
      var tp = (c[i].h + c[i].l + c[i].c) / 3, v = c[i].v || 0;
      pv += tp * v; vol += v; n++; sumTp += tp;
      out[i] = vol > 0 ? pv / vol : sumTp / n;
    }
    return out;
  }

  function obv(c) {
    var out = new Array(c.length), x = 0;
    for (var i = 0; i < c.length; i++) {
      if (i > 0) {
        if (c[i].c > c[i - 1].c) x += c[i].v || 0;
        else if (c[i].c < c[i - 1].c) x -= c[i].v || 0;
      }
      out[i] = x;
    }
    return out;
  }

  /* Money Flow Index */
  function mfi(c, n) {
    var out = new Array(c.length).fill(null), pos = [], neg = [];
    for (var i = 0; i < c.length; i++) {
      var tp = (c[i].h + c[i].l + c[i].c) / 3, p = 0, ng = 0;
      if (i > 0) {
        var ptp = (c[i - 1].h + c[i - 1].l + c[i - 1].c) / 3, raw = tp * (c[i].v || 0);
        if (tp > ptp) p = raw; else if (tp < ptp) ng = raw;
      }
      pos.push(p); neg.push(ng);
      if (i >= n) {
        var sp = 0, sn = 0;
        for (var j = i - n + 1; j <= i; j++) { sp += pos[j]; sn += neg[j]; }
        out[i] = (sp + sn) === 0 ? 50 : 100 * sp / (sp + sn);
      }
    }
    return out;
  }

  /* Chaikin Money Flow: +ve = closes near highs on volume (accumulation), -ve = distribution */
  function cmf(c, n) {
    var out = new Array(c.length).fill(null), mfv = [], vol = [];
    for (var i = 0; i < c.length; i++) {
      var r = c[i].h - c[i].l, v = c[i].v || 0;
      mfv.push(r > 0 ? ((c[i].c - c[i].l) - (c[i].h - c[i].c)) / r * v : 0);
      vol.push(v);
      if (i >= n - 1) {
        var a = 0, b = 0;
        for (var j = i - n + 1; j <= i; j++) { a += mfv[j]; b += vol[j]; }
        out[i] = b > 0 ? a / b : 0;
      }
    }
    return out;
  }

  function adx(c, n) {
    var len = c.length, pdm = new Array(len).fill(0), ndm = new Array(len).fill(0);
    for (var i = 1; i < len; i++) {
      var up = c[i].h - c[i - 1].h, dn = c[i - 1].l - c[i].l;
      pdm[i] = (up > dn && up > 0) ? up : 0;
      ndm[i] = (dn > up && dn > 0) ? dn : 0;
    }
    var tr = rma(trueRange(c), n, 1), p = rma(pdm, n, 1), m = rma(ndm, n, 1);
    var dx = new Array(len).fill(0), start = -1, pdi = new Array(len).fill(null), ndi = new Array(len).fill(null);
    for (i = 0; i < len; i++) {
      if (tr[i] === null || tr[i] === 0) continue;
      if (start < 0) start = i;
      pdi[i] = 100 * p[i] / tr[i]; ndi[i] = 100 * m[i] / tr[i];
      var s = pdi[i] + ndi[i];
      dx[i] = s === 0 ? 0 : 100 * Math.abs(pdi[i] - ndi[i]) / s;
    }
    var out = start < 0 ? new Array(len).fill(null) : rma(dx, n, start);
    return { adx: out, pdi: pdi, ndi: ndi };
  }

  /* Rolling highest/lowest including the current bar */
  function highest(v, n) {
    var out = new Array(v.length).fill(null);
    for (var i = n - 1; i < v.length; i++) { var m = -Infinity; for (var j = i - n + 1; j <= i; j++) if (v[j] > m) m = v[j]; out[i] = m; }
    return out;
  }
  function lowest(v, n) {
    var out = new Array(v.length).fill(null);
    for (var i = n - 1; i < v.length; i++) { var m = Infinity; for (var j = i - n + 1; j <= i; j++) if (v[j] < m) m = v[j]; out[i] = m; }
    return out;
  }

  /* Classic floor pivots from a previous period's high/low/close */
  function pivots(h, l, c) {
    var p = (h + l + c) / 3;
    return { p: p, r1: 2 * p - l, s1: 2 * p - h, r2: p + (h - l), s2: p - (h - l), r3: h + 2 * (p - l), s3: l - 2 * (h - p) };
  }

  MF.ind = {
    IST_OFFSET: IST_OFFSET, col: col, sma: sma, ema: ema, rma: rma, rsi: rsi, atr: atr, trueRange: trueRange,
    macd: macd, bollinger: bollinger, supertrend: supertrend, donchian: donchian, vwap: vwap, obv: obv,
    mfi: mfi, cmf: cmf, adx: adx, highest: highest, lowest: lowest, pivots: pivots,
    istDayKey: istDayKey, istMinuteOfDay: istMinuteOfDay
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = MF;
})(typeof window !== 'undefined' ? window : globalThis);
