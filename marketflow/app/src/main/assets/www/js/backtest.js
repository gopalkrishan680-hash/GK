/* Backtest engine.
 * Signals are read at the close of bar i and executed at the open of bar i+1 (no look-ahead).
 * Costs and slippage are charged on both entry and exit.
 */
(function (root) {
  'use strict';
  var MF = root.MF = root.MF || {};
  if (typeof module !== 'undefined' && module.exports && !MF.strategies) require('./strategies.js');
  var I = MF.ind;

  var SQUARE_OFF_MIN = 15 * 60 + 15; // 15:15 IST
  var NO_ENTRY_MIN = 15 * 60;        // no fresh intraday entries after 15:00 IST

  function intervalSeconds(c) {
    if (c.length < 3) return 86400;
    var d = [];
    for (var i = 1; i < Math.min(c.length, 200); i++) d.push(c[i].t - c[i - 1].t);
    d.sort(function (a, b) { return a - b; });
    return d[Math.floor(d.length / 2)] || 86400;
  }

  function defaultsFor(style) {
    if (style === 'intraday') return { capital: 100000, costPct: 0.03, slippagePct: 0.02, intraday: true, allowShort: true, stopPct: 0, targetPct: 0, allocPct: 100 };
    return { capital: 100000, costPct: 0.12, slippagePct: 0.05, intraday: false, allowShort: false, stopPct: 0, targetPct: 0, allocPct: 100 };
  }

  function run(c, pos, cfg) {
    var n = c.length, iv = intervalSeconds(c), ivMin = iv / 60, isIntra = iv < 86400;
    var intraday = !!cfg.intraday && isIntra;
    var cost = (cfg.costPct || 0) / 100, slip = (cfg.slippagePct || 0) / 100, alloc = (cfg.allocPct || 100) / 100;
    var bal = cfg.capital, dir = 0, units = 0, entryPx = 0, entryIdx = -1, entryCost = 0;
    var pending = 0, blockDir = 0, trades = [], equity = [], barsIn = 0, fractional = false;

    function close(px, i, reason) {
      var exitPx = px * (1 - slip * dir);
      var gross = (exitPx - entryPx) * units * dir, exitCost = exitPx * units * cost;
      bal += gross - exitCost;
      var net = gross - exitCost - entryCost;
      trades.push({
        dir: dir, entryIdx: entryIdx, exitIdx: i, entryT: c[entryIdx].t, exitT: c[i].t, entry: entryPx, exit: exitPx,
        units: units, pnl: net, pnlPct: 100 * net / (entryPx * units), bars: i - entryIdx, reason: reason
      });
      dir = 0; units = 0;
    }
    function open(d, px, i) {
      entryPx = px * (1 + slip * d);
      var u = Math.floor(bal * alloc / entryPx);
      if (u < 1) { u = bal * alloc / entryPx; fractional = true; }
      units = u; dir = d; entryIdx = i;
      entryCost = entryPx * units * cost;
      bal -= entryCost;
    }

    for (var i = 0; i < n; i++) {
      var b = c[i], min = I.istMinuteOfDay(b.t);
      var newDay = i > 0 && I.istDayKey(b.t) !== I.istDayKey(c[i - 1].t);

      // 1. Execute at the open
      if (i > 0) {
        var target = pending;
        if (intraday && newDay) { if (dir !== 0) close(b.o, i, 'session'); target = 0; }
        if (target !== dir) {
          if (dir !== 0) close(b.o, i, 'signal');
          if (target !== 0) open(target, b.o, i);
        }
      }

      // 2. Hard stop-loss / target inside the bar (stop is assumed to hit first when both are touched)
      if (dir !== 0 && (cfg.stopPct > 0 || cfg.targetPct > 0)) {
        var stopPx = cfg.stopPct > 0 ? entryPx * (1 - dir * cfg.stopPct / 100) : null;
        var tgtPx = cfg.targetPct > 0 ? entryPx * (1 + dir * cfg.targetPct / 100) : null;
        var d0 = dir;
        if (stopPx !== null && (d0 === 1 ? b.l <= stopPx : b.h >= stopPx)) {
          close(d0 === 1 ? Math.min(b.o, stopPx) : Math.max(b.o, stopPx), i, 'stop'); blockDir = d0;
        } else if (tgtPx !== null && (d0 === 1 ? b.h >= tgtPx : b.l <= tgtPx)) {
          close(d0 === 1 ? Math.max(b.o, tgtPx) : Math.min(b.o, tgtPx), i, 'target'); blockDir = d0;
        }
      }

      // 3. Decide at the close
      var want = pos[i] || 0;
      if (want === -1 && !cfg.allowShort) want = 0;
      if (blockDir !== 0 && want !== blockDir) blockDir = 0;
      if (blockDir !== 0 && want === blockDir) want = 0;
      if (intraday && min + ivMin >= SQUARE_OFF_MIN) {
        if (dir !== 0) close(b.c, i, 'square-off');
        want = 0;
      } else if (intraday && min + ivMin >= NO_ENTRY_MIN && want !== dir) {
        want = 0;
      }
      pending = want;
      if (dir !== 0) barsIn++;
      equity.push({ t: b.t, v: bal + (dir !== 0 ? (b.c - entryPx) * units * dir : 0) });
    }

    var openTrade = null;
    if (dir !== 0) {
      openTrade = { dir: dir, entryIdx: entryIdx, entryT: c[entryIdx].t, entry: entryPx, units: units };
      close(c[n - 1].c, n - 1, 'open');
      equity[n - 1].v = bal;
    }

    return { trades: trades, equity: equity, openTrade: openTrade, metrics: metrics(c, trades, equity, cfg, barsIn), fractional: fractional, intraday: intraday };
  }

  function metrics(c, trades, equity, cfg, barsIn) {
    var n = c.length, m = { trades: trades.length };
    if (!n) return m;
    var start = cfg.capital, end = equity[n - 1].v;
    var years = Math.max((c[n - 1].t - c[0].t) / (365.25 * 86400), 1 / 365.25);
    m.netProfit = end - start;
    m.returnPct = 100 * (end / start - 1);
    m.years = years;
    m.cagr = end > 0 ? 100 * (Math.pow(end / start, 1 / years) - 1) : -100;
    m.buyHoldPct = 100 * (c[n - 1].c / c[0].o - 1);
    m.buyHoldCagr = 100 * (Math.pow(c[n - 1].c / c[0].o, 1 / years) - 1);
    m.exposurePct = 100 * barsIn / n;

    var peak = start, maxDd = 0;
    for (var i = 0; i < n; i++) { if (equity[i].v > peak) peak = equity[i].v; var dd = (peak - equity[i].v) / peak; if (dd > maxDd) maxDd = dd; }
    m.maxDrawdownPct = 100 * maxDd;

    var bhPeak = c[0].o, bhDd = 0;
    for (i = 0; i < n; i++) { if (c[i].c > bhPeak) bhPeak = c[i].c; var d2 = (bhPeak - c[i].c) / bhPeak; if (d2 > bhDd) bhDd = d2; }
    m.buyHoldDrawdownPct = 100 * bhDd;

    var wins = 0, gp = 0, gl = 0, streak = 0, maxStreak = 0, bars = 0, best = 0, worst = 0;
    trades.forEach(function (t) {
      if (t.pnl > 0) { wins++; gp += t.pnl; streak = 0; } else { gl -= t.pnl; streak++; if (streak > maxStreak) maxStreak = streak; }
      bars += t.bars;
      if (t.pnlPct > best) best = t.pnlPct;
      if (t.pnlPct < worst) worst = t.pnlPct;
    });
    var losses = trades.length - wins;
    m.wins = wins; m.losses = losses;
    m.winRate = trades.length ? 100 * wins / trades.length : 0;
    m.profitFactor = gl > 0 ? gp / gl : (gp > 0 ? Infinity : 0);
    m.avgWin = wins ? gp / wins : 0;
    m.avgLoss = losses ? gl / losses : 0;
    m.expectancy = trades.length ? (gp - gl) / trades.length : 0;
    m.maxLossStreak = maxStreak;
    m.avgBars = trades.length ? bars / trades.length : 0;
    m.bestPct = best; m.worstPct = worst;

    // Sharpe from daily equity returns
    var daily = [], lastDay = null;
    for (i = 0; i < n; i++) {
      var dk = I.istDayKey(equity[i].t);
      if (dk !== lastDay) { daily.push(equity[i].v); lastDay = dk; } else daily[daily.length - 1] = equity[i].v;
    }
    var rets = [], prev = start;
    daily.forEach(function (v) { rets.push(v / prev - 1); prev = v; });
    var mean = 0; rets.forEach(function (r) { mean += r; }); mean /= rets.length || 1;
    var varc = 0; rets.forEach(function (r) { varc += (r - mean) * (r - mean); }); varc /= Math.max(rets.length - 1, 1);
    var perYear = rets.length / years;
    m.sharpe = varc > 0 ? mean / Math.sqrt(varc) * Math.sqrt(perYear) : 0;
    return m;
  }

  function runStrategy(c, strategy, params, cfg) {
    var p = params || MF.strategies.defaults(strategy);
    var sig = strategy.compute(c, p, { allowShort: !!cfg.allowShort });
    var res = run(c, sig.pos, cfg);
    res.lines = sig.lines; res.pos = sig.pos; res.strategy = strategy; res.params = p;
    return res;
  }

  /* Robustness score used for ranking: rewards risk-adjusted return, penalises too few trades. */
  function score(m) {
    if (!m.trades) return -999;
    var s = m.cagr / Math.max(m.maxDrawdownPct, 1);
    if (m.trades < 10) s *= m.trades / 10;
    return s;
  }

  function rankAll(c, cfg, filter) {
    var iv = intervalSeconds(c), out = [];
    MF.strategies.list.forEach(function (s) {
      if (s.intradayOnly && iv >= 86400) return;
      if (filter && !filter(s)) return;
      var r = runStrategy(c, s, null, cfg);
      out.push({ strategy: s, metrics: r.metrics, score: score(r.metrics) });
    });
    out.sort(function (a, b) { return b.score - a.score; });
    return out;
  }

  MF.backtest = { run: run, runStrategy: runStrategy, rankAll: rankAll, score: score, defaultsFor: defaultsFor, intervalSeconds: intervalSeconds };
  if (typeof module !== 'undefined' && module.exports) module.exports = MF;
})(typeof window !== 'undefined' ? window : globalThis);
