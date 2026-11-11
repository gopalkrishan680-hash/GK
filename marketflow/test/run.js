/* Unit tests for the pure-JS modules. Run: node test/run.js */
'use strict';
const path = require('path'), W = path.join(__dirname, '../app/src/main/assets/www/js/');
globalThis.window = globalThis;
require(W + 'core.js'); require(W + 'indicators.js'); require(W + 'strategies.js'); require(W + 'backtest.js');
require(W + 'instruments-data.js'); require(W + 'instruments.js'); require(W + 'data.js'); require(W + 'analysis.js');
const MF = globalThis.MF, I = MF.ind;
let pass = 0, fail = 0;
function ok(cond, name) { if (cond) pass++; else { fail++; console.log('FAIL: ' + name); } }
function near(a, b, tol, name) { ok(Math.abs(a - b) <= (tol || 1e-9), name + ' (got ' + a + ', want ' + b + ')'); }

// --- SHA-256 known vectors
ok(MF.sha256('abc') === 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad', 'sha256 abc');
ok(MF.sha256('') === 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'sha256 empty');
ok(MF.sha256('a'.repeat(100)) === require('crypto').createHash('sha256').update('a'.repeat(100)).digest('hex'), 'sha256 multi-block');

// --- indicators
near(I.sma([1, 2, 3, 4, 5], 3)[4], 4, 1e-12, 'sma');
ok(I.sma([1, 2, 3], 3)[1] === null, 'sma warmup');
near(I.ema([1, 2, 3, 4, 5], 3)[4], 4, 1e-12, 'ema of a line');
const flat = Array.from({ length: 50 }, () => 10);
ok(I.rsi(flat.map((x, i) => x + i), 14)[49] === 100, 'rsi all gains = 100');
near(I.rsi(flat.map((x, i) => x + (i % 2)), 14)[49], 50, 4, 'rsi alternating ~50');
const bars = Array.from({ length: 300 }, (_, i) => { const c = 100 + i; return { t: 1700000000 + i * 86400, o: c - 0.5, h: c + 1, l: c - 1, c: c, v: 1000 }; });
ok(I.supertrend(bars, 10, 3).dir[299] === 1, 'supertrend up in a rising series');
near(I.atr(bars, 14)[299], 2, 1e-6, 'atr constant range');
const dc = I.donchian(bars, 20); near(dc.upper[100], bars[99].h, 1e-12, 'donchian excludes current bar');
const bb = I.bollinger(flat, 20, 2); near(bb.upper[40], 10, 1e-12, 'bollinger flat');
// causality: indicator values must not change when future bars are appended
const rnd = MF.data._internal.Demo;
MF.clock.now = () => 1794300000; // fixed clock: Tue 10 Nov 2026 ~ 13:50 IST
const sym = { ex: 'NSE', sym: 'RELIANCE', name: 'Reliance', type: 'EQ' };

(async function () {
  const daily = await rnd.candles(sym, '1D', 2200), m15 = await rnd.candles(sym, '15m', 60), m1 = await rnd.candles(sym, '1m', 3), wk = await rnd.candles(sym, '1W', 2200);
  ok(daily.length > 1400, 'demo daily length ' + daily.length);
  ok(m15.length > 500, 'demo 15m length ' + m15.length);
  ok(daily.every((c, i) => i === 0 || c.t > daily[i - 1].t), 'daily ascending');
  ok(daily.every(c => c.h >= Math.max(c.o, c.c) - 1e-6 && c.l <= Math.min(c.o, c.c) + 1e-6), 'daily OHLC consistent');
  ok(m15.every(c => { const m = I.istMinuteOfDay(c.t); return m >= 555 && m < 930; }), '15m inside session');
  ok(wk.length > 200 && wk.length < daily.length / 4, 'weekly aggregation');
  // 1m aggregates to the daily bar of the same day
  const dkey = I.istDayKey(m1[0].t), day1 = m1.filter(c => I.istDayKey(c.t) === dkey), dbar = daily.find(c => I.istDayKey(c.t) === dkey);
  near(Math.max(...day1.map(c => c.h)), dbar.h, 1e-3, '1m high matches daily high');
  near(day1.reduce((a, c) => a + c.v, 0), dbar.v, 1, '1m volume matches daily volume');

  for (const s of MF.strategies.list) {
    const src = s.intradayOnly ? m15 : daily, p = MF.strategies.defaults(s);
    const full = s.compute(src, p, { allowShort: true }).pos, cut = s.compute(src.slice(0, src.length - 150), p, { allowShort: true }).pos;
    ok(full.length === src.length, s.id + ' pos length');
    ok(cut.every((v, i) => v === full[i]), s.id + ' is causal (no look-ahead)');
    ok(full.some(v => v !== 0), s.id + ' produces positions');
    const cfg = MF.backtest.defaultsFor(s.intradayOnly ? 'intraday' : 'swing'), r = MF.backtest.runStrategy(src, s, null, cfg);
    ok(isFinite(r.metrics.returnPct) && r.equity.length === src.length, s.id + ' backtest runs');
    if (s.intradayOnly) ok(r.trades.every(t => I.istDayKey(t.entryT) === I.istDayKey(t.exitT)), s.id + ' never holds overnight');
  }

  // --- engine arithmetic on a hand-made series
  const mk = (arr) => arr.map((x, i) => ({ t: 1700000000 + i * 86400, o: x[0], h: x[1], l: x[2], c: x[3], v: 100 }));
  const c5 = mk([[100, 101, 99, 100], [100, 111, 100, 110], [110, 121, 109, 120], [120, 121, 110, 112], [112, 113, 111, 112]]);
  let r = MF.backtest.run(c5, [1, 1, 0, 0, 0], { capital: 10000, costPct: 0, slippagePct: 0, allocPct: 100 });
  ok(r.trades.length === 1 && r.trades[0].entry === 100 && r.trades[0].exit === 120, 'enters next open, exits next open');
  near(r.metrics.netProfit, 2000, 1e-9, 'pnl 100 units x 20');
  r = MF.backtest.run(c5, [1, 1, 0, 0, 0], { capital: 10000, costPct: 0.1, slippagePct: 0, allocPct: 100 });
  near(r.metrics.netProfit, 2000 - 10 - 12, 1e-9, 'costs charged both sides');
  r = MF.backtest.run(c5, [1, 1, 1, 1, 1], { capital: 10000, costPct: 0, slippagePct: 0, allocPct: 100, stopPct: 5 });
  ok(r.trades.length === 1 && r.trades[0].reason === 'open', 'stop not hit when low stays above');
  const c6 = mk([[100, 101, 99, 100], [100, 101, 94, 96], [96, 97, 95, 96], [96, 99, 96, 98]]);
  r = MF.backtest.run(c6, [1, 1, 1, 1], { capital: 10000, costPct: 0, slippagePct: 0, allocPct: 100, stopPct: 5 });
  ok(r.trades.length === 1 && r.trades[0].reason === 'stop' && Math.abs(r.trades[0].exit - 95) < 1e-9, 'stop exits at stop price and does not re-enter');
  r = MF.backtest.run(c5, [-1, -1, 0, 0, 0], { capital: 10000, costPct: 0, slippagePct: 0, allocPct: 100, allowShort: false });
  ok(r.trades.length === 0, 'shorts ignored when not allowed');
  r = MF.backtest.run(c5, [-1, -1, 0, 0, 0], { capital: 10000, costPct: 0, slippagePct: 0, allocPct: 100, allowShort: true });
  near(r.metrics.netProfit, -2000, 1e-9, 'short pnl');
  near(MF.backtest.run(c5, [0, 0, 0, 0, 0], { capital: 10000 }).metrics.buyHoldPct, 12, 1e-9, 'buy & hold');

  // --- Groww response parsing
  const X = MF.data._internal;
  ok(X.parseOhlc('{open: 149.50,high: 150.50,low: 148.50,close: 149.50}').high === 150.5, 'ohlc string');
  ok(X.parseOhlc({ open: 1, close: 2 }).close === 2, 'ohlc object');
  ok(X.parseTs('2025-09-24T10:30:00') === Date.UTC(2025, 8, 24, 5, 0, 0) / 1000, 'IST timestamp string');
  ok(X.parseTs(1633072800) === 1633072800 && X.parseTs(1633072800000) === 1633072800, 'epoch s / ms');
  ok(X.normCandles([['2025-09-24T10:30:00', 1, 2, 0.5, 1.5, 10, null], [1, null, 2, 3, 4, 5]]).length === 1, 'null candles dropped');
  ok(X.tokenFresh(MF.clock.now() - 3600) === true && X.tokenFresh(MF.clock.now() - 86400 * 2) === false, 'token expiry at 6 AM IST');

  // --- analysis
  const q = await rnd.quote(sym), m5 = await rnd.candles(sym, '5m', 5);
  const fl = MF.analysis.flow(m5, q, 1e6);
  ok(fl.score >= -100 && fl.score <= 100 && fl.parts.length >= 5, 'flow score in range');
  const ol = MF.analysis.outlook(m15, daily); ok(ol.intraday && ol.swing && ol.long, 'outlook has 3 horizons');
  const lv = MF.analysis.levels(daily); ok(lv.pivot.r1 > lv.pivot.s1, 'pivot levels');
  const ch = await rnd.optionChain({ ex: 'NSE', sym: 'NIFTY', type: 'IDX' }, (await rnd.expiries())[0]);
  const oa = MF.analysis.options(ch, null);
  ok(oa.pcr > 0 && oa.maxPain > 0 && oa.support.strike <= oa.resistance.strike + 2000, 'option analytics');
  const mp = MF.analysis.options({ spot: 100, rows: [{ strike: 90, ce: { oi: 0, ltp: 1, volume: 0 }, pe: { oi: 10, ltp: 1, volume: 0 } }, { strike: 100, ce: { oi: 5, ltp: 1, volume: 0 }, pe: { oi: 5, ltp: 1, volume: 0 } }, { strike: 110, ce: { oi: 10, ltp: 1, volume: 0 }, pe: { oi: 0, ltp: 1, volume: 0 } }] }, null);
  ok(mp.maxPain === 100 && mp.pcr === 1, 'max pain on a symmetric chain');
  const tp = MF.analysis.tradePlan(100, 2, 1, 100000, 1, 1.5, 2); ok(tp.stop === 97 && tp.target === 106 && tp.qty === 333, 'trade plan sizing');
  ok(MF.instruments.search('RELIANCE')[0].sym === 'RELIANCE' && MF.instruments.universe.length >= 45, 'instrument search + universe (' + MF.instruments.universe.length + ')');
  ok(MF.instruments.lotSize('NIFTY') > 0, 'lot size');

  console.log(pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
