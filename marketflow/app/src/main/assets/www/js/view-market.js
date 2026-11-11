/* Market tab: indices, market breadth, watchlist, movers and sector performance. */
(function (root) {
  'use strict';
  var MF = root.MF, U = MF.ui, F = MF.fmt, IN = MF.instruments;
  MF.views = MF.views || {};

  function chgCell(q) { return q && q.chgPct !== undefined && q.chgPct !== null ? '<span class="chg ' + U.cls(q.chgPct) + '">' + F.pct(q.chgPct) + '</span>' : '<span class="chg">–</span>'; }
  function row(s, q) {
    return '<li class="tap" data-key="' + MF.data.key(s) + '"><div class="grow"><div class="t ellipsis">' + F.esc(U.symLabel(s)) + '</div><div class="d ellipsis">' + F.esc(s.type === 'IDX' ? s.ex + ' index' : s.name) + '</div></div>' +
      '<div class="right num"><div class="t">' + (q && q.ltp !== undefined ? F.price(q.ltp) : '–') + '</div><div class="d ' + U.cls(q && q.chg) + '">' + (q && q.chg !== undefined ? F.signed(q.chg) : '') + '</div></div>' + chgCell(q) + '</li>';
  }

  MF.views.market = function (el) {
    el.innerHTML =
      '<div class="row between mb"><div><h1>GK MarketFlow</h1><small data-status></small></div><div class="row"><button class="iconbtn" data-search>' + U.icon('search') + '</button><button class="iconbtn" data-refresh>' + U.icon('refresh') + '</button></div></div>' +
      '<div data-err></div>' +
      '<div class="grid2 mb" data-indices>' + U.spinner + '</div>' +
      '<div class="card" data-breadth></div>' +
      '<div class="card flush"><div class="card-h" style="padding:12px 12px 0"><h2>Watchlist</h2><button class="btn small ghost" data-add>+ Add</button></div><ul class="list" data-watch></ul></div>' +
      '<div class="card"><div class="card-h"><h2>Sector performance</h2><small>large-cap average</small></div><div data-sectors></div></div>' +
      '<div class="grid2"><div class="card flush"><h3 style="padding:12px 12px 0">Top gainers</h3><ul class="list" data-gain></ul></div><div class="card flush"><h3 style="padding:12px 12px 0">Top losers</h3><ul class="list" data-lose></ul></div></div>' +
      '<p class="muted center" style="font-size:11px">Information only — not investment advice.</p>';

    function status() {
      var open = MF.clock.isMarketOpen();
      U.$('[data-status]', el).innerHTML = '<span class="pill ' + (open ? 'up' : '') + '">' + (open ? 'Market open' : 'Market closed') + '</span> <span class="pill info">' + (MF.data.isDemo() ? 'Demo data' : 'Groww live') + '</span>';
    }

    function load(force) {
      status();
      var watch = U.watchlist(), syms = [], seen = {};
      IN.mainIndices.concat(watch, IN.universe).forEach(function (s) { var k = MF.data.key(s); if (!seen[k]) { seen[k] = 1; syms.push(s); } });
      if (force) MF.data.clearCache();
      MF.data.quotes(syms, 8).then(function (q) {
        U.$('[data-err]', el).innerHTML = '';
        U.$('[data-indices]', el).innerHTML = IN.mainIndices.map(function (s) {
          var x = q[MF.data.key(s)] || {};
          return '<div class="tile tap" data-key="' + MF.data.key(s) + '"><div class="k">' + F.esc(s.name) + '</div><div class="v">' + F.price(x.ltp) + '</div><div class="s ' + U.cls(x.chg) + '">' + F.signed(x.chg) + ' (' + F.pct(x.chgPct) + ')</div></div>';
        }).join('');

        var uni = IN.universe.map(function (s) { return { s: s, q: q[MF.data.key(s)] }; }).filter(function (x) { return x.q && x.q.chgPct !== undefined && x.q.chgPct !== null; });
        var adv = uni.filter(function (x) { return x.q.chgPct > 0; }).length, dec = uni.filter(function (x) { return x.q.chgPct < 0; }).length, n = uni.length || 1;
        var avg = uni.reduce(function (a, x) { return a + x.q.chgPct; }, 0) / n, ratio = adv / Math.max(adv + dec, 1);
        var mood = ratio > 0.7 ? 'Broad buying' : ratio > 0.55 ? 'Buyers ahead' : ratio < 0.3 ? 'Broad selling' : ratio < 0.45 ? 'Sellers ahead' : 'Mixed';
        U.$('[data-breadth]', el).innerHTML = '<div class="card-h"><h2>Market breadth</h2>' + U.flowPill(mood.replace('Broad buying', 'Strong Buying').replace('Broad selling', 'Strong Selling').replace('Buyers ahead', 'Buying').replace('Sellers ahead', 'Selling').replace('Mixed', 'Neutral')) + '</div>' +
          '<div class="bar' + (adv + dec ? '' : ' neutral') + '"><i style="width:' + (100 * ratio) + '%"></i></div>' +
          '<div class="row between mt-s"><span class="up num">' + adv + ' advancing</span><span class="muted">avg ' + F.pct(avg) + '</span><span class="down num">' + dec + ' declining</span></div>' +
          '<small>' + mood + ' across ' + uni.length + ' large caps. Open the <b>Flow</b> tab to see where the volume is going.</small>';

        U.$('[data-watch]', el).innerHTML = watch.length ? watch.map(function (s) { return row(s, q[MF.data.key(s)]); }).join('') : '<div class="empty">Your watchlist is empty. Tap “+ Add”.</div>';

        var secs = Object.keys(IN.sectors).map(function (name) {
          var list = IN.sectors[name].map(function (x) { return q['NSE:' + x]; }).filter(function (x) { return x && x.chgPct !== undefined && x.chgPct !== null; });
          return { name: name, v: list.length ? list.reduce(function (a, x) { return a + x.chgPct; }, 0) / list.length : null };
        }).filter(function (x) { return x.v !== null; }).sort(function (a, b) { return b.v - a.v; });
        var mx = Math.max.apply(null, secs.map(function (x) { return Math.abs(x.v); }).concat([0.5]));
        U.$('[data-sectors]', el).innerHTML = secs.map(function (x) {
          var w = 50 * Math.abs(x.v) / mx;
          return '<div class="row" style="margin-bottom:7px"><div style="width:118px" class="ellipsis">' + x.name + '</div><div class="meter grow"><i style="' + (x.v >= 0 ? 'left:50%;background:var(--up)' : 'right:50%;background:var(--down)') + ';width:' + w + '%"></i></div><div style="width:58px" class="right num ' + U.cls(x.v) + '">' + F.pct(x.v) + '</div></div>';
        }).join('');

        uni.sort(function (a, b) { return b.q.chgPct - a.q.chgPct; });
        function mini(x) { return '<li class="tap" data-key="' + MF.data.key(x.s) + '"><div class="grow t ellipsis">' + F.esc(x.s.sym) + '</div><span class="num ' + U.cls(x.q.chgPct) + '">' + F.pct(x.q.chgPct) + '</span></li>'; }
        U.$('[data-gain]', el).innerHTML = uni.slice(0, 5).map(mini).join('');
        U.$('[data-lose]', el).innerHTML = uni.slice(-5).reverse().map(mini).join('');
      }).catch(function (e) {
        U.$('[data-err]', el).innerHTML = U.error(e, true);
        if (U.$('[data-indices] .spinner', el)) U.$('[data-indices]', el).innerHTML = '';
      });
    }

    U.on(el, 'click', '[data-key]', function (e, t) { var s = IN.get(t.getAttribute('data-key')); if (s) MF.app.openDetail(s); });
    U.on(el, 'click', '[data-retry]', function () { load(true); });
    U.$('[data-refresh]', el).onclick = function () { load(true); U.toast('Refreshed'); };
    U.$('[data-search]', el).onclick = function () { U.pickSymbol('Search stocks & indices', MF.app.openDetail); };
    U.$('[data-add]', el).onclick = function () {
      U.pickSymbol('Add to watchlist', function (s) { if (!U.inWatchlist(s)) U.toggleWatch(s); load(false); U.toast(s.sym + ' added to watchlist'); });
    };
    load(false);
    var stop = U.poll(function () { if (MF.clock.isMarketOpen() || MF.data.isDemo()) load(false); else status(); }, 15000);
    return { destroy: stop, onShow: function () { load(false); } };
  };
})(window);
