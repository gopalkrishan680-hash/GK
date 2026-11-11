/* Market analysis: buying / selling pressure, trend verdicts, key levels and option-chain analytics. */
(function (root) {
  'use strict';
  var MF = root.MF = root.MF || {};
  if (typeof module !== 'undefined' && module.exports && !MF.ind) require('./indicators.js');
  var I = MF.ind;

  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function last(a) { return a[a.length - 1]; }
  function flowLabel(score) {
    if (score >= 45) return 'Strong Buying';
    if (score >= 15) return 'Buying';
    if (score <= -45) return 'Strong Selling';
    if (score <= -15) return 'Selling';
    return 'Neutral';
  }

  /* Buying / selling pressure from intraday candles (today) and an optional live quote.
   * Returns score in [-100, 100]: positive = buyers in control, negative = sellers. */
  function flow(intraday, quote, dailyAvgVol) {
    var parts = [], n = intraday.length;
    if (n < 5) return { score: 0, label: 'Neutral', parts: [], note: 'Not enough data yet' };
    var lastDay = I.istDayKey(intraday[n - 1].t), today = intraday.filter(function (c) { return I.istDayKey(c.t) === lastDay; });
    var hasVol = today.some(function (c) { return c.v > 0; });
    var lc = last(today).c;
    function add(name, value, weight, text) { parts.push({ name: name, value: clamp(value, -1, 1), weight: weight, text: text }); }

    // 1. Price versus session VWAP
    if (hasVol) {
      var vw = last(I.vwap(today)), dv = 100 * (lc - vw) / vw;
      add('Price vs VWAP', dv / 0.8, 22, 'Price is ' + MF.fmt.pct(dv) + (dv >= 0 ? ' above' : ' below') + ' today\'s VWAP (' + MF.fmt.price(vw) + ')');
    }
    // 2. Volume traded on rising bars versus falling bars
    if (hasVol) {
      var up = 0, dn = 0;
      today.forEach(function (c) { if (c.c > c.o) up += c.v; else if (c.c < c.o) dn += c.v; });
      var share = up + dn > 0 ? up / (up + dn) : 0.5;
      add('Up vs down volume', (share - 0.5) * 5, 22, Math.round(share * 100) + '% of today\'s volume traded on rising candles');
    }
    // 3. Chaikin money flow (where bars close inside their range, weighted by volume)
    if (hasVol && today.length >= 5) {
      var cm = last(I.cmf(today, Math.min(20, today.length)));
      add('Money flow (CMF)', cm * 4, 16, 'Chaikin Money Flow is ' + MF.fmt.signed(cm, 2) + (cm > 0.05 ? ' — closes near highs (accumulation)' : cm < -0.05 ? ' — closes near lows (distribution)' : ' — balanced'));
    }
    // 4. Position inside the day's range
    var hi = -Infinity, lo = Infinity;
    today.forEach(function (c) { if (c.h > hi) hi = c.h; if (c.l < lo) lo = c.l; });
    if (hi > lo) {
      var loc = (lc - lo) / (hi - lo);
      add('Day range position', (loc - 0.5) * 2, 14, 'Trading at ' + Math.round(loc * 100) + '% of today\'s range (0% = day low, 100% = day high)');
    }
    // 5. Day change
    if (quote && quote.chgPct !== undefined && quote.chgPct !== null) add('Day change', quote.chgPct / 1.5, 12, 'Day change ' + MF.fmt.pct(quote.chgPct));
    // 6. Pending order book
    if (quote && quote.totalBuyQty && quote.totalSellQty) {
      var imb = (quote.totalBuyQty - quote.totalSellQty) / (quote.totalBuyQty + quote.totalSellQty);
      add('Pending orders', imb * 2.5, 14, 'Pending buy quantity ' + MF.fmt.compact(quote.totalBuyQty) + ' vs sell ' + MF.fmt.compact(quote.totalSellQty));
    }
    var s = 0, w = 0;
    parts.forEach(function (p) { s += p.value * p.weight; w += p.weight; });
    var score = w ? Math.round(100 * s / w) : 0;

    var rvol = null;
    if (hasVol && dailyAvgVol) {
      var tv = 0; today.forEach(function (c) { tv += c.v; });
      var elapsed = clamp((last(today).t - today[0].t) / 60 / 375 + 0.04, 0.04, 1);
      rvol = tv / (dailyAvgVol * elapsed);
    }
    return { score: score, label: flowLabel(score), parts: parts, rvol: rvol, hasVolume: hasVol };
  }

  /* Bars where volume is far above normal: the visible footprint of large orders. */
  function volumeEvents(c, mult, lookback) {
    mult = mult || 3; lookback = lookback || 20;
    var vol = I.col(c, 'v'), avg = I.sma(vol, lookback), out = [];
    for (var i = lookback; i < c.length; i++) {
      if (!avg[i - 1] || vol[i] < mult * avg[i - 1]) continue;
      var chg = 100 * (c[i].c - c[i].o) / c[i].o, range = c[i].h - c[i].l, loc = range > 0 ? (c[i].c - c[i].l) / range : 0.5;
      var side = chg > 0 && loc > 0.55 ? 'buy' : chg < 0 && loc < 0.45 ? 'sell' : 'mixed';
      out.push({ idx: i, t: c[i].t, ratio: vol[i] / avg[i - 1], chgPct: chg, side: side, price: c[i].c, volume: vol[i] });
    }
    return out;
  }

  function verdict(score) { return score >= 40 ? 'Bullish' : score >= 15 ? 'Mildly Bullish' : score <= -40 ? 'Bearish' : score <= -15 ? 'Mildly Bearish' : 'Neutral'; }
  function tally(checks) {
    var s = 0, w = 0;
    checks.forEach(function (c) { if (c.dir === null) return; s += c.dir * c.weight; w += c.weight; });
    var score = w ? Math.round(100 * s / w) : 0;
    return { score: score, verdict: verdict(score), checks: checks };
  }
  function chk(name, dir, weight, text) { return { name: name, dir: dir, weight: weight, text: text }; }

  /* Three independent readings: intraday (intraday candles), swing and long-term (daily candles). */
  function outlook(intraday, daily) {
    var out = {};
    if (intraday && intraday.length > 30) {
      var cl = I.col(intraday, 'c'), n = cl.length, e9 = I.ema(cl, 9), e21 = I.ema(cl, 21), st = I.supertrend(intraday, 10, 3), r = I.rsi(cl, 14), vw = I.vwap(intraday);
      var hasVol = intraday.some(function (c) { return c.v > 0; });
      out.intraday = tally([
        chk('VWAP', hasVol ? (cl[n - 1] > vw[n - 1] ? 1 : -1) : null, 3, 'Price ' + (cl[n - 1] > vw[n - 1] ? 'above' : 'below') + ' VWAP'),
        chk('EMA 9/21', e9[n - 1] > e21[n - 1] ? 1 : -1, 2, 'EMA 9 ' + (e9[n - 1] > e21[n - 1] ? 'above' : 'below') + ' EMA 21'),
        chk('Supertrend', st.dir[n - 1], 3, 'Supertrend (10,3) is ' + (st.dir[n - 1] === 1 ? 'green' : 'red')),
        chk('RSI 14', r[n - 1] > 55 ? 1 : r[n - 1] < 45 ? -1 : 0, 2, 'RSI ' + MF.fmt.num(r[n - 1], 0) + (r[n - 1] > 70 ? ' (overbought)' : r[n - 1] < 30 ? ' (oversold)' : ''))
      ]);
    }
    if (daily && daily.length > 60) {
      var dc = I.col(daily, 'c'), m = dc.length, e20 = I.ema(dc, 20), e50 = I.ema(dc, 50), mac = I.macd(dc, 12, 26, 9), rs = I.rsi(dc, 14), sd = I.supertrend(daily, 10, 3), ax = I.adx(daily, 14);
      out.swing = tally([
        chk('EMA 20', dc[m - 1] > e20[m - 1] ? 1 : -1, 2, 'Price ' + (dc[m - 1] > e20[m - 1] ? 'above' : 'below') + ' 20-day EMA'),
        chk('EMA 20/50', e20[m - 1] > e50[m - 1] ? 1 : -1, 2, '20-day EMA ' + (e20[m - 1] > e50[m - 1] ? 'above' : 'below') + ' 50-day EMA'),
        chk('MACD', mac.hist[m - 1] > 0 ? 1 : -1, 2, 'MACD ' + (mac.hist[m - 1] > 0 ? 'above' : 'below') + ' its signal line'),
        chk('Supertrend', sd.dir[m - 1], 3, 'Daily Supertrend is ' + (sd.dir[m - 1] === 1 ? 'green' : 'red')),
        chk('RSI 14', rs[m - 1] > 55 ? 1 : rs[m - 1] < 45 ? -1 : 0, 1, 'Daily RSI ' + MF.fmt.num(rs[m - 1], 0)),
        chk('Trend strength', 0, 0, 'ADX ' + MF.fmt.num(ax.adx[m - 1], 0) + (ax.adx[m - 1] > 25 ? ' — trending' : ' — weak / sideways trend'))
      ]);
      if (daily.length > 210) {
        var s50 = I.sma(dc, 50), s200 = I.sma(dc, 200), yr = daily.slice(-250), hi = -Infinity, lo = Infinity;
        yr.forEach(function (c) { if (c.h > hi) hi = c.h; if (c.l < lo) lo = c.l; });
        var p52 = (dc[m - 1] - lo) / (hi - lo), ret6 = m > 126 ? 100 * (dc[m - 1] / dc[m - 127] - 1) : null;
        out.long = tally([
          chk('200-day SMA', dc[m - 1] > s200[m - 1] ? 1 : -1, 3, 'Price ' + (dc[m - 1] > s200[m - 1] ? 'above' : 'below') + ' 200-day SMA (' + MF.fmt.price(s200[m - 1]) + ')'),
          chk('50/200 SMA', s50[m - 1] > s200[m - 1] ? 1 : -1, 3, s50[m - 1] > s200[m - 1] ? 'Golden cross in force (50 above 200)' : 'Death cross in force (50 below 200)'),
          chk('52-week range', p52 > 0.7 ? 1 : p52 < 0.3 ? -1 : 0, 2, 'At ' + Math.round(p52 * 100) + '% of its 52-week range'),
          chk('6-month return', ret6 === null ? null : ret6 > 5 ? 1 : ret6 < -5 ? -1 : 0, 2, '6-month return ' + MF.fmt.pct(ret6))
        ]);
      }
    }
    return out;
  }

  /* Support / resistance from the previous session plus recent swing extremes. */
  function levels(daily) {
    if (!daily || daily.length < 3) return null;
    var n = daily.length, prev = daily[n - 2], pv = I.pivots(prev.h, prev.l, prev.c), w = daily.slice(-21, -1), hi = -Infinity, lo = Infinity;
    w.forEach(function (c) { if (c.h > hi) hi = c.h; if (c.l < lo) lo = c.l; });
    return { pivot: pv, prevHigh: prev.h, prevLow: prev.l, prevClose: prev.c, high20: hi, low20: lo, atr: last(I.atr(daily, 14)) };
  }

  /* Risk-based position size. */
  function tradePlan(entry, atr, dir, capital, riskPct, atrStop, rr) {
    var stopDist = atr * atrStop, stop = entry - dir * stopDist, target = entry + dir * stopDist * rr;
    var risk = capital * riskPct / 100, qty = Math.floor(risk / stopDist), maxQty = Math.floor(capital / entry);
    return { entry: entry, stop: stop, target: target, qty: Math.max(0, Math.min(qty, maxQty)), riskAmount: Math.min(qty, maxQty) * stopDist, capped: qty > maxQty, stopPct: 100 * stopDist / entry };
  }

  /* ---------- Option chain ---------- */
  function options(chain, prev) {
    var rows = chain.rows, spot = chain.spot, ceOi = 0, peOi = 0, ceVol = 0, peVol = 0, maxCe = null, maxPe = null, atm = null;
    rows.forEach(function (r) {
      var c = r.ce ? r.ce.oi : 0, p = r.pe ? r.pe.oi : 0;
      ceOi += c; peOi += p; ceVol += r.ce ? r.ce.volume : 0; peVol += r.pe ? r.pe.volume : 0;
      if (!maxCe || c > maxCe.oi) maxCe = { strike: r.strike, oi: c };
      if (!maxPe || p > maxPe.oi) maxPe = { strike: r.strike, oi: p };
      if (!atm || Math.abs(r.strike - spot) < Math.abs(atm.strike - spot)) atm = r;
    });
    // Max pain: the expiry price at which option buyers collectively lose the most
    var best = null;
    rows.forEach(function (x) {
      var pain = 0;
      rows.forEach(function (r) {
        if (r.ce && x.strike > r.strike) pain += (x.strike - r.strike) * r.ce.oi;
        if (r.pe && x.strike < r.strike) pain += (r.strike - x.strike) * r.pe.oi;
      });
      if (!best || pain < best.pain) best = { strike: x.strike, pain: pain };
    });
    var pcr = ceOi ? peOi / ceOi : null;
    var straddle = atm && atm.ce && atm.pe ? atm.ce.ltp + atm.pe.ltp : null;

    // OI change versus an earlier snapshot of the same chain
    var change = null;
    if (prev && prev.rows) {
      var map = {}, dCe = 0, dPe = 0, list = [];
      prev.rows.forEach(function (r) { map[r.strike] = r; });
      rows.forEach(function (r) {
        var o = map[r.strike]; if (!o) return;
        var a = (r.ce ? r.ce.oi : 0) - (o.ce || 0), b = (r.pe ? r.pe.oi : 0) - (o.pe || 0);
        dCe += a; dPe += b; list.push({ strike: r.strike, ce: a, pe: b });
      });
      change = { ce: dCe, pe: dPe, rows: list, since: prev.t };
    }

    var notes = [];
    if (pcr !== null) notes.push({ dir: pcr > 1.2 ? 1 : pcr < 0.8 ? -1 : 0, text: 'Put-Call Ratio (OI) is ' + MF.fmt.num(pcr, 2) + (pcr > 1.2 ? ' — more puts written than calls; put writers expect support to hold (bullish lean).' : pcr < 0.8 ? ' — more calls written than puts; call writers expect a ceiling (bearish lean).' : ' — balanced positioning.') });
    if (maxPe && maxCe) notes.push({ dir: 0, text: 'Largest put OI at ' + MF.fmt.num(maxPe.strike, 0) + ' (support) and largest call OI at ' + MF.fmt.num(maxCe.strike, 0) + ' (resistance).' });
    if (best) notes.push({ dir: best.strike > spot * 1.003 ? 1 : best.strike < spot * 0.997 ? -1 : 0, text: 'Max pain is ' + MF.fmt.num(best.strike, 0) + ' — ' + (Math.abs(best.strike - spot) / spot < 0.003 ? 'spot is already there.' : 'spot is ' + MF.fmt.num(Math.abs(spot - best.strike), 0) + ' points ' + (spot > best.strike ? 'above' : 'below') + ' it.') });
    if (change && (change.ce !== 0 || change.pe !== 0)) {
      var d = change.pe - change.ce;
      notes.push({ dir: d > 0 ? 1 : d < 0 ? -1 : 0, text: 'Since ' + MF.fmt.time(change.since) + ': put OI ' + MF.fmt.signed(change.pe, 0) + ', call OI ' + MF.fmt.signed(change.ce, 0) + (d > 0 ? ' — fresh put writing is stronger (supportive).' : d < 0 ? ' — fresh call writing is stronger (capping).' : '.') });
    }
    if (straddle) notes.push({ dir: 0, text: 'ATM straddle costs ' + MF.fmt.price(straddle) + ', so the market is pricing a move of about ±' + MF.fmt.num(100 * straddle / spot, 2) + '% by expiry.' });
    var s = 0; notes.forEach(function (x) { s += x.dir; });

    return { spot: spot, pcr: pcr, pcrVolume: ceVol ? peVol / ceVol : null, ceOi: ceOi, peOi: peOi, support: maxPe, resistance: maxCe, maxPain: best && best.strike, atm: atm, straddle: straddle, change: change, notes: notes, bias: s > 0 ? 'Bullish lean' : s < 0 ? 'Bearish lean' : 'Neutral' };
  }

  function snapshot(chain) { return { t: MF.clock.now(), spot: chain.spot, rows: chain.rows.map(function (r) { return { strike: r.strike, ce: r.ce ? r.ce.oi : 0, pe: r.pe ? r.pe.oi : 0 }; }) }; }

  MF.analysis = { flow: flow, flowLabel: flowLabel, volumeEvents: volumeEvents, outlook: outlook, levels: levels, tradePlan: tradePlan, options: options, snapshot: snapshot, verdict: verdict };
  if (typeof module !== 'undefined' && module.exports) module.exports = MF;
})(typeof window !== 'undefined' ? window : globalThis);
