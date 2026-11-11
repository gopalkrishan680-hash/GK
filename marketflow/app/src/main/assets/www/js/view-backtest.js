/* Backtest tab: run one strategy on history, or rank every strategy on a symbol. */
(function (root) {
  'use strict';
  var MF = root.MF, U = MF.ui, F = MF.fmt, BT = MF.backtest, ST = MF.strategies;
  MF.views = MF.views || {};

  var STYLES = {
    intraday: { label: 'Intraday', tfs: ['5m', '15m'], tf: '15m' },
    swing: { label: 'Swing', tfs: ['1h', '1D'], tf: '1D' },
    long: { label: 'Long term', tfs: ['1D', '1W'], tf: '1D' }
  };
  var PERIODS = { '5m': [[30, '1 month'], [60, '2 months'], [90, '3 months']], '15m': [[60, '2 months'], [120, '4 months'], [180, '6 months']], '1h': [[180, '6 months'], [360, '1 year'], [720, '2 years']], '1D': [[365, '1 year'], [730, '2 years'], [1095, '3 years'], [1825, '5 years'], [2200, '6 years']], '1W': [[1095, '3 years'], [2200, '6 years']] };
  var DEF_PERIOD = { '5m': 60, '15m': 120, '1h': 360, '1D': 1825, '1W': 2200 };

  function defaults() { return { sym: 'NSE:RELIANCE', style: 'swing', tf: '1D', days: 1825, strategy: 'supertrend', params: {}, capital: 100000, allowShort: false, stopPct: 0, targetPct: 0, costPct: 0.12, slippagePct: 0.05 }; }
  MF.btState = function () { var s = MF.store.get('bt', null) || defaults(), d = defaults(); Object.keys(d).forEach(function (k) { if (s[k] === undefined) s[k] = d[k]; }); return s; };

  function strategiesFor(style) { return ST.list.filter(function (s) { return s.styles.indexOf(style) >= 0; }); }
  function cfgOf(st) { return { capital: +st.capital || 100000, costPct: +st.costPct || 0, slippagePct: +st.slippagePct || 0, intraday: st.style === 'intraday', allowShort: !!st.allowShort, stopPct: +st.stopPct || 0, targetPct: +st.targetPct || 0, allocPct: 100 }; }
  MF.btCfg = cfgOf;

  function metricTiles(m) {
    var t = [
      ['Net profit', F.rupee(m.netProfit), U.cls(m.netProfit)], ['Total return', F.pct(m.returnPct), U.cls(m.returnPct)], ['Per year (CAGR)', F.pct(m.cagr), U.cls(m.cagr)],
      ['Max drawdown', '-' + F.num(m.maxDrawdownPct, 1) + '%', 'down'], ['Win rate', F.num(m.winRate, 0) + '%', ''], ['Profit factor', F.num(m.profitFactor, 2), m.profitFactor >= 1 ? 'up' : 'down'],
      ['Trades', F.num(m.trades, 0), ''], ['Avg win / loss', F.rupee(m.avgWin) + ' / ' + F.rupee(m.avgLoss), ''], ['Sharpe ratio', F.num(m.sharpe, 2), '']
    ];
    return '<div class="grid3">' + t.map(function (x) { return '<div class="stat"><div class="k">' + x[0] + '</div><div class="v ' + x[2] + '" style="font-size:' + (x[1].length > 11 ? 11 : 14) + 'px">' + x[1] + '</div></div>'; }).join('') + '</div>';
  }

  function verdictText(m) {
    if (!m.trades) return 'The strategy did not take any trade in this period.';
    var parts = [], beat = m.returnPct > m.buyHoldPct;
    parts.push(m.netProfit > 0 ? 'Profitable after costs' : 'Lost money after costs');
    parts.push(beat ? 'beat buy-and-hold (' + F.pct(m.buyHoldPct, 1) + ')' : 'did not beat buy-and-hold (' + F.pct(m.buyHoldPct, 1) + ')');
    if (m.maxDrawdownPct < m.buyHoldDrawdownPct) parts.push('with a smaller worst fall (' + F.num(m.maxDrawdownPct, 0) + '% vs ' + F.num(m.buyHoldDrawdownPct, 0) + '%)');
    var s = parts.join(', ') + '.';
    if (m.trades < 20) s += ' Only ' + m.trades + ' trades — too few to trust the result.';
    return s;
  }

  MF.views.backtest = function (el, args) {
    var st = MF.btState(), tab = args.tab || 'run', dead = false, charts = [];
    if (args.sym) { st.sym = MF.data.key(args.sym); }
    if (args.strategy) st.strategy = args.strategy;
    function save() { MF.store.set('bt', st); }
    function sym() { return MF.instruments.get(st.sym) || MF.instruments.get('NSE:RELIANCE'); }
    function strat() { var l = strategiesFor(st.style), s = ST.byId(st.strategy); return s && l.indexOf(s) >= 0 ? s : l[0]; }
    function params(s) { var p = ST.defaults(s), o = st.params[s.id] || {}; Object.keys(p).forEach(function (k) { if (o[k] !== undefined && !isNaN(o[k])) p[k] = o[k]; }); return p; }
    function killCharts() { charts.forEach(function (c) { c.destroy(); }); charts = []; }

    el.innerHTML = '<div class="row between mb"><div><h1>Backtest</h1><small>Test a strategy on past data before risking money</small></div></div>' +
      '<div class="seg mb"><button data-tab="run">Backtest</button><button data-tab="rank">Rank strategies</button><button data-tab="replay">Replay</button></div><div data-body></div>';
    var body = U.$('[data-body]', el);

    function form(withStrategy) {
      var s = strat(), sy = sym(), tfs = STYLES[st.style].tfs;
      if (tfs.indexOf(st.tf) < 0) { st.tf = STYLES[st.style].tf; st.days = DEF_PERIOD[st.tf]; }
      if (!PERIODS[st.tf].some(function (p) { return p[0] === st.days; })) st.days = DEF_PERIOD[st.tf];
      var h = '<div class="card"><div class="field"><label class="f">Stock or index</label><button class="btn ghost block" data-sym style="text-align:left">' + F.esc(U.symLabel(sy)) + ' <small>' + F.esc(sy.type === 'IDX' ? 'index' : sy.name) + '</small></button></div>' +
        '<div class="field"><label class="f">Trading style</label><div class="seg">' + Object.keys(STYLES).map(function (k) { return '<button data-style="' + k + '" class="' + (k === st.style ? 'active' : '') + '">' + STYLES[k].label + '</button>'; }).join('') + '</div></div>' +
        '<div class="grid2"><div class="field"><label class="f">Candle size</label><select data-f="tf">' + tfs.map(function (t) { return '<option value="' + t + '"' + (t === st.tf ? ' selected' : '') + '>' + MF.data.tf(t).label + '</option>'; }).join('') + '</select></div>' +
        '<div class="field"><label class="f">History</label><select data-f="days">' + PERIODS[st.tf].map(function (p) { return '<option value="' + p[0] + '"' + (p[0] === st.days ? ' selected' : '') + '>' + p[1] + '</option>'; }).join('') + '</select></div></div>';
      if (withStrategy) {
        var p = params(s);
        h += '<div class="field"><label class="f">Strategy</label><select data-f="strategy">' + strategiesFor(st.style).map(function (x) { return '<option value="' + x.id + '"' + (x === s ? ' selected' : '') + '>' + x.name + '</option>'; }).join('') + '</select></div>' +
          '<div class="note"><b>' + s.name + '.</b> ' + s.summary + ' <span class="muted">' + s.logic + '</span></div>' +
          '<div class="grid2">' + s.params.map(function (x) { return '<div class="field"><label class="f">' + x.label + '</label><input type="number" inputmode="decimal" data-param="' + x.key + '" value="' + p[x.key] + '" min="' + x.min + '" max="' + x.max + '" step="' + x.step + '"></div>'; }).join('') + '</div>';
      }
      h += '<details><summary class="muted" style="padding:6px 0">Capital, costs and risk settings</summary><div class="grid2 mt-s">' +
        '<div class="field"><label class="f">Capital (₹)</label><input type="number" inputmode="decimal" data-f="capital" value="' + st.capital + '"></div>' +
        '<div class="field"><label class="f">Cost per side (%)</label><input type="number" inputmode="decimal" step="0.01" data-f="costPct" value="' + st.costPct + '"></div>' +
        '<div class="field"><label class="f">Slippage per side (%)</label><input type="number" inputmode="decimal" step="0.01" data-f="slippagePct" value="' + st.slippagePct + '"></div>' +
        '<div class="field"><label class="f">Stop-loss % (0 = off)</label><input type="number" inputmode="decimal" step="0.25" data-f="stopPct" value="' + st.stopPct + '"></div>' +
        '<div class="field"><label class="f">Target % (0 = off)</label><input type="number" inputmode="decimal" step="0.25" data-f="targetPct" value="' + st.targetPct + '"></div></div>' +
        '<label class="switch"><span>Allow short selling<br><small>Shorts are only possible intraday or in F&O</small></span><input type="checkbox" data-f="allowShort"' + (st.allowShort ? ' checked' : '') + '></label></details></div>';
      return h;
    }

    function bindForm(rerender) {
      U.$('[data-sym]', body).onclick = function () { U.pickSymbol('Choose stock or index', function (s) { st.sym = MF.data.key(s); save(); rerender(); }); };
      U.on(body, 'click', '[data-style]', function (e, b) {
        st.style = b.getAttribute('data-style'); st.tf = STYLES[st.style].tf; st.days = DEF_PERIOD[st.tf];
        var d = BT.defaultsFor(st.style); st.costPct = d.costPct; st.slippagePct = d.slippagePct; st.allowShort = d.allowShort;
        save(); rerender();
      });
      U.$$('[data-f]', body).forEach(function (inp) {
        inp.addEventListener('change', function () {
          var k = inp.getAttribute('data-f');
          st[k] = inp.type === 'checkbox' ? inp.checked : (k === 'tf' || k === 'strategy') ? inp.value : (parseFloat(inp.value) || 0);
          if (k === 'tf') st.days = DEF_PERIOD[st.tf];
          save();
          if (k === 'tf' || k === 'strategy') rerender();
        });
      });
      U.$$('[data-param]', body).forEach(function (inp) {
        inp.addEventListener('change', function () { var s = strat(); st.params[s.id] = st.params[s.id] || {}; st.params[s.id][inp.getAttribute('data-param')] = parseFloat(inp.value); save(); });
      });
    }

    /* ---------- single backtest ---------- */
    function showRun(autoRun) {
      killCharts();
      body.innerHTML = form(true) + '<button class="btn block mb" data-go>Run backtest</button><div data-res></div>';
      bindForm(function () { showRun(false); });
      U.$('[data-go]', body).onclick = run;
      if (autoRun) run();
    }

    function run() {
      var res = U.$('[data-res]', body), s = strat(), sy = sym(), btn = U.$('[data-go]', body);
      killCharts(); res.innerHTML = U.spinner + '<p class="center muted">Loading history…</p>'; btn.disabled = true;
      MF.data.candles(sy, st.tf, st.days).then(function (c) {
        if (dead) return;
        btn.disabled = false;
        if (c.length < 60) { res.innerHTML = '<div class="err">Only ' + c.length + ' candles were returned — not enough history to test. Try a longer period or another symbol.</div>'; return; }
        var r = BT.runStrategy(c, s, params(s), cfgOf(st)), m = r.metrics, intra = MF.data.tf(st.tf).sec < 86400;
        var html = '<div class="card"><div class="card-h"><h2>' + s.name + ' on ' + F.esc(U.symLabel(sy)) + '</h2></div>' +
          '<small>' + F.date(c[0].t) + ' → ' + F.date(c[c.length - 1].t) + ' · ' + c.length + ' candles (' + MF.data.tf(st.tf).label + ')' + (MF.data.isDemo() ? ' · <span class="warn">simulated demo data</span>' : '') + '</small>' +
          '<p class="mt-s"><b>' + verdictText(m) + '</b></p>' + metricTiles(m) +
          '<div class="kv mt-s"><span>Buy & hold over the same period</span><span class="' + U.cls(m.buyHoldPct) + '">' + F.pct(m.buyHoldPct, 1) + ' (worst fall -' + F.num(m.buyHoldDrawdownPct, 0) + '%)</span></div>' +
          '<div class="kv"><span>Time in the market</span><span>' + F.num(m.exposurePct, 0) + '%</span></div><div class="kv"><span>Longest losing streak</span><span>' + m.maxLossStreak + ' trades</span></div>' +
          '<div class="kv"><span>Best / worst trade</span><span>' + F.pct(m.bestPct, 1) + ' / ' + F.pct(m.worstPct, 1) + '</span></div>' +
          (r.openTrade ? '<div class="kv"><span>Position right now</span><span class="' + (r.openTrade.dir > 0 ? 'up' : 'down') + '">' + (r.openTrade.dir > 0 ? 'LONG' : 'SHORT') + ' since ' + F.dateTime(r.openTrade.entryT, intra) + '</span></div>' : '<div class="kv"><span>Position right now</span><span>None (in cash)</span></div>') +
          (r.fractional ? '<small class="warn">One unit costs more than the capital, so fractional quantities were used.</small>' : '') + '</div>' +
          '<div class="card" style="padding:6px"><h3 style="padding:6px 6px 0">Account value</h3><div class="chart small" data-eq></div>' + U.legend([{ name: 'Strategy', color: '#4c9aff' }, { name: 'Buy & hold', color: '#8b98ad' }]) + '</div>' +
          '<div class="card" style="padding:6px"><h3 style="padding:6px 6px 0">Trades on the chart</h3><div class="chart" data-px></div>' + U.legend(r.lines) + '</div>' +
          '<button class="btn ghost block mb" data-replay>Replay this strategy candle by candle</button>' +
          '<div class="card flush"><h3 style="padding:12px 12px 0">Trades (' + r.trades.length + ')</h3><div class="scroll-y scroll-x"><table class="t"><thead><tr><th>Entry</th><th>Side</th><th>Buy/Sell at</th><th>Exit at</th><th>P&L</th><th>%</th><th>Why exit</th></tr></thead><tbody>' +
          r.trades.slice(-200).reverse().map(function (t) {
            return '<tr><td>' + F.dateTime(t.entryT, intra) + '</td><td class="' + (t.dir > 0 ? 'up' : 'down') + '">' + (t.dir > 0 ? 'Long' : 'Short') + '</td><td>' + F.price(t.entry) + '</td><td>' + F.price(t.exit) + '</td><td class="' + U.cls(t.pnl) + '">' + F.rupee(t.pnl) + '</td><td class="' + U.cls(t.pnl) + '">' + F.pct(t.pnlPct, 1) + '</td><td>' + (t.reason === 'open' ? 'still open' : t.reason) + '</td></tr>';
          }).join('') + '</tbody></table></div></div>' +
          '<div class="note">Past results do not guarantee future results. A strategy that worked on one stock or period can fail on another — use “Rank strategies” and different periods to check that the result is not luck.</div>';
        res.innerHTML = html;

        var eq = U.chart(U.$('[data-eq]', res), { line: true }); charts.push(eq);
        eq.setIntraday(intra);
        eq.setLine(r.equity);
        var k = cfgOf(st).capital / c[0].o;
        eq.addLine('#8b98ad', c.map(function (x) { return { t: x.t, v: x.c * k }; }), true);
        eq.fit();
        var px = U.chart(U.$('[data-px]', res)); charts.push(px);
        px.setIntraday(intra); px.setCandles(c); px.setLines(c, r.lines);
        var mk = [];
        r.trades.slice(-150).forEach(function (t) {
          mk.push({ t: t.entryT, side: t.dir > 0 ? 'buy' : 'sell', text: t.dir > 0 ? 'B' : 'S' });
          if (t.reason !== 'open') mk.push({ t: t.exitT, side: 'exit', text: (t.pnl >= 0 ? '+' : '') + F.num(t.pnlPct, 1) + '%' });
        });
        px.setMarkers(mk); px.showLast(Math.min(c.length, 160));
        U.$('[data-replay]', res).onclick = function () { MF.app.push(MF.views.replay, { sym: sy, tf: st.tf, strategy: s.id, params: params(s) }, 'Market replay'); };
        res.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }).catch(function (e) { if (dead) return; btn.disabled = false; res.innerHTML = U.error(e); });
    }

    /* ---------- rank all strategies ---------- */
    function showRank() {
      killCharts();
      body.innerHTML = form(false) + '<button class="btn block mb" data-go>Rank all strategies</button><div data-res></div>';
      bindForm(showRank);
      U.$('[data-go]', body).onclick = function () {
        var res = U.$('[data-res]', body), sy = sym(), btn = U.$('[data-go]', body);
        res.innerHTML = U.spinner; btn.disabled = true;
        MF.data.candles(sy, st.tf, st.days).then(function (c) {
          if (dead) return;
          btn.disabled = false;
          if (c.length < 60) { res.innerHTML = '<div class="err">Not enough history (' + c.length + ' candles).</div>'; return; }
          var list = BT.rankAll(c, cfgOf(st), function (s) { return s.styles.indexOf(st.style) >= 0; }), bh = list.length ? list[0].metrics : null;
          res.innerHTML = '<div class="card flush"><div style="padding:12px 12px 6px"><h2>' + F.esc(U.symLabel(sy)) + ' · ' + STYLES[st.style].label + ' · ' + MF.data.tf(st.tf).label + '</h2><small>' + F.date(c[0].t) + ' → ' + F.date(c[c.length - 1].t) + (MF.data.isDemo() ? ' · <span class="warn">simulated demo data</span>' : '') + '. Ranked by return per unit of drawdown, after costs. Buy & hold: ' + (bh ? F.pct(bh.buyHoldPct, 1) : '–') + '.</small></div>' +
            '<div class="scroll-x"><table class="t"><thead><tr><th>Strategy</th><th>Return</th><th>CAGR</th><th>Max DD</th><th>Win %</th><th>PF</th><th>Trades</th></tr></thead><tbody>' + list.map(function (x, i) {
              var m = x.metrics;
              return '<tr class="tap" data-sid="' + x.strategy.id + '"><td><span class="rank">' + (i + 1) + '</span> ' + x.strategy.name + '</td><td class="' + U.cls(m.returnPct) + '">' + F.pct(m.returnPct, 1) + '</td><td class="' + U.cls(m.cagr) + '">' + F.pct(m.cagr, 1) + '</td><td>-' + F.num(m.maxDrawdownPct || 0, 1) + '%</td><td>' + F.num(m.winRate || 0, 0) + '</td><td>' + F.num(m.profitFactor || 0, 2) + '</td><td>' + m.trades + '</td></tr>';
            }).join('') + '</tbody></table></div></div><small class="muted">Tap a row to see its trades. The top strategy on one stock is often not the top one on another — prefer strategies that stay near the top across many symbols.</small>';
          U.on(res, 'click', '[data-sid]', function (e, tr) { st.strategy = tr.getAttribute('data-sid'); save(); setTab('run', true); });
        }).catch(function (e) { if (dead) return; btn.disabled = false; res.innerHTML = U.error(e); });
      };
    }

    function setTab(t, autoRun) {
      if (t === 'replay') { MF.app.push(MF.views.replay, { sym: sym(), tf: st.tf, strategy: strat().id, params: params(strat()) }, 'Market replay'); return; }
      tab = t;
      U.$$('[data-tab]', el).forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-tab') === t); });
      if (t === 'rank') showRank(); else showRun(autoRun);
    }
    U.on(el, 'click', '[data-tab]', function (e, b) { setTab(b.getAttribute('data-tab')); });
    setTab(tab, false);
    return { destroy: function () { dead = true; killCharts(); } };
  };
})(window);
