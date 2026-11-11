/* Market data layer.
 *  - Groww Trade API (official, needs the user's own API subscription and token)
 *  - Demo provider (clearly-labelled simulated prices so every screen can be explored without a key)
 * Candle shape everywhere: { t: epochSeconds(UTC), o, h, l, c, v, oi }
 */
(function (root) {
  'use strict';
  var MF = root.MF = root.MF || {};
  var IST = 19800, DAY = 86400, OPEN_MIN = 555, SESSION_MIN = 375;

  var TF = [
    { id: '1m', label: '1m', groww: '1minute', sec: 60, chunkDays: 30, defDays: 5, maxDays: 30 },
    { id: '5m', label: '5m', groww: '5minute', sec: 300, chunkDays: 30, defDays: 20, maxDays: 90 },
    { id: '15m', label: '15m', groww: '15minute', sec: 900, chunkDays: 90, defDays: 60, maxDays: 180 },
    { id: '1h', label: '1H', groww: '1hour', sec: 3600, chunkDays: 180, defDays: 180, maxDays: 720 },
    { id: '1D', label: '1D', groww: '1day', sec: 86400, chunkDays: 180, defDays: 540, maxDays: 2200 },
    { id: '1W', label: '1W', groww: '1week', sec: 604800, chunkDays: 180, defDays: 2200, maxDays: 2200 }
  ];
  function tf(id) { for (var i = 0; i < TF.length; i++) if (TF[i].id === id) return TF[i]; return TF[4]; }
  function key(s) { return s.ex + ':' + s.sym; }

  /* =====================================================================================
   * Groww Trade API
   * ===================================================================================== */
  var BASE = MF.store.get('devApiBase', null) || 'https://api.groww.in'; // devApiBase is only used by the automated tests
  var chain = Promise.resolve(), GAP_MS = 130;

  function throttled(fn) {
    var p = chain.then(fn, fn);
    chain = p.then(function () { return new Promise(function (r) { setTimeout(r, GAP_MS); }); }, function () { return new Promise(function (r) { setTimeout(r, GAP_MS); }); });
    return p;
  }

  function settings() { return MF.store.get('settings', { mode: 'demo', authMode: 'token', accessToken: '', apiKey: '', apiSecret: '' }); }

  /* Tokens issued by Groww expire daily at 06:00 IST. */
  function tokenFresh(issuedAt) {
    if (!issuedAt) return false;
    var now = MF.clock.now(), sixToday = Math.floor((now + IST) / DAY) * DAY - IST + 6 * 3600;
    var lastExpiry = now >= sixToday ? sixToday : sixToday - DAY;
    return issuedAt > lastExpiry;
  }

  function getToken(force) {
    var s = settings();
    if (s.authMode !== 'keysecret') {
      if (!s.accessToken) return Promise.reject(new Error('No Groww access token saved. Open Settings to add one.'));
      return Promise.resolve(s.accessToken.trim());
    }
    var cached = MF.store.get('growwToken', null);
    if (!force && cached && cached.token && cached.key === s.apiKey && tokenFresh(cached.at)) return Promise.resolve(cached.token);
    if (!s.apiKey || !s.apiSecret) return Promise.reject(new Error('Groww API key and secret are not saved. Open Settings to add them.'));
    var ts = String(MF.clock.now());
    return MF.net.request({
      method: 'POST', url: BASE + '/v1/token/api/access',
      headers: { 'Authorization': 'Bearer ' + s.apiKey.trim(), 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify({ key_type: 'approval', checksum: MF.sha256(s.apiSecret.trim() + ts), timestamp: ts })
    }).then(function (r) {
      var j = null; try { j = JSON.parse(r.body); } catch (e) { /* not json */ }
      var tok = j && (j.token || (j.payload && j.payload.token));
      if (!tok) throw new Error(errText(r, j) + ' (token request). Check that today\'s API key is approved on the Groww API keys page.');
      MF.store.set('growwToken', { token: tok, at: MF.clock.now(), key: s.apiKey });
      return tok;
    });
  }

  function errText(r, j) {
    if (r.status === 0) return 'Network error — check your internet connection';
    var m = j && j.error && (j.error.message || j.error.code);
    if (r.status === 401 || r.status === 403) return 'Groww rejected the credentials (HTTP ' + r.status + (m ? ': ' + m : '') + '). The access token expires every day at 6:00 AM — generate a new one';
    if (r.status === 429) return 'Groww rate limit reached, try again in a minute';
    return 'Groww API error' + (m ? ': ' + m : '') + ' (HTTP ' + r.status + ')';
  }

  function qs(o) { return Object.keys(o).map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(o[k]); }).join('&'); }

  function api(path, params, attempt) {
    attempt = attempt || 0;
    return getToken(false).then(function (tok) {
      return throttled(function () {
        return MF.net.request({ url: BASE + path + (params ? '?' + qs(params) : ''), headers: { 'Authorization': 'Bearer ' + tok, 'Accept': 'application/json', 'X-API-VERSION': '1.0' } });
      });
    }).then(function (r) {
      var j = null; try { j = JSON.parse(r.body); } catch (e) { /* not json */ }
      if (r.status === 429 && attempt < 2) return new Promise(function (res) { setTimeout(res, 1500 * (attempt + 1)); }).then(function () { return api(path, params, attempt + 1); });
      if ((r.status === 401 || r.status === 403) && attempt === 0 && settings().authMode === 'keysecret') return getToken(true).then(function () { return api(path, params, 1); });
      if (r.status !== 200 || !j || j.status !== 'SUCCESS') throw new Error(errText(r, j));
      return j.payload;
    });
  }

  /* Groww returns ohlc either as an object or as a string like "{open: 149.50,high: 150.50,low: 148.50,close: 149.50}". */
  function parseOhlc(x) {
    if (!x) return {};
    if (typeof x === 'object') return x;
    var o = {}; String(x).replace(/(open|high|low|close)\s*:\s*(-?[\d.]+)/g, function (_, k, v) { o[k] = parseFloat(v); return ''; });
    return o;
  }

  /* Candle timestamps arrive as epoch seconds or as an IST wall-clock string "2025-09-24T10:30:00". */
  function parseTs(x) {
    if (typeof x === 'number') return x > 1e11 ? Math.floor(x / 1000) : x;
    var m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(String(x));
    if (!m) { var p = Date.parse(x); return isNaN(p) ? null : Math.floor(p / 1000); }
    return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)) / 1000 - IST;
  }

  function normCandles(rows) {
    var out = [];
    (rows || []).forEach(function (r) {
      var t = parseTs(r[0]);
      if (t === null || r[1] === null || r[4] === null || r[1] === undefined) return;
      out.push({ t: t, o: +r[1], h: +r[2], l: +r[3], c: +r[4], v: +(r[5] || 0), oi: r[6] === null || r[6] === undefined ? null : +r[6] });
    });
    return out;
  }

  function chunk(arr, n) { var o = []; for (var i = 0; i < arr.length; i += n) o.push(arr.slice(i, i + n)); return o; }

  var Groww = {
    name: 'Groww',
    quotes: function (syms) {
      var out = {}, jobs = [];
      chunk(syms, 50).forEach(function (part) {
        var list = part.map(function (s) { return s.ex + '_' + s.sym; }).join(',');
        jobs.push(api('/v1/live-data/ltp', { segment: 'CASH', exchange_symbols: list }).then(function (p) {
          part.forEach(function (s) { var v = p[s.ex + '_' + s.sym]; if (v !== undefined && v !== null) (out[key(s)] = out[key(s)] || {}).ltp = +v; });
        }));
        jobs.push(api('/v1/live-data/ohlc', { segment: 'CASH', exchange_symbols: list }).then(function (p) {
          part.forEach(function (s) {
            var o = parseOhlc(p[s.ex + '_' + s.sym]), q = out[key(s)] = out[key(s)] || {};
            q.open = o.open; q.high = o.high; q.low = o.low; q.prevClose = o.close;
          });
        }));
      });
      return Promise.all(jobs).then(function () {
        Object.keys(out).forEach(function (k) {
          var q = out[k];
          if (q.ltp !== undefined && q.prevClose) { q.chg = q.ltp - q.prevClose; q.chgPct = 100 * q.chg / q.prevClose; }
        });
        return out;
      });
    },
    quote: function (s) {
      return api('/v1/live-data/quote', { exchange: s.ex, segment: 'CASH', trading_symbol: s.sym }).then(function (p) {
        var o = parseOhlc(p.ohlc), ltp = +p.last_price;
        var chg = p.day_change !== undefined && p.day_change !== null ? +p.day_change : (o.close ? ltp - o.close : null);
        var prev = chg !== null ? ltp - chg : o.close;
        return {
          ltp: ltp, chg: chg, chgPct: p.day_change_perc !== undefined && p.day_change_perc !== null ? +p.day_change_perc : (prev ? 100 * chg / prev : null),
          prevClose: prev, open: o.open, high: o.high, low: o.low, volume: p.volume, avgPrice: p.average_price,
          totalBuyQty: p.total_buy_quantity, totalSellQty: p.total_sell_quantity,
          depth: p.depth && { buy: p.depth.buy || [], sell: p.depth.sell || [] },
          upperCircuit: p.upper_circuit_limit, lowerCircuit: p.lower_circuit_limit, high52: p.week_52_high, low52: p.week_52_low,
          oi: p.open_interest, lastTradeTime: p.last_trade_time ? Math.floor(p.last_trade_time / 1000) : null
        };
      });
    },
    candles: function (s, tfId, days) {
      var t = tf(tfId), now = MF.clock.now(), from = now - days * DAY, ranges = [], a = from;
      while (a < now) { var b = Math.min(a + t.chunkDays * DAY, now); ranges.push([a, b]); a = b; }
      var all = [], seq = Promise.resolve();
      ranges.forEach(function (r) {
        seq = seq.then(function () {
          return api('/v1/historical/candles', { exchange: s.ex, segment: 'CASH', groww_symbol: s.ex + '-' + s.sym, start_time: MF.fmt.apiTime(r[0]), end_time: MF.fmt.apiTime(r[1]), candle_interval: t.groww })
            .then(function (p) { all = all.concat(normCandles(p.candles)); });
        });
      });
      return seq.then(function () {
        all.sort(function (x, y) { return x.t - y.t; });
        var out = [];
        all.forEach(function (c) { if (!out.length || out[out.length - 1].t !== c.t) out.push(c); });
        return out;
      });
    },
    expiries: function (u) {
      var now = MF.fmt.istParts(MF.clock.now()), today = MF.fmt.isoDate(MF.clock.now());
      var next = now.mo === 11 ? { y: now.y + 1, m: 1 } : { y: now.y, m: now.mo + 2 };
      function get(y, m) { return api('/v1/historical/expiries', { exchange: u.ex, underlying_symbol: u.sym, year: y, month: m }).then(function (p) { return p.expiries || []; }, function () { return []; }); }
      return Promise.all([get(now.y, now.mo + 1), get(next.y, next.m)]).then(function (r) {
        return r[0].concat(r[1]).filter(function (d, i, a) { return d >= today && a.indexOf(d) === i; }).sort();
      });
    },
    optionChain: function (u, expiry) {
      return api('/v1/option-chain/exchange/' + u.ex + '/underlying/' + encodeURIComponent(u.sym), { expiry_date: expiry }).then(function (p) {
        var rows = [];
        Object.keys(p.strikes || {}).forEach(function (k) {
          var st = p.strikes[k];
          function leg(x) { return x ? { ltp: x.ltp, oi: x.open_interest || 0, volume: x.volume || 0, iv: x.greeks && x.greeks.iv, delta: x.greeks && x.greeks.delta, symbol: x.trading_symbol } : null; }
          rows.push({ strike: parseFloat(k), ce: leg(st.CE), pe: leg(st.PE) });
        });
        rows.sort(function (a, b) { return a.strike - b.strike; });
        return { spot: p.underlying_ltp, rows: rows };
      });
    },
    test: function () { return Groww.quotes([{ ex: 'NSE', sym: 'NIFTY' }]).then(function (q) { var v = q['NSE:NIFTY']; if (!v || v.ltp === undefined) throw new Error('Connected, but no price was returned for NIFTY'); return 'Connected. NIFTY 50 is at ' + MF.fmt.price(v.ltp); }); }
  };

  /* =====================================================================================
   * Demo provider — simulated prices (deterministic per symbol, never real market data)
   * ===================================================================================== */
  var INTRA_DAYS = 60, OLD_DAYS = 1500, demoCache = {}, demoOrder = [];
  var BASE_PRICE = { NIFTY: 25800, BANKNIFTY: 58200, SENSEX: 84300, FINNIFTY: 27400, INDIAVIX: 13.4, NIFTYIT: 37000, NIFTYMIDCAP: 60000, NIFTYJR: 69000 };

  function hash(s) { var h = 2166136261; for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; }
  function rng(seed) { var a = seed >>> 0; return function () { a = (a + 0x6D2B79F5) | 0; var t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
  function gauss(r) { var u = 0, v = 0; while (u === 0) u = r(); v = r(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }

  function tradingDays(n) {
    var out = [], d = Math.floor((MF.clock.now() + IST) / DAY);
    while (out.length < n) { var dow = (d + 4) % 7; if (dow !== 0 && dow !== 6) out.unshift(d); d--; }
    return out;
  }

  function demoGen(s) {
    var k = key(s);
    if (demoCache[k] && demoCache[k].today === Math.floor((MF.clock.now() + IST) / DAY)) return demoCache[k];
    var r = rng(hash(k)), isIdx = s.type === 'IDX', days = tradingDays(INTRA_DAYS + OLD_DAYS);
    var annVol = isIdx ? 0.13 : 0.22 + r() * 0.16, dVol = annVol / Math.sqrt(252), mVol = dVol / Math.sqrt(SESSION_MIN);
    var price = 100, regime = 0, regimeLeft = 0, daily = [];
    var baseVol = isIdx ? 0 : Math.round(2e5 + r() * 6e6);

    for (var i = 0; i < OLD_DAYS; i++) {
      if (regimeLeft-- <= 0) { regime = (r() - 0.34) * 0.0024; regimeLeft = 40 + Math.floor(r() * 120); }
      var o = price * (1 + gauss(r) * dVol * 0.3), c = o * (1 + regime + gauss(r) * dVol);
      var h = Math.max(o, c) * (1 + Math.abs(gauss(r)) * dVol * 0.45), l = Math.min(o, c) * (1 - Math.abs(gauss(r)) * dVol * 0.45);
      var v = baseVol ? Math.round(baseVol * Math.exp(gauss(r) * 0.45) * (1 + 6 * Math.abs(c / o - 1) / dVol * 0.12)) : 0;
      daily.push({ t: days[i] * DAY - IST, o: o, h: h, l: l, c: c, v: v });
      price = c;
    }
    var m = new Float32Array(INTRA_DAYS * SESSION_MIN * 5), idx = 0;
    for (var d = 0; d < INTRA_DAYS; d++) {
      if (regimeLeft-- <= 0) { regime = (r() - 0.34) * 0.0024; regimeLeft = 40 + Math.floor(r() * 120); }
      price *= 1 + gauss(r) * dVol * 0.3;
      var burstAt = r() < 0.35 ? Math.floor(r() * 330) : -1, burstDir = r() < 0.5 ? -1 : 1, dayDrift = regime / SESSION_MIN + gauss(r) * dVol * 0.5 / SESSION_MIN;
      for (var mi = 0; mi < SESSION_MIN; mi++) {
        var burst = burstAt >= 0 && mi >= burstAt && mi < burstAt + 25;
        var o1 = price, c1 = o1 * (1 + dayDrift + gauss(r) * mVol + (burst ? burstDir * mVol * 1.1 : 0));
        var h1 = Math.max(o1, c1) * (1 + Math.abs(gauss(r)) * mVol * 0.4), l1 = Math.min(o1, c1) * (1 - Math.abs(gauss(r)) * mVol * 0.4);
        var u = 1 + 2.2 * Math.pow(Math.abs(mi - 187) / 187, 2.2);
        var v1 = baseVol ? baseVol / SESSION_MIN * u * 0.6 * Math.exp(gauss(r) * 0.5) * (burst ? 3.5 : 1) : 0;
        m[idx++] = o1; m[idx++] = h1; m[idx++] = l1; m[idx++] = c1; m[idx++] = Math.round(v1);
        price = c1;
      }
    }
    // Rescale so the last price lands near a believable level for the symbol.
    var target = BASE_PRICE[s.sym] || (isIdx ? 20000 + hash(k) % 30000 : 80 + (hash(k + 'p') % 4200));
    var f = target / price;
    daily.forEach(function (x) { x.o *= f; x.h *= f; x.l *= f; x.c *= f; });
    for (i = 0; i < m.length; i += 5) { m[i] *= f; m[i + 1] *= f; m[i + 2] *= f; m[i + 3] *= f; }

    var g = { daily: daily, m: m, days: days.slice(OLD_DAYS), today: Math.floor((MF.clock.now() + IST) / DAY), isIdx: isIdx };
    demoCache[k] = g; demoOrder.push(k);
    if (demoOrder.length > 90) delete demoCache[demoOrder.shift()];
    return g;
  }

  /* Number of 1-minute bars of intraday day d that are visible "now". */
  function visibleMinutes(g, d) {
    var now = MF.clock.now(), nowDay = Math.floor((now + IST) / DAY);
    if (g.days[d] < nowDay) return SESSION_MIN;
    var min = Math.floor(((now + IST) % DAY) / 60) - OPEN_MIN;
    return Math.max(0, Math.min(SESSION_MIN, min + 1));
  }

  function demoIntraday(g, tfMin, fromDay) {
    var out = [];
    for (var d = Math.max(0, fromDay); d < INTRA_DAYS; d++) {
      var vis = visibleMinutes(g, d), base = d * SESSION_MIN * 5, cur = null;
      for (var mi = 0; mi < vis; mi++) {
        var p = base + mi * 5;
        if (mi % tfMin === 0) { cur = { t: g.days[d] * DAY - IST + (OPEN_MIN + mi) * 60, o: g.m[p], h: g.m[p + 1], l: g.m[p + 2], c: g.m[p + 3], v: g.m[p + 4], oi: null }; out.push(cur); }
        else { if (g.m[p + 1] > cur.h) cur.h = g.m[p + 1]; if (g.m[p + 2] < cur.l) cur.l = g.m[p + 2]; cur.c = g.m[p + 3]; cur.v += g.m[p + 4]; }
      }
    }
    return out;
  }

  function demoDaily(g) {
    var out = g.daily.slice();
    demoIntraday(g, SESSION_MIN, 0).forEach(function (c) { c.t = Math.floor((c.t + IST) / DAY) * DAY - IST; out.push(c); });
    return out;
  }

  function demoQuote(s) {
    var g = demoGen(s), d = demoDaily(g), last = d[d.length - 1], prev = d[d.length - 2];
    var r = rng(hash(key(s)) + Math.floor(MF.clock.now() / 5));
    var open = MF.clock.isMarketOpen(), ltp = last.c * (open ? 1 + (r() - 0.5) * 0.0006 : 1);
    return { ltp: ltp, prevClose: prev.c, chg: ltp - prev.c, chgPct: 100 * (ltp - prev.c) / prev.c, open: last.o, high: Math.max(last.h, ltp), low: Math.min(last.l, ltp), volume: last.v, _g: g, _r: r };
  }

  var Demo = {
    name: 'Demo',
    quotes: function (syms) { var o = {}; syms.forEach(function (s) { var q = demoQuote(s); delete q._g; delete q._r; o[key(s)] = q; }); return Promise.resolve(o); },
    quote: function (s) {
      var q = demoQuote(s), g = q._g, r = q._r; delete q._g; delete q._r;
      var m5 = demoIntraday(g, 5, INTRA_DAYS - 2), n = m5.length, mom = n > 6 ? (m5[n - 1].c / m5[n - 7].c - 1) : 0;
      var tick = q.ltp > 5000 ? 1 : q.ltp > 500 ? 0.1 : 0.05, bias = Math.max(0.25, Math.min(0.75, 0.5 + mom * 40));
      var yr = demoDaily(g).slice(-250), hi = -Infinity, lo = Infinity;
      yr.forEach(function (c) { if (c.h > hi) hi = c.h; if (c.l < lo) lo = c.l; });
      q.high52 = hi; q.low52 = lo; q.upperCircuit = q.prevClose * 1.1; q.lowerCircuit = q.prevClose * 0.9;
      if (!g.isIdx) {
        var buy = [], sell = [], tb = 0, ts = 0;
        for (var i = 0; i < 5; i++) {
          var bq = Math.round((200 + r() * 4000) * bias * 2), sq = Math.round((200 + r() * 4000) * (1 - bias) * 2);
          buy.push({ price: Math.round((q.ltp - tick * (i + 1)) / tick) * tick, quantity: bq });
          sell.push({ price: Math.round((q.ltp + tick * (i + 1)) / tick) * tick, quantity: sq });
          tb += bq; ts += sq;
        }
        q.depth = { buy: buy, sell: sell };
        q.totalBuyQty = Math.round(tb * (18 + r() * 6)); q.totalSellQty = Math.round(ts * (18 + r() * 6));
        var vsum = 0, pv = 0; demoIntraday(g, 5, INTRA_DAYS - 1).forEach(function (c) { vsum += c.v; pv += c.v * (c.h + c.l + c.c) / 3; });
        q.avgPrice = vsum ? pv / vsum : q.ltp;
      }
      return Promise.resolve(q);
    },
    candles: function (s, tfId, days) {
      var g = demoGen(s), t = tf(tfId), out, cutoff = MF.clock.now() - days * DAY;
      if (t.sec < DAY) out = demoIntraday(g, t.sec / 60, 0);
      else {
        out = demoDaily(g);
        if (t.id === '1W') {
          var w = [], cur = null, wk = null;
          out.forEach(function (c) {
            var k = Math.floor((Math.floor((c.t + IST) / DAY) + 3) / 7);
            if (k !== wk) { wk = k; cur = { t: c.t, o: c.o, h: c.h, l: c.l, c: c.c, v: c.v, oi: null }; w.push(cur); }
            else { cur.h = Math.max(cur.h, c.h); cur.l = Math.min(cur.l, c.l); cur.c = c.c; cur.v += c.v; }
          });
          out = w;
        }
      }
      return Promise.resolve(out.filter(function (c) { return c.t >= cutoff; }));
    },
    expiries: function () {
      var out = [], d = Math.floor((MF.clock.now() + IST) / DAY);
      while (out.length < 5) { if ((d + 4) % 7 === 2) out.push(MF.fmt.isoDate(d * DAY - IST + 12 * 3600)); d++; }
      return Promise.resolve(out);
    },
    optionChain: function (u, expiry) {
      var q = demoQuote(u), spot = q.ltp, step = spot > 40000 ? 100 : spot > 10000 ? 50 : spot > 2000 ? 20 : spot > 500 ? 10 : spot > 100 ? 5 : 1;
      var atm = Math.round(spot / step) * step, rows = [], r0 = rng(hash(key(u) + expiry));
      var days = Math.max(0.5, (Date.parse(expiry + 'T10:00:00Z') / 1000 - MF.clock.now()) / DAY), T = days / 365;
      var wob = rng(hash(key(u) + expiry) + Math.floor(MF.clock.now() / 60));
      var lot = (MF.instruments && MF.instruments.lotSize(u.sym)) || 50;
      function cdf(x) { var t = 1 / (1 + 0.2316419 * Math.abs(x)), d = 0.3989423 * Math.exp(-x * x / 2), p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274)))); return x > 0 ? 1 - p : p; }
      for (var i = -20; i <= 20; i++) {
        var K = atm + i * step, mny = (K - spot) / spot, iv = 0.125 + 0.9 * mny * mny * 40 * 0.02 + (mny < 0 ? -mny * 0.35 : 0);
        var sd = iv * Math.sqrt(T), d1 = (Math.log(spot / K) + 0.5 * iv * iv * T) / sd, d2 = d1 - sd;
        var call = spot * cdf(d1) - K * cdf(d2), put = call - spot + K;
        var round = (K % (step * 10) === 0) ? 2.2 : (K % (step * 2) === 0 ? 1.3 : 1);
        var shape = Math.exp(-Math.pow(i / 9, 2)), base = (4000 + r0() * 9000) * shape * round * (0.94 + wob() * 0.12);
        var ceOi = Math.round(base * (i >= 0 ? 1 : 0.35) * (0.6 + r0() * 0.8)) * lot, peOi = Math.round(base * (i <= 0 ? 1.05 : 0.33) * (0.6 + r0() * 0.8)) * lot;
        rows.push({
          strike: K,
          ce: { ltp: Math.max(0.05, call), oi: ceOi, volume: Math.round(ceOi * (1 + r0() * 6)), iv: iv * 100, delta: cdf(d1), symbol: u.sym + ' ' + K + ' CE' },
          pe: { ltp: Math.max(0.05, put), oi: peOi, volume: Math.round(peOi * (1 + r0() * 6)), iv: iv * 100, delta: cdf(d1) - 1, symbol: u.sym + ' ' + K + ' PE' }
        });
      }
      return Promise.resolve({ spot: spot, rows: rows });
    },
    test: function () { return Promise.resolve('Demo mode is active — prices are simulated.'); }
  };

  /* =====================================================================================
   * Facade with a small in-memory cache
   * ===================================================================================== */
  var cache = {};
  function cached(k, ttlSec, fn) {
    var e = cache[k], now = Date.now();
    if (e && now - e.at < ttlSec * 1000) return e.p;
    var p = fn();
    cache[k] = { at: now, p: p };
    p.catch(function () { if (cache[k] && cache[k].p === p) delete cache[k]; });
    return p;
  }
  function provider() { return settings().mode === 'groww' ? Groww : Demo; }

  MF.data = {
    TF: TF, tf: tf, key: key, settings: settings,
    saveSettings: function (s) { MF.store.set('settings', s); cache = {}; },
    isDemo: function () { return settings().mode !== 'groww'; },
    providerName: function () { return provider().name; },
    quotes: function (syms, ttl) { if (!syms.length) return Promise.resolve({}); return cached('qs|' + syms.map(key).join(','), ttl === undefined ? 4 : ttl, function () { return provider().quotes(syms); }); },
    quote: function (s, ttl) { return cached('q|' + key(s), ttl === undefined ? 3 : ttl, function () { return provider().quote(s); }); },
    candles: function (s, tfId, days, ttl) {
      var t = tf(tfId); days = Math.min(days || t.defDays, t.maxDays);
      return cached('c|' + key(s) + '|' + tfId + '|' + days, ttl === undefined ? (t.sec < DAY ? 30 : 300) : ttl, function () { return provider().candles(s, tfId, days); });
    },
    expiries: function (u) { return cached('e|' + key(u), 3600, function () { return provider().expiries(u); }); },
    optionChain: function (u, expiry, ttl) { return cached('oc|' + key(u) + '|' + expiry, ttl === undefined ? 20 : ttl, function () { return provider().optionChain(u, expiry); }); },
    test: function () { cache = {}; return provider().test(); },
    clearCache: function () { cache = {}; },
    _internal: { parseOhlc: parseOhlc, parseTs: parseTs, normCandles: normCandles, tokenFresh: tokenFresh, Groww: Groww, Demo: Demo }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = MF;
})(typeof window !== 'undefined' ? window : globalThis);
