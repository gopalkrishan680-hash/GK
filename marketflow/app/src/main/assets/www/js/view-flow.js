/* Flow tab: scans a list of stocks and ranks them by buying / selling pressure. */
(function (root) {
  'use strict';
  var MF = root.MF, U = MF.ui, F = MF.fmt, A = MF.analysis, IN = MF.instruments;
  MF.views = MF.views || {};
  var lastScan = null; // survives tab switches

  function scanOne(s, quotes) {
    return Promise.all([MF.data.candles(s, '5m', 4, 120), MF.data.candles(s, '1D', 60, 3600)]).then(function (r) {
      var m5 = r[0], d = r[1], avg = null;
      if (d.length > 21) { var t = 0; d.slice(-21, -1).forEach(function (c) { t += c.v; }); avg = t / 20; }
      var q = quotes[MF.data.key(s)] || null, fl = A.flow(m5, q, avg), ev = A.volumeEvents(m5, 3, 20);
      var lastDay = m5.length ? MF.ind.istDayKey(m5[m5.length - 1].t) : null;
      ev = ev.filter(function (e) { return MF.ind.istDayKey(e.t) === lastDay; });
      return { s: s, q: q, flow: fl, events: ev };
    });
  }

  MF.views.flow = function (el) {
    var scope = MF.store.get('flowScope', 'large'), running = false, dead = false, auto = MF.store.get('flowAuto', false), notify = MF.store.get('flowNotify', false);
    el.innerHTML =
      '<div class="row between mb"><div><h1>Money flow</h1><small>Where is the buying and selling right now?</small></div><button class="iconbtn" data-scan>' + U.icon('refresh') + '</button></div>' +
      '<div class="seg mb"><button data-scope="large">Large caps (' + IN.universe.length + ')</button><button data-scope="watch">My watchlist</button></div>' +
      '<div data-prog class="hidden mb"><div class="progress"><i></i></div><small data-ptext></small></div><div data-err></div>' +
      '<div data-out><div class="note">Tap <b>Scan</b> to measure buying and selling pressure in every stock on the list. Each stock gets a flow score from −100 (heavy selling) to +100 (heavy buying) built from VWAP position, up-volume vs down-volume, money flow and relative volume.</div><button class="btn block" data-scan>Scan now</button></div>' +
      '<div class="card mt"><label class="switch"><span>Auto-rescan every 3 minutes<br><small>while this tab is open and the market is live</small></span><input type="checkbox" data-auto></label>' +
      '<label class="switch"><span>Notify when a stock turns Strong Buying / Selling<br><small>needs auto-rescan</small></span><input type="checkbox" data-notify></label></div>';
    U.$('[data-auto]', el).checked = auto; U.$('[data-notify]', el).checked = notify;

    function setScope() { U.$$('[data-scope]', el).forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-scope') === scope); }); }
    setScope();

    function item(x, showEv) {
      var fl = x.flow, w = Math.abs(fl.score) / 2;
      return '<li class="tap" data-key="' + MF.data.key(x.s) + '"><div class="grow"><div class="row between"><span class="t">' + F.esc(x.s.sym) + '</span><span class="num ' + U.cls(x.q && x.q.chgPct) + '">' + (x.q ? F.pct(x.q.chgPct) : '') + '</span></div>' +
        '<div class="meter" style="margin:5px 0 3px"><i style="' + (fl.score >= 0 ? 'left:50%;background:var(--up)' : 'right:50%;background:var(--down)') + ';width:' + w + '%"></i></div>' +
        '<div class="d">' + (showEv || (fl.rvol ? 'Volume ' + F.num(fl.rvol, 1) + '× normal' : '') + (IN.sectorOf(x.s.sym) ? ' · ' + IN.sectorOf(x.s.sym) : '')) + '</div></div>' +
        '<div class="right" style="width:88px">' + U.flowPill(fl.label) + '<div class="d num">' + F.signed(fl.score, 0) + '</div></div></li>';
    }

    function render(res) {
      var out = U.$('[data-out]', el), list = res.items.slice().sort(function (a, b) { return b.flow.score - a.flow.score; });
      if (!list.length) { out.innerHTML = '<div class="empty">Nothing to scan — add stocks to your watchlist first.</div>'; return; }
      var buy = list.filter(function (x) { return x.flow.score >= 15; }), sell = list.filter(function (x) { return x.flow.score <= -15; }).reverse();
      var avg = list.reduce(function (a, x) { return a + x.flow.score; }, 0) / list.length;
      var html = '<div class="card"><div class="card-h"><h2>Overall flow</h2>' + U.flowPill(A.flowLabel(avg)) + '</div>' + U.gauge(avg) +
        '<div class="grid3 mt"><div class="stat"><div class="k">Buying</div><div class="v up">' + buy.length + '</div></div><div class="stat"><div class="k">Neutral</div><div class="v">' + (list.length - buy.length - sell.length) + '</div></div><div class="stat"><div class="k">Selling</div><div class="v down">' + sell.length + '</div></div></div>' +
        '<small class="mt-s" style="display:block">Scanned ' + list.length + ' stocks at ' + F.time(res.at) + (res.failed ? ' · ' + res.failed + ' could not be loaded' : '') + '</small></div>';

      if (scope === 'large') {
        var secs = Object.keys(IN.sectors).map(function (n) {
          var xs = list.filter(function (x) { return IN.sectorOf(x.s.sym) === n; });
          return { n: n, v: xs.length ? xs.reduce(function (a, x) { return a + x.flow.score; }, 0) / xs.length : null };
        }).filter(function (x) { return x.v !== null; }).sort(function (a, b) { return b.v - a.v; });
        html += '<div class="card"><div class="card-h"><h2>Flow by sector</h2><small>average score</small></div><div class="heat">' + secs.map(function (x) {
          var a = Math.min(0.55, 0.1 + Math.abs(x.v) / 110), col = x.v >= 0 ? 'rgba(34,197,94,' + a + ')' : 'rgba(239,68,68,' + a + ')';
          return '<div style="background:' + col + '"><span class="ellipsis" style="display:block">' + x.n + '</span><b class="num">' + F.signed(x.v, 0) + '</b></div>';
        }).join('') + '</div></div>';
      }

      var evs = [];
      list.forEach(function (x) { x.events.slice(-1).forEach(function (e) { evs.push({ x: x, e: e }); }); });
      evs.sort(function (a, b) { return b.e.t - a.e.t; });
      html += '<div class="card flush"><h3 style="padding:12px 12px 0">Strongest buying</h3><ul class="list">' + (buy.length ? buy.slice(0, 10).map(function (x) { return item(x); }).join('') : '<div class="empty">No stock shows clear buying pressure.</div>') + '</ul></div>';
      html += '<div class="card flush"><h3 style="padding:12px 12px 0">Strongest selling</h3><ul class="list">' + (sell.length ? sell.slice(0, 10).map(function (x) { return item(x); }).join('') : '<div class="empty">No stock shows clear selling pressure.</div>') + '</ul></div>';
      html += '<div class="card flush"><h3 style="padding:12px 12px 0">Latest volume spikes today</h3><ul class="list">' + (evs.length ? evs.slice(0, 10).map(function (v) {
        return item(v.x, F.time(v.e.t) + ' · ' + F.num(v.e.ratio, 1) + '× volume, ' + (v.e.side === 'buy' ? 'buyers' : v.e.side === 'sell' ? 'sellers' : 'two-way') + ' (' + F.pct(v.e.chgPct) + ')');
      }).join('') : '<div class="empty">No 3× volume spikes today.</div>') + '</ul></div>';
      out.innerHTML = html;
    }

    function scan(silent) {
      if (running) return;
      var syms = scope === 'watch' ? U.watchlist().filter(function (s) { return s.type !== 'IDX'; }) : IN.universe;
      if (!syms.length) { render({ items: [], at: MF.clock.now() }); return; }
      running = true;
      var prog = U.$('[data-prog]', el), bar = U.$('.progress i', el), txt = U.$('[data-ptext]', el), done = 0, items = [], failed = 0, firstErr = null;
      U.$('[data-err]', el).innerHTML = '';
      if (!silent) prog.classList.remove('hidden');
      bar.style.width = '0';
      MF.data.quotes(syms, 20).catch(function (e) { firstErr = e; return {}; }).then(function (quotes) {
        var i = 0;
        function next() {
          if (dead || i >= syms.length) return Promise.resolve();
          var s = syms[i++];
          return scanOne(s, quotes).then(function (r) { items.push(r); }, function (e) { failed++; firstErr = firstErr || e; }).then(function () {
            done++; bar.style.width = (100 * done / syms.length) + '%'; txt.textContent = 'Scanning ' + done + ' / ' + syms.length + ' — ' + s.sym;
            if (failed >= 3 && !items.length) return; // credentials or network problem: stop early
            return next();
          });
        }
        return Promise.all([next(), next(), next()]);
      }).then(function () {
        running = false; prog.classList.add('hidden');
        if (dead) return;
        if (!items.length && firstErr) { U.$('[data-err]', el).innerHTML = U.error(firstErr, true); return; }
        var prev = lastScan && lastScan.scope === scope ? lastScan : null;
        lastScan = { scope: scope, items: items, at: MF.clock.now(), failed: failed };
        render(lastScan);
        if (notify && prev) {
          var old = {}; prev.items.forEach(function (x) { old[MF.data.key(x.s)] = x.flow.label; });
          items.forEach(function (x) {
            var k = MF.data.key(x.s);
            if (/^Strong/.test(x.flow.label) && old[k] && old[k] !== x.flow.label) MF.alerts.notify(x.s.sym + ': ' + x.flow.label, 'Flow score ' + F.signed(x.flow.score, 0) + (x.q ? ', price ' + F.price(x.q.ltp) + ' (' + F.pct(x.q.chgPct) + ')' : ''));
          });
        }
      });
    }

    U.on(el, 'click', '[data-scan]', function () { scan(false); });
    U.on(el, 'click', '[data-retry]', function () { scan(false); });
    U.on(el, 'click', '[data-scope]', function (e, b) { scope = b.getAttribute('data-scope'); MF.store.set('flowScope', scope); setScope(); if (lastScan && lastScan.scope === scope) render(lastScan); else scan(false); });
    U.on(el, 'click', '[data-key]', function (e, t) { var s = IN.get(t.getAttribute('data-key')); if (s) MF.app.openDetail(s); });
    U.$('[data-auto]', el).onchange = function (e) { auto = e.target.checked; MF.store.set('flowAuto', auto); };
    U.$('[data-notify]', el).onchange = function (e) { notify = e.target.checked; MF.store.set('flowNotify', notify); if (notify) MF.alerts.ensurePermission(); };

    if (lastScan && lastScan.scope === scope) render(lastScan);
    var stop = U.poll(function () { if (auto && (MF.clock.isMarketOpen() || MF.data.isDemo())) scan(true); }, 180000);
    return { destroy: function () { dead = true; stop(); } };
  };
})(window);
