/* Stock / index detail page: live price, chart, buying-selling pressure, depth, outlook, levels and trade plan. */
(function (root) {
  'use strict';
  var MF = root.MF, U = MF.ui, F = MF.fmt, I = MF.ind, A = MF.analysis;
  MF.views = MF.views || {};
  var OVERLAYS = [{ id: 'ema', label: 'EMA 20/50' }, { id: 'vwap', label: 'VWAP' }, { id: 'st', label: 'Supertrend' }, { id: 'bb', label: 'Bollinger' }, { id: 'lv', label: 'Levels' }, { id: 'vol', label: 'Volume spikes' }];

  MF.views.detail = function (el, args, bar) {
    var sym = args.sym, tfId = MF.store.get('detailTf', '15m'), on = MF.store.get('detailOverlays', { ema: true, vwap: true, vol: true });
    var chart = null, candles = [], daily = null, quote = null, intra5 = null, dead = false;
    bar.sub.textContent = sym.type === 'IDX' ? sym.ex + ' index' : sym.name + ' · ' + sym.ex;
    bar.actions.innerHTML = '<button class="iconbtn" data-alert>' + U.icon('bell') + '</button><button class="iconbtn" data-star>' + U.icon('star') + '</button>';
    var star = U.$('[data-star]', bar.actions);
    star.classList.toggle('on', U.inWatchlist(sym));
    star.onclick = function () { var added = U.toggleWatch(sym); star.classList.toggle('on', added); U.toast(added ? 'Added to watchlist' : 'Removed from watchlist'); };
    U.$('[data-alert]', bar.actions).onclick = function () { MF.alerts.addSheet(sym, quote && quote.ltp); };

    el.innerHTML =
      '<div data-err></div>' +
      '<div class="row between" style="align-items:flex-end"><div><div class="big" data-ltp>–</div><div data-chg class="num">&nbsp;</div></div><div class="right" data-flowpill></div></div>' +
      '<div class="grid3 mt mb" data-ohlc></div>' +
      '<div class="chips mb" data-tf>' + MF.data.TF.map(function (t) { return '<button class="chip' + (t.id === tfId ? ' active' : '') + '" data-tfid="' + t.id + '">' + t.label + '</button>'; }).join('') + '</div>' +
      '<div class="card" style="padding:6px"><div class="chart" data-chart></div><div data-legend></div></div>' +
      '<div class="chips mb" data-ov>' + OVERLAYS.map(function (o) { return '<button class="chip' + (on[o.id] ? ' active' : '') + '" data-ovid="' + o.id + '">' + o.label + '</button>'; }).join('') + '</div>' +
      '<div class="card" data-flow><h2>Buying vs selling pressure</h2>' + U.spinner + '</div>' +
      '<div class="card hidden" data-depth></div>' +
      '<div class="card" data-events><h2>Unusual volume</h2>' + U.spinner + '</div>' +
      '<div class="card" data-outlook><h2>Outlook</h2>' + U.spinner + '</div>' +
      '<div class="card" data-levels><h2>Key levels</h2>' + U.spinner + '</div>' +
      '<div class="card" data-plan></div>' +
      '<div class="grid2 mb"><button class="btn ghost" data-bt>Backtest</button><button class="btn ghost" data-rp>Replay</button></div>' +
      (MF.instruments.isFno(sym.sym) ? '<button class="btn ghost block mb" data-oc>Open option chain</button>' : '') +
      '<p class="muted center" style="font-size:11px">Signals describe what the data shows now. They are not predictions or investment advice.</p>';

    chart = U.chart(U.$('[data-chart]', el));

    function showErr(e) { if (!dead) U.$('[data-err]', el).innerHTML = U.error(e, true); }

    function renderQuote() {
      if (!quote) return;
      U.$('[data-ltp]', el).textContent = F.price(quote.ltp);
      var c = U.$('[data-chg]', el); c.className = 'num ' + U.cls(quote.chg); c.textContent = F.signed(quote.chg) + ' (' + F.pct(quote.chgPct) + ')';
      var cells = [['Open', F.price(quote.open)], ['High', F.price(quote.high)], ['Low', F.price(quote.low)], ['Prev close', F.price(quote.prevClose)], ['Volume', quote.volume ? F.compact(quote.volume) : '–'], ['52W range', quote.low52 ? F.num(quote.low52, 0) + ' – ' + F.num(quote.high52, 0) : '–']];
      U.$('[data-ohlc]', el).innerHTML = cells.map(function (x) { return '<div class="stat"><div class="k">' + x[0] + '</div><div class="v" style="font-size:13px">' + x[1] + '</div></div>'; }).join('');

      var d = U.$('[data-depth]', el);
      if (quote.depth && (quote.depth.buy.length || quote.depth.sell.length)) {
        var mx = 1; quote.depth.buy.concat(quote.depth.sell).forEach(function (l) { if (l.quantity > mx) mx = l.quantity; });
        var tb = quote.totalBuyQty || 0, ts = quote.totalSellQty || 0, share = tb + ts ? tb / (tb + ts) : 0.5;
        function lv(l) { return '<div class="lv"><i style="width:' + (100 * l.quantity / mx) + '%"></i><span>' + F.price(l.price) + '</span><span>' + F.num(l.quantity, 0) + '</span></div>'; }
        d.classList.remove('hidden');
        d.innerHTML = '<div class="card-h"><h2>Market depth</h2><small>pending orders</small></div>' +
          '<div class="bar"><i style="width:' + (100 * share) + '%"></i></div><div class="row between mt-s mb"><span class="up num">Buy ' + F.compact(tb) + ' (' + Math.round(share * 100) + '%)</span><span class="down num">Sell ' + F.compact(ts) + '</span></div>' +
          '<div class="depth"><div class="b"><h3>Bids</h3>' + quote.depth.buy.slice(0, 5).map(lv).join('') + '</div><div class="a"><h3>Offers</h3>' + quote.depth.sell.slice(0, 5).map(lv).join('') + '</div></div>' +
          '<small class="mt-s" style="display:block">Pending orders can be cancelled at any time, so treat this as a hint, not proof.</small>';
      } else d.classList.add('hidden');
    }

    function renderFlow() {
      if (!intra5) return;
      var avg = null;
      if (daily && daily.length > 21) { var s = 0; daily.slice(-21, -1).forEach(function (c) { s += c.v; }); avg = s / 20; }
      var fl = A.flow(intra5, quote, avg);
      U.$('[data-flowpill]', el).innerHTML = U.flowPill(fl.label) + '<div class="muted" style="font-size:11px;margin-top:3px">flow score ' + F.signed(fl.score, 0) + '</div>';
      var html = '<div class="card-h"><h2>Buying vs selling pressure</h2>' + U.flowPill(fl.label) + '</div>' + U.gauge(fl.score);
      if (fl.rvol) html += '<div class="kv mt-s"><span>Relative volume today</span><span class="' + (fl.rvol > 1.5 ? 'warn' : '') + '">' + F.num(fl.rvol, 2) + '× normal</span></div>';
      html += '<div class="mt-s">' + fl.parts.map(function (p) { return '<div class="check"><span class="dot ' + (p.value > 0.15 ? 'up' : p.value < -0.15 ? 'down' : '') + '"></span><span>' + F.esc(p.text) + '</span></div>'; }).join('') + '</div>';
      if (!fl.hasVolume) html += '<small>Indices have no traded volume of their own, so this reading uses price action only. Check the Flow tab for stock-by-stock volume and the Options tab for open interest.</small>';
      U.$('[data-flow]', el).innerHTML = html;

      var ev = A.volumeEvents(intra5, 3, 20).slice(-6).reverse();
      U.$('[data-events]', el).innerHTML = '<div class="card-h"><h2>Unusual volume</h2><small>5-minute candles, 3× average or more</small></div>' + (ev.length ? '<ul class="list" style="margin:0 -12px -12px">' + ev.map(function (x) {
        return '<li><div class="grow"><div class="t">' + (x.side === 'buy' ? '<span class="up">Heavy buying</span>' : x.side === 'sell' ? '<span class="down">Heavy selling</span>' : 'Heavy two-way volume') + '</div><div class="d">' + F.dateTime(x.t) + ' · ' + F.compact(x.volume) + ' shares at ' + F.price(x.price) + '</div></div><div class="right num"><div class="t">' + F.num(x.ratio, 1) + '×</div><div class="d ' + U.cls(x.chgPct) + '">' + F.pct(x.chgPct) + '</div></div></li>';
      }).join('') + '</ul>' : '<div class="empty">' + (fl.hasVolume ? 'No volume spikes in the last two sessions.' : 'Not available for indices.') + '</div>');
    }

    function renderOutlook() {
      if (!daily) return;
      var intraSrc = candles.length && MF.data.tf(tfId).sec < 86400 ? candles : intra5, o = A.outlook(intraSrc, daily);
      function block(title, sub, x) {
        if (!x) return '';
        return '<div class="mb"><div class="row between"><div><b>' + title + '</b> <small>' + sub + '</small></div>' + U.verdictPill(x.verdict) + '</div>' + x.checks.map(function (c) { return '<div class="check"><span class="dot ' + (c.dir > 0 ? 'up' : c.dir < 0 ? 'down' : '') + '"></span><span>' + F.esc(c.text) + '</span></div>'; }).join('') + '</div>';
      }
      U.$('[data-outlook]', el).innerHTML = '<h2>Outlook by time horizon</h2>' + block('Intraday', 'minutes to hours', o.intraday) + block('Swing', 'days to weeks', o.swing) + (o.long ? block('Long term', 'months', o.long) : '<small>Long-term view needs about one year of daily history.</small>');

      var lv = A.levels(daily);
      if (lv) {
        var p = lv.pivot, ltp = quote ? quote.ltp : daily[daily.length - 1].c;
        var rows = [['R3', p.r3], ['R2', p.r2], ['R1', p.r1], ['Pivot', p.p], ['S1', p.s1], ['S2', p.s2], ['S3', p.s3]];
        U.$('[data-levels]', el).innerHTML = '<div class="card-h"><h2>Key levels</h2><small>from the previous session</small></div><div class="grid2"><div>' +
          rows.map(function (r) { return '<div class="kv"><span class="' + (r[1] > ltp ? 'down' : r[1] < ltp ? 'up' : '') + '">' + r[0] + '</span><span>' + F.price(r[1]) + '</span></div>'; }).join('') + '</div><div>' +
          [['Prev high', lv.prevHigh], ['Prev low', lv.prevLow], ['Prev close', lv.prevClose], ['20-day high', lv.high20], ['20-day low', lv.low20], ['ATR (14)', lv.atr]].map(function (r) { return '<div class="kv"><span>' + r[0] + '</span><span>' + F.price(r[1]) + '</span></div>'; }).join('') +
          '</div></div><small class="mt-s" style="display:block">Levels above the price act as resistance, levels below as support.</small>';
        renderPlan(lv.atr);
      }
    }

    function renderPlan(atr) {
      var st = MF.store.get('plan', { capital: 100000, risk: 1, atrStop: 1.5, rr: 2, dir: 1 }), box = U.$('[data-plan]', el);
      if (!box.firstChild) {
        box.innerHTML = '<div class="card-h"><h2>Trade plan calculator</h2><div class="seg" style="width:130px"><button data-d="1">Buy</button><button data-d="-1">Sell</button></div></div>' +
          '<div class="grid2"><div class="field"><label class="f">Capital (₹)</label><input type="number" inputmode="decimal" data-p="capital"></div><div class="field"><label class="f">Risk per trade (%)</label><input type="number" inputmode="decimal" step="0.25" data-p="risk"></div>' +
          '<div class="field"><label class="f">Stop distance (× ATR)</label><input type="number" inputmode="decimal" step="0.5" data-p="atrStop"></div><div class="field"><label class="f">Reward : risk</label><input type="number" inputmode="decimal" step="0.5" data-p="rr"></div></div><div data-out></div>';
        U.$$('[data-p]', box).forEach(function (inp) { inp.value = st[inp.getAttribute('data-p')]; inp.addEventListener('input', function () { st[inp.getAttribute('data-p')] = parseFloat(inp.value) || 0; MF.store.set('plan', st); calc(); }); });
        U.on(box, 'click', '[data-d]', function (e, b) { st.dir = +b.getAttribute('data-d'); MF.store.set('plan', st); calc(); });
      }
      function calc() {
        U.$$('[data-d]', box).forEach(function (b) { b.classList.toggle('active', +b.getAttribute('data-d') === st.dir); });
        var entry = quote ? quote.ltp : (daily ? daily[daily.length - 1].c : null);
        if (!entry || !atr || !st.capital || !st.risk || !st.atrStop) { U.$('[data-out]', box).innerHTML = ''; return; }
        var p = A.tradePlan(entry, atr, st.dir, st.capital, st.risk, st.atrStop, st.rr || 2);
        U.$('[data-out]', box).innerHTML = '<div class="grid3"><div class="stat"><div class="k">Entry</div><div class="v">' + F.price(p.entry) + '</div></div><div class="stat"><div class="k">Stop-loss</div><div class="v down">' + F.price(p.stop) + '</div></div><div class="stat"><div class="k">Target</div><div class="v up">' + F.price(p.target) + '</div></div></div>' +
          '<div class="kv mt-s"><span>Quantity</span><span>' + F.num(p.qty, 0) + ' shares</span></div><div class="kv"><span>Money at risk</span><span>' + F.rupee(p.riskAmount) + ' (' + F.num(p.stopPct, 2) + '% away)</span></div>' +
          (p.capped ? '<small class="warn">Quantity is limited by your capital, so the actual risk is lower than ' + st.risk + '%.</small>' : '') +
          (MF.instruments.lotSize(sym.sym) ? '<div class="kv"><span>F&O lot size</span><span>' + MF.instruments.lotSize(sym.sym) + '</span></div>' : '');
      }
      calc();
    }

    function renderChart(fit) {
      if (!candles.length) return;
      var intra = MF.data.tf(tfId).sec < 86400, cl = I.col(candles, 'c'), lines = [];
      chart.setIntraday(intra);
      chart.setCandles(candles);
      if (on.ema) { lines.push({ name: 'EMA 20', color: '#f5a524', data: I.ema(cl, 20) }); lines.push({ name: 'EMA 50', color: '#4c9aff', data: I.ema(cl, 50) }); }
      if (on.vwap && intra && candles.some(function (c) { return c.v > 0; })) lines.push({ name: 'VWAP', color: '#e879f9', data: I.vwap(candles), width: 2 });
      if (on.st) lines.push({ name: 'Supertrend', color: '#b388ff', data: I.supertrend(candles, 10, 3).line });
      if (on.bb) { var b = I.bollinger(cl, 20, 2); lines.push({ name: 'BB', color: '#6b7a90', data: b.upper }); lines.push({ name: 'BB lower', color: '#6b7a90', data: b.lower }); }
      chart.setLines(candles, lines);
      U.$('[data-legend]', el).innerHTML = U.legend(lines.filter(function (l) { return l.name !== 'BB lower'; }));
      var lv = on.lv && daily ? A.levels(daily) : null;
      chart.setPriceLines(lv ? [{ price: lv.pivot.r1, color: '#ef4444', title: 'R1' }, { price: lv.pivot.p, color: '#8b98ad', title: 'P' }, { price: lv.pivot.s1, color: '#22c55e', title: 'S1' }] : []);
      chart.setMarkers(on.vol ? A.volumeEvents(candles, 3, 20).slice(-40).map(function (x) { return { t: x.t, side: 'vol-' + x.side, text: F.num(x.ratio, 1) + '×' }; }) : []);
      if (fit) chart.showLast(Math.min(candles.length, 110));
    }

    function loadCandles(fit) {
      return MF.data.candles(sym, tfId).then(function (c) { if (dead) return; candles = c; renderChart(fit); if (!c.length) U.toast('No candles returned for this timeframe'); });
    }
    function loadQuote() { return MF.data.quote(sym).then(function (q) { if (dead) return; quote = q; renderQuote(); }); }
    function loadFlow() { return MF.data.candles(sym, '5m', 4).then(function (c) { if (dead) return; intra5 = c; renderFlow(); }); }
    function loadDaily() { return MF.data.candles(sym, '1D', 540).then(function (c) { if (dead) return; daily = c; renderOutlook(); renderFlow(); renderChart(false); }); }

    function loadAll() {
      U.$('[data-err]', el).innerHTML = '';
      Promise.all([loadQuote(), loadCandles(true), loadFlow()]).then(loadDaily).catch(showErr);
    }

    U.on(el, 'click', '[data-tfid]', function (e, b) {
      tfId = b.getAttribute('data-tfid'); MF.store.set('detailTf', tfId);
      U.$$('[data-tfid]', el).forEach(function (x) { x.classList.toggle('active', x === b); });
      loadCandles(true).then(renderOutlook).catch(showErr);
    });
    U.on(el, 'click', '[data-ovid]', function (e, b) {
      var id = b.getAttribute('data-ovid'); on[id] = !on[id]; MF.store.set('detailOverlays', on); b.classList.toggle('active', !!on[id]); renderChart(false);
    });
    U.on(el, 'click', '[data-retry]', loadAll);
    U.$('[data-bt]', el).onclick = function () { MF.app.show('backtest', { sym: sym }); };
    U.$('[data-rp]', el).onclick = function () { MF.app.push(MF.views.replay, { sym: sym }, 'Market replay'); };
    var oc = U.$('[data-oc]', el); if (oc) oc.onclick = function () { MF.app.show('options', { sym: sym }); };

    loadAll();
    var live = function () { return MF.clock.isMarketOpen() || MF.data.isDemo(); };
    var stop1 = U.poll(function () { if (live()) loadQuote().then(renderFlow).catch(function () { }); }, 5000);
    var stop2 = U.poll(function () { if (live()) { loadCandles(false).catch(function () { }); loadFlow().catch(function () { }); } }, 30000);
    return { destroy: function () { dead = true; stop1(); stop2(); chart.destroy(); } };
  };
})(window);
