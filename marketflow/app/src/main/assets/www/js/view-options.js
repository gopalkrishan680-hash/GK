/* Options tab: option chain, open interest map, PCR, max pain and OI change through the day. */
(function (root) {
  'use strict';
  var MF = root.MF, U = MF.ui, F = MF.fmt, A = MF.analysis, IN = MF.instruments;
  MF.views = MF.views || {};

  MF.views.options = function (el, args) {
    var unders = IN.optionUnderlyings(), saved = MF.store.get('optUnder', 'NIFTY');
    var under = (args.sym && IN.isFno(args.sym.sym) && args.sym) || unders.filter(function (u) { return u.sym === saved; })[0] || unders[0];
    var expiry = null, dead = false, lastChain = null;

    el.innerHTML =
      '<div class="row between mb"><div><h1>Options</h1><small>Open interest shows where big positions sit</small></div><button class="iconbtn" data-refresh>' + U.icon('refresh') + '</button></div>' +
      '<div class="grid2 mb"><button class="btn ghost ellipsis" data-under></button><select data-exp><option>Loading…</option></select></div>' +
      '<div data-err></div><div data-out>' + U.spinner + '</div>';

    function snapKey() { return 'oi|' + MF.data.key(under) + '|' + expiry + '|' + F.isoDate(MF.clock.now()); }

    function render(chain) {
      lastChain = chain;
      var key = snapKey(), first = MF.store.get(key, null);
      if (!first) { first = A.snapshot(chain); MF.store.set(key, first); var keys = MF.store.get('oiKeys', []).concat([key]);
        while (keys.length > 6) { try { root.localStorage.removeItem('mf.' + keys.shift()); } catch (e) { keys.shift(); } }
        MF.store.set('oiKeys', keys);
      }
      var o = A.options(chain, first.t < MF.clock.now() - 60 ? first : null), rows = chain.rows, ai = rows.indexOf(o.atm), lot = IN.lotSize(under.sym);
      var near = rows.slice(Math.max(0, ai - 10), ai + 11), mx = 1;
      near.forEach(function (r) { mx = Math.max(mx, r.ce ? r.ce.oi : 0, r.pe ? r.pe.oi : 0); });

      var html = '<div class="card"><div class="card-h"><h2>' + F.esc(U.symLabel(under)) + ' <span class="num">' + F.price(o.spot) + '</span></h2>' + U.verdictPill(o.bias.replace(' lean', '').replace('Bullish', 'Mildly Bullish').replace('Bearish', 'Mildly Bearish')) + '</div>' +
        '<div class="grid3"><div class="stat"><div class="k">PCR (OI)</div><div class="v">' + F.num(o.pcr, 2) + '</div></div><div class="stat"><div class="k">Max pain</div><div class="v">' + F.num(o.maxPain, 0) + '</div></div><div class="stat"><div class="k">ATM straddle</div><div class="v">' + F.num(o.straddle, 1) + '</div></div>' +
        '<div class="stat"><div class="k">Support (put OI)</div><div class="v up">' + F.num(o.support.strike, 0) + '</div></div><div class="stat"><div class="k">Resistance (call OI)</div><div class="v down">' + F.num(o.resistance.strike, 0) + '</div></div><div class="stat"><div class="k">ATM IV</div><div class="v">' + (o.atm.ce && o.atm.ce.iv ? F.num(o.atm.ce.iv, 1) + '%' : '–') + '</div></div></div>' +
        '<div class="mt-s">' + o.notes.map(function (n) { return '<div class="check"><span class="dot ' + (n.dir > 0 ? 'up' : n.dir < 0 ? 'down' : '') + '"></span><span>' + F.esc(n.text) + '</span></div>'; }).join('') + '</div></div>';

      html += '<div class="card"><div class="card-h"><h2>Open interest by strike</h2><small><span class="down">■</span> calls &nbsp;<span class="up">■</span> puts</small></div>' + near.map(function (r) {
        return '<div class="oi-row' + (r === o.atm ? ' atm' : '') + '"><div class="l"><i style="width:' + (100 * (r.ce ? r.ce.oi : 0) / mx) + '%"></i></div><div class="s">' + F.num(r.strike, 0) + '</div><div class="r"><i style="width:' + (100 * (r.pe ? r.pe.oi : 0) / mx) + '%"></i></div></div>';
      }).join('') + '<small class="mt-s" style="display:block">Option writers (usually large traders) defend strikes with the tallest bars: tall put OI below the price is support, tall call OI above is resistance.</small></div>';

      if (o.change) {
        var ch = o.change.rows.filter(function (r) { return near.some(function (n) { return n.strike === r.strike; }); }), cm = 1;
        ch.forEach(function (r) { cm = Math.max(cm, Math.abs(r.ce), Math.abs(r.pe)); });
        html += '<div class="card"><div class="card-h"><h2>OI change since ' + F.time(o.change.since) + '</h2><small>this app\'s first reading today</small></div>' +
          '<div class="grid2 mb"><div class="stat"><div class="k">Call OI change</div><div class="v ' + U.cls(-o.change.ce) + '">' + F.signed(o.change.ce, 0) + '</div></div><div class="stat"><div class="k">Put OI change</div><div class="v ' + U.cls(o.change.pe) + '">' + F.signed(o.change.pe, 0) + '</div></div></div>' +
          ch.map(function (r) { return '<div class="oi-row"><div class="l"><i style="width:' + (100 * Math.abs(r.ce) / cm) + '%;opacity:' + (r.ce >= 0 ? 1 : 0.35) + '"></i></div><div class="s">' + F.num(r.strike, 0) + '</div><div class="r"><i style="width:' + (100 * Math.abs(r.pe) / cm) + '%;opacity:' + (r.pe >= 0 ? 1 : 0.35) + '"></i></div></div>'; }).join('') +
          '<small class="mt-s" style="display:block">Solid bars = OI added (new positions). Faded bars = OI reduced (positions closed).</small></div>';
      } else html += '<div class="note">OI change will appear after the next refresh. The app stores its first reading of the day and compares later readings against it, so open this screen early in the session.</div>';

      html += '<div class="card flush"><div class="scroll-y"><table class="t"><thead><tr><th>Call OI</th><th>IV</th><th>Call LTP</th><th style="text-align:center">Strike</th><th>Put LTP</th><th>IV</th><th>Put OI</th></tr></thead><tbody>' + rows.slice(Math.max(0, ai - 15), ai + 16).map(function (r) {
        function oi(x) { return x ? F.compact(lot ? x.oi : x.oi) : '–'; }
        return '<tr class="' + (r === o.atm ? 'atm' : '') + '"><td>' + oi(r.ce) + '</td><td>' + (r.ce && r.ce.iv ? F.num(r.ce.iv, 1) : '–') + '</td><td>' + (r.ce ? F.price(r.ce.ltp) : '–') + '</td><td class="strike">' + F.num(r.strike, 0) + '</td><td>' + (r.pe ? F.price(r.pe.ltp) : '–') + '</td><td>' + (r.pe && r.pe.iv ? F.num(r.pe.iv, 1) : '–') + '</td><td>' + oi(r.pe) + '</td></tr>';
      }).join('') + '</tbody></table></div></div>' + (lot ? '<small class="muted">Lot size ' + lot + '. Updated ' + F.time(MF.clock.now()) + '.</small>' : '');
      U.$('[data-out]', el).innerHTML = html;
    }

    function loadChain(force) {
      if (!expiry) return;
      MF.data.optionChain(under, expiry, force ? 0 : 20).then(function (c) {
        if (dead) return;
        U.$('[data-err]', el).innerHTML = '';
        if (!c.rows.length) { U.$('[data-out]', el).innerHTML = '<div class="empty">No option chain returned for this expiry.</div>'; return; }
        render(c);
      }).catch(function (e) { if (!dead) { U.$('[data-err]', el).innerHTML = U.error(e, true); if (!lastChain) U.$('[data-out]', el).innerHTML = ''; } });
    }

    function loadUnder() {
      U.$('[data-under]', el).textContent = U.symLabel(under) + ' ▾';
      MF.store.set('optUnder', under.sym);
      lastChain = null; expiry = null;
      U.$('[data-out]', el).innerHTML = U.spinner; U.$('[data-err]', el).innerHTML = '';
      MF.data.expiries(under).then(function (ex) {
        if (dead) return;
        var sel = U.$('[data-exp]', el);
        if (!ex.length) { sel.innerHTML = '<option>No expiries</option>'; U.$('[data-out]', el).innerHTML = '<div class="empty">No upcoming expiries found for ' + F.esc(under.sym) + '.</div>'; return; }
        sel.innerHTML = ex.map(function (d) { return '<option value="' + d + '">' + F.date(Date.parse(d + 'T06:00:00Z') / 1000) + '</option>'; }).join('');
        expiry = ex[0]; loadChain(false);
      }).catch(function (e) { if (!dead) { U.$('[data-err]', el).innerHTML = U.error(e, true); U.$('[data-out]', el).innerHTML = ''; } });
    }

    U.$('[data-under]', el).onclick = function () {
      U.pickSymbol('Choose underlying', function (s) { under = s; loadUnder(); }, { suggest: unders.slice(0, 14), filter: function (s) { return IN.isFno(s.sym); } });
    };
    U.$('[data-exp]', el).onchange = function (e) { expiry = e.target.value; lastChain = null; U.$('[data-out]', el).innerHTML = U.spinner; loadChain(false); };
    U.$('[data-refresh]', el).onclick = function () { loadChain(true); U.toast('Refreshed'); };
    U.on(el, 'click', '[data-retry]', function () { if (expiry) loadChain(true); else loadUnder(); });

    loadUnder();
    var stop = U.poll(function () { if (MF.clock.isMarketOpen() || MF.data.isDemo()) loadChain(false); }, 30000);
    return { destroy: function () { dead = true; stop(); } };
  };
})(window);
