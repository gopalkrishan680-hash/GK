/* Strategy library.
 * Each strategy's compute(candles, params, opts) returns { pos: [], lines: [] } where pos[i] is the position the
 * strategy wants to hold AFTER seeing the close of bar i: 1 = long, -1 = short, 0 = flat.
 * The backtest engine executes changes at the NEXT bar's open, so no strategy can see the future.
 * opts.allowShort enables the mirrored short side.
 */
(function (root) {
  'use strict';
  var MF = root.MF = root.MF || {};
  if (typeof module !== 'undefined' && module.exports && !MF.ind) require('./indicators.js');
  var I = MF.ind;

  function P(key, label, def, min, max, step) { return { key: key, label: label, def: def, min: min, max: max, step: step || 1 }; }
  function ok() { for (var i = 0; i < arguments.length; i++) if (arguments[i] === null || arguments[i] === undefined) return false; return true; }

  var list = [];

  list.push({
    id: 'ema_cross', name: 'EMA Crossover', styles: ['intraday', 'swing'],
    summary: 'Buy when the fast EMA crosses above the slow EMA; exit (or short) on the opposite cross.',
    logic: 'Trend following. Works in trending markets, gives small repeated losses in sideways markets.',
    params: [P('fast', 'Fast EMA', 9, 3, 50), P('slow', 'Slow EMA', 21, 10, 200)],
    compute: function (c, p, o) {
      var cl = I.col(c, 'c'), f = I.ema(cl, p.fast), s = I.ema(cl, p.slow), pos = [];
      for (var i = 0; i < c.length; i++) pos.push(!ok(f[i], s[i]) ? 0 : f[i] > s[i] ? 1 : (o.allowShort && f[i] < s[i] ? -1 : 0));
      return { pos: pos, lines: [{ name: 'EMA ' + p.fast, color: '#f5a524', data: f }, { name: 'EMA ' + p.slow, color: '#4c9aff', data: s }] };
    }
  });

  list.push({
    id: 'supertrend', name: 'Supertrend', styles: ['intraday', 'swing'],
    summary: 'Long while price is above the Supertrend line, exit (or short) when it flips below.',
    logic: 'ATR based trailing trend filter. Popular on NIFTY / BANKNIFTY intraday and positional charts.',
    params: [P('period', 'ATR period', 10, 5, 30), P('mult', 'Multiplier', 3, 1, 6, 0.5)],
    compute: function (c, p, o) {
      var st = I.supertrend(c, p.period, p.mult), pos = [];
      for (var i = 0; i < c.length; i++) pos.push(st.dir[i] === null ? 0 : st.dir[i] === 1 ? 1 : (o.allowShort ? -1 : 0));
      return { pos: pos, lines: [{ name: 'Supertrend', color: '#b388ff', data: st.line }] };
    }
  });

  list.push({
    id: 'rsi2', name: 'RSI(2) Dip Buying', styles: ['swing'],
    summary: 'In an up-trend (above the 200 SMA) buy sharp 2-3 day dips (RSI-2 below 10) and sell on the bounce above the 5 SMA.',
    logic: 'Mean reversion inside a long-term up-trend. Many small wins, needs discipline when a dip keeps falling.',
    params: [P('trend', 'Trend SMA', 200, 50, 250, 10), P('entry', 'RSI entry below', 10, 2, 30), P('exitMa', 'Exit SMA', 5, 3, 20)],
    compute: function (c, p, o) {
      var cl = I.col(c, 'c'), t = I.sma(cl, p.trend), r = I.rsi(cl, 2), x = I.sma(cl, p.exitMa), pos = [], cur = 0;
      for (var i = 0; i < c.length; i++) {
        if (!ok(t[i], r[i], x[i])) { pos.push(0); continue; }
        if (cur === 1 && cl[i] > x[i]) cur = 0;
        else if (cur === -1 && cl[i] < x[i]) cur = 0;
        if (cur === 0) {
          if (cl[i] > t[i] && r[i] < p.entry) cur = 1;
          else if (o.allowShort && cl[i] < t[i] && r[i] > 100 - p.entry) cur = -1;
        }
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'SMA ' + p.trend, color: '#4c9aff', data: t }, { name: 'SMA ' + p.exitMa, color: '#f5a524', data: x }] };
    }
  });

  list.push({
    id: 'macd_trend', name: 'MACD + Trend Filter', styles: ['swing', 'long'],
    summary: 'Buy when MACD crosses above its signal line while price is above the trend EMA; exit on the cross back down.',
    logic: 'Momentum entries only in the direction of the bigger trend, which removes many false MACD signals.',
    params: [P('fast', 'MACD fast', 12, 5, 20), P('slow', 'MACD slow', 26, 15, 60), P('signal', 'Signal', 9, 3, 20), P('trend', 'Trend EMA', 200, 50, 250, 10)],
    compute: function (c, p, o) {
      var cl = I.col(c, 'c'), m = I.macd(cl, p.fast, p.slow, p.signal), t = I.ema(cl, p.trend), pos = [], cur = 0;
      for (var i = 0; i < c.length; i++) {
        if (!ok(m.line[i], m.signal[i], t[i])) { pos.push(0); continue; }
        var up = m.line[i] > m.signal[i];
        if (cur === 1 && !up) cur = 0;
        if (cur === -1 && up) cur = 0;
        if (cur === 0) {
          if (up && cl[i] > t[i]) cur = 1;
          else if (o.allowShort && !up && cl[i] < t[i]) cur = -1;
        }
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'EMA ' + p.trend, color: '#4c9aff', data: t }] };
    }
  });

  list.push({
    id: 'bb_reversion', name: 'Bollinger Band Reversion', styles: ['intraday', 'swing'],
    summary: 'Buy a close below the lower band while the long-term trend is up; exit at the middle band.',
    logic: 'Mean reversion. Buys oversold stretches in healthy stocks; a trend filter avoids catching falling knives.',
    params: [P('period', 'Band period', 20, 10, 50), P('dev', 'Std deviations', 2, 1, 3, 0.25), P('trend', 'Trend SMA', 200, 50, 250, 10)],
    compute: function (c, p, o) {
      var cl = I.col(c, 'c'), b = I.bollinger(cl, p.period, p.dev), t = I.sma(cl, p.trend), pos = [], cur = 0;
      for (var i = 0; i < c.length; i++) {
        if (!ok(b.lower[i], t[i])) { pos.push(0); continue; }
        if (cur === 1 && cl[i] >= b.mid[i]) cur = 0;
        if (cur === -1 && cl[i] <= b.mid[i]) cur = 0;
        if (cur === 0) {
          if (cl[i] < b.lower[i] && cl[i] > t[i]) cur = 1;
          else if (o.allowShort && cl[i] > b.upper[i] && cl[i] < t[i]) cur = -1;
        }
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'BB upper', color: '#6b7a90', data: b.upper }, { name: 'BB mid', color: '#f5a524', data: b.mid }, { name: 'BB lower', color: '#6b7a90', data: b.lower }] };
    }
  });

  list.push({
    id: 'donchian', name: 'Donchian Breakout (Turtle)', styles: ['swing', 'long'],
    summary: 'Buy a close above the highest high of the last N bars; exit on a close below the lowest low of the last M bars.',
    logic: 'Classic breakout trend following. Few trades, low win rate, but winners can be large.',
    params: [P('entry', 'Entry channel', 20, 10, 100), P('exit', 'Exit channel', 10, 5, 50)],
    compute: function (c, p, o) {
      var e = I.donchian(c, p.entry), x = I.donchian(c, p.exit), pos = [], cur = 0;
      for (var i = 0; i < c.length; i++) {
        if (!ok(e.upper[i], x.lower[i])) { pos.push(0); continue; }
        if (cur === 1 && c[i].c < x.lower[i]) cur = 0;
        if (cur === -1 && c[i].c > x.upper[i]) cur = 0;
        if (cur === 0) {
          if (c[i].c > e.upper[i]) cur = 1;
          else if (o.allowShort && c[i].c < e.lower[i]) cur = -1;
        }
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'High ' + p.entry, color: '#22c55e', data: e.upper }, { name: 'Low ' + p.exit, color: '#ef4444', data: x.lower }] };
    }
  });

  list.push({
    id: 'volume_breakout', name: 'Volume Breakout', styles: ['intraday', 'swing'],
    summary: 'Buy when price closes above its recent high on unusually heavy volume; trail the stop with ATR.',
    logic: 'Follows strong buying: a breakout backed by 2x normal volume shows large participants are active.',
    params: [P('lookback', 'Breakout lookback', 20, 10, 60), P('volMult', 'Volume x average', 2, 1.2, 5, 0.1), P('atrMult', 'ATR trail multiple', 3, 1.5, 6, 0.5)],
    needsVolume: true,
    compute: function (c, p, o) {
      var d = I.donchian(c, p.lookback), vol = I.col(c, 'v'), va = I.sma(vol, p.lookback), a = I.atr(c, 14), pos = [], cur = 0, trail = null;
      var tl = new Array(c.length).fill(null);
      for (var i = 0; i < c.length; i++) {
        if (!ok(d.upper[i], va[i - 1], a[i])) { pos.push(0); continue; }
        var heavy = va[i - 1] > 0 && vol[i] > p.volMult * va[i - 1];
        if (cur === 1) { trail = Math.max(trail, c[i].c - p.atrMult * a[i]); if (c[i].c < trail) cur = 0; }
        else if (cur === -1) { trail = Math.min(trail, c[i].c + p.atrMult * a[i]); if (c[i].c > trail) cur = 0; }
        if (cur === 0) {
          if (c[i].c > d.upper[i] && heavy) { cur = 1; trail = c[i].c - p.atrMult * a[i]; }
          else if (o.allowShort && c[i].c < d.lower[i] && heavy) { cur = -1; trail = c[i].c + p.atrMult * a[i]; }
        }
        if (cur !== 0) tl[i] = trail;
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'ATR trail', color: '#ef4444', data: tl }] };
    }
  });

  list.push({
    id: 'rsi_pullback', name: 'RSI Pullback in Up-trend', styles: ['swing'],
    summary: 'In an up-trend, buy when RSI(14) recovers back above 40 after a pullback; exit when RSI is overbought or the trend breaks.',
    logic: 'Buys the dip only after it starts turning up, so entries are later but safer than plain dip buying.',
    params: [P('trend', 'Trend SMA', 200, 50, 250, 10), P('low', 'RSI pullback level', 40, 20, 50), P('high', 'RSI exit level', 70, 55, 85)],
    compute: function (c, p, o) {
      var cl = I.col(c, 'c'), t = I.sma(cl, p.trend), r = I.rsi(cl, 14), pos = [], cur = 0;
      for (var i = 0; i < c.length; i++) {
        if (i === 0 || !ok(t[i], r[i], r[i - 1])) { pos.push(0); continue; }
        if (cur === 1 && (r[i] > p.high || cl[i] < t[i])) cur = 0;
        if (cur === -1 && (r[i] < 100 - p.high || cl[i] > t[i])) cur = 0;
        if (cur === 0) {
          if (cl[i] > t[i] && r[i - 1] < p.low && r[i] >= p.low) cur = 1;
          else if (o.allowShort && cl[i] < t[i] && r[i - 1] > 100 - p.low && r[i] <= 100 - p.low) cur = -1;
        }
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'SMA ' + p.trend, color: '#4c9aff', data: t }] };
    }
  });

  list.push({
    id: 'orb', name: 'Opening Range Breakout', styles: ['intraday'], intradayOnly: true,
    summary: 'Mark the high and low of the first minutes of the session. Go long on a close above the range, short on a close below; exit if price returns to the middle of the range.',
    logic: 'The first 15-30 minutes set the tone for the day. One trade per direction per day, always squared off before close.',
    params: [P('minutes', 'Opening range (min)', 15, 5, 60, 5)],
    compute: function (c, p, o) {
      var pos = [], day = null, hi = 0, lo = 0, cur = 0, longDone = false, shortDone = false, start = 0;
      var hl = new Array(c.length).fill(null), ll = new Array(c.length).fill(null);
      for (var i = 0; i < c.length; i++) {
        var d = I.istDayKey(c[i].t), m = I.istMinuteOfDay(c[i].t);
        if (d !== day) { day = d; hi = -Infinity; lo = Infinity; cur = 0; longDone = false; shortDone = false; start = m; }
        if (m < start + p.minutes) { hi = Math.max(hi, c[i].h); lo = Math.min(lo, c[i].l); pos.push(0); continue; }
        var mid = (hi + lo) / 2;
        hl[i] = hi; ll[i] = lo;
        if (cur === 1 && c[i].c < mid) cur = 0;
        if (cur === -1 && c[i].c > mid) cur = 0;
        if (cur === 0) {
          if (!longDone && c[i].c > hi) { cur = 1; longDone = true; }
          else if (o.allowShort && !shortDone && c[i].c < lo) { cur = -1; shortDone = true; }
        }
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'Range high', color: '#22c55e', data: hl }, { name: 'Range low', color: '#ef4444', data: ll }] };
    }
  });

  list.push({
    id: 'vwap_trend', name: 'VWAP Trend', styles: ['intraday'], intradayOnly: true,
    summary: 'Long while price holds above the session VWAP with the fast EMA above the slow EMA; exit when price closes back below VWAP.',
    logic: 'VWAP is the average price institutions paid today. Above it buyers are in control, below it sellers are.',
    params: [P('fast', 'Fast EMA', 9, 3, 30), P('slow', 'Slow EMA', 21, 10, 60)],
    compute: function (c, p, o) {
      var cl = I.col(c, 'c'), v = I.vwap(c), f = I.ema(cl, p.fast), s = I.ema(cl, p.slow), pos = [], cur = 0, day = null, n = 0;
      for (var i = 0; i < c.length; i++) {
        var d = I.istDayKey(c[i].t);
        if (d !== day) { day = d; cur = 0; n = 0; }
        n++;
        if (!ok(v[i], f[i], s[i]) || n < 3) { pos.push(0); continue; }
        if (cur === 1 && cl[i] < v[i]) cur = 0;
        if (cur === -1 && cl[i] > v[i]) cur = 0;
        if (cur === 0) {
          if (cl[i] > v[i] && f[i] > s[i]) cur = 1;
          else if (o.allowShort && cl[i] < v[i] && f[i] < s[i]) cur = -1;
        }
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'VWAP', color: '#f5a524', data: v }] };
    }
  });

  list.push({
    id: 'golden_cross', name: 'Golden Cross (50/200)', styles: ['long'],
    summary: 'Hold while the 50-day SMA is above the 200-day SMA; move to cash on the death cross.',
    logic: 'Very slow trend filter. Its job is to stay invested in bull markets and step aside in long bear markets.',
    params: [P('fast', 'Fast SMA', 50, 20, 100, 5), P('slow', 'Slow SMA', 200, 100, 300, 10)],
    compute: function (c, p, o) {
      var cl = I.col(c, 'c'), f = I.sma(cl, p.fast), s = I.sma(cl, p.slow), pos = [];
      for (var i = 0; i < c.length; i++) pos.push(!ok(f[i], s[i]) ? 0 : f[i] > s[i] ? 1 : 0);
      return { pos: pos, lines: [{ name: 'SMA ' + p.fast, color: '#f5a524', data: f }, { name: 'SMA ' + p.slow, color: '#4c9aff', data: s }] };
    }
  });

  list.push({
    id: 'trend_200', name: '200-Day Trend Following', styles: ['long'],
    summary: 'Hold while price is above its 200-day SMA; exit when it closes a set percentage below.',
    logic: 'The simplest long-term rule: own it in an up-trend, hold cash otherwise. A small buffer reduces whipsaws.',
    params: [P('period', 'SMA period', 200, 100, 300, 10), P('buffer', 'Exit buffer %', 2, 0, 5, 0.5)],
    compute: function (c, p, o) {
      var cl = I.col(c, 'c'), s = I.sma(cl, p.period), pos = [], cur = 0;
      for (var i = 0; i < c.length; i++) {
        if (!ok(s[i])) { pos.push(0); continue; }
        if (cur === 0 && cl[i] > s[i]) cur = 1;
        else if (cur === 1 && cl[i] < s[i] * (1 - p.buffer / 100)) cur = 0;
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'SMA ' + p.period, color: '#4c9aff', data: s }] };
    }
  });

  list.push({
    id: 'high_breakout', name: '52-Week High Momentum', styles: ['swing', 'long'],
    summary: 'Buy a close at a new 52-week high; hold until price closes below an ATR trailing stop.',
    logic: 'Stocks making new highs tend to keep leading. The trailing stop lets winners run and cuts failures.',
    params: [P('lookback', 'High lookback (bars)', 250, 60, 300, 10), P('atrMult', 'ATR trail multiple', 4, 2, 8, 0.5)],
    compute: function (c, p, o) {
      var d = I.donchian(c, p.lookback), a = I.atr(c, 20), pos = [], cur = 0, trail = null, tl = new Array(c.length).fill(null);
      for (var i = 0; i < c.length; i++) {
        if (!ok(d.upper[i], a[i])) { pos.push(0); continue; }
        if (cur === 1) { trail = Math.max(trail, c[i].c - p.atrMult * a[i]); if (c[i].c < trail) cur = 0; }
        if (cur === 0 && c[i].c > d.upper[i]) { cur = 1; trail = c[i].c - p.atrMult * a[i]; }
        if (cur === 1) tl[i] = trail;
        pos.push(cur);
      }
      return { pos: pos, lines: [{ name: 'ATR trail', color: '#ef4444', data: tl }] };
    }
  });

  function byId(id) { for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i]; return null; }
  function defaults(s) { var o = {}; s.params.forEach(function (p) { o[p.key] = p.def; }); return o; }

  MF.strategies = { list: list, byId: byId, defaults: defaults };
  if (typeof module !== 'undefined' && module.exports) module.exports = MF;
})(typeof window !== 'undefined' ? window : globalThis);
