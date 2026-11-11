/* Market replay: plays history forward candle by candle like a live market, with paper trading and live strategy signals. */
(function (root) {
  'use strict';
  var MF = root.MF, U = MF.ui, F = MF.fmt, BT = MF.backtest, ST = MF.strategies;
  MF.views = MF.views || {};
  var SPEEDS = [[1, '1×'], [2, '2×'], [5, '5×'], [10, '10×'], [30, '30×']];
  var TFS = ['1m', '5m', '15m', '1h', '1D'];

  MF.views.replay = function (el, args, bar) {
    var bt = MF.btState(), sym = args.sym || MF.instruments.get(bt.sym), tfId = args.tf && TFS.indexOf(args.tf) >= 0 ? args.tf : '15m';
    var stratId = args.strategy || '', userParams = args.params || null;
    var candles = [], idx = 0, sub = 3, startIdx = 0, playing = false, timer = null, speed = 2, ticks = true, dead = false, chart = null;
    var sig = null, markers = [], cost = (+bt.costPct || 0) / 100;
    var acct = null; // paper account
    bar.sub.textContent = 'Practice on past data as if it were live';

    el.innerHTML =
      '<div class="card"><div class="grid2"><div class="field"><label class="f">Stock or index</label><button class="btn ghost block ellipsis" data-sym></button></div>' +
      '<div class="field"><label class="f">Candle size</label><select data-tf>' + TFS.map(function (t) { return '<option value="' + t + '">' + MF.data.tf(t).label + '</option>'; }).join('') + '</select></div></div>' +
      '<div class="field"><label class="f">Strategy signals to show</label><select data-strat></select></div>' +
      '<div class="field"><label class="f">Start replay from: <b data-startlbl></b></label><input type="range" min="0" max="100" value="60" data-start style="padding:0"></div></div>' +
      '<div data-err></div>' +
      '<div class="row between"><div><div class="big" data-px>–</div><div class="num" data-chg>&nbsp;</div></div><div class="right"><div class="pill info" data-clock>–</div><div class="muted" style="font-size:11px;margin-top:4px" data-left></div></div></div>' +
      '<div class="card mt" style="padding:6px"><div class="chart" data-chart></div><div data-legend></div></div>' +
      '<div class="card hidden" data-sigcard></div>' +
      '<div class="card"><div class="card-h"><h2>Paper trading</h2><small>no real orders are placed</small></div>' +
      '<div class="row mb"><div class="grow"><label class="f">Quantity</label><input type="number" inputmode="numeric" data-qty value="10" min="1"></div><button class="btn buy" data-buy style="margin-top:18px">Buy</button><button class="btn sell" data-sell style="margin-top:18px">Sell</button><button class="btn ghost" data-flat style="margin-top:18px">Close</button></div>' +
      '<div class="grid3" data-acct></div><div data-log class="mt-s"></div></div>' +
      '<div class="replay-bar"><div class="row"><button class="btn" data-play style="width:96px">▶ Play</button><button class="btn ghost" data-step>Next ▸</button><select data-speed style="width:84px">' + SPEEDS.map(function (s) { return '<option value="' + s[0] + '">' + s[1] + '</option>'; }).join('') + '</select>' +
      '<label class="row grow" style="justify-content:flex-end;font-size:12px"><input type="checkbox" data-ticks checked style="width:20px;height:20px;min-height:0"> Live ticks</label></div></div>';

    U.$('[data-tf]', el).value = tfId; U.$('[data-speed]', el).value = String(speed);
    chart = U.chart(U.$('[data-chart]', el));

    function strategyOptions() {
      var intra = MF.data.tf(tfId).sec < 86400, sel = U.$('[data-strat]', el);
      var list = ST.list.filter(function (s) { return intra || !s.intradayOnly; });
      if (stratId && !list.some(function (s) { return s.id === stratId; })) stratId = '';
      sel.innerHTML = '<option value="">None — I will trade manually</option>' + list.map(function (s) { return '<option value="' + s.id + '"' + (s.id === stratId ? ' selected' : '') + '>' + s.name + '</option>'; }).join('');
    }

    function cfg() { var c = MF.btCfg(bt), s = ST.byId(stratId); c.intraday = MF.data.tf(tfId).sec < 86400 && (tfId === '1m' || tfId === '5m' || tfId === '15m' || (s && s.intradayOnly)); c.allowShort = c.intraday ? true : !!bt.allowShort; return c; }

    function resetAcct() { acct = { qty: 0, avg: 0, realized: 0, log: [] }; }
    function price() { return cur().c; }

    /* The candle currently on screen (partially formed when live ticks are on). */
    function cur() {
      var c = candles[idx];
      if (sub >= 3) return c;
      var path = c.c >= c.o ? [c.o, c.l, c.h, c.c] : [c.o, c.h, c.l, c.c], seen = path.slice(0, sub + 1);
      return { t: c.t, o: c.o, h: Math.max.apply(null, seen), l: Math.min.apply(null, seen), c: path[sub], v: Math.round(c.v * (sub + 1) / 4) };
    }

    function order(dir, qty, why) {
      if (!candles.length || !qty) return;
      var px = price(), fee = px * qty * cost;
      if (acct.qty !== 0 && Math.sign(acct.qty) !== dir) {
        var closeQty = Math.min(qty, Math.abs(acct.qty)), pnl = (px - acct.avg) * closeQty * Math.sign(acct.qty);
        acct.realized += pnl - px * closeQty * cost;
        acct.qty += dir * closeQty;
        var rest = qty - closeQty;
        acct.log.unshift({ t: candles[idx].t, text: (dir > 0 ? 'Bought ' : 'Sold ') + closeQty + ' @ ' + F.price(px) + ' (closed, ' + F.rupee(pnl) + ')', pnl: pnl });
        if (acct.qty === 0) acct.avg = 0;
        if (rest > 0) { acct.qty = dir * rest; acct.avg = px; acct.realized -= px * rest * cost; acct.log.unshift({ t: candles[idx].t, text: (dir > 0 ? 'Bought ' : 'Sold ') + rest + ' @ ' + F.price(px) }); }
      } else {
        acct.avg = (acct.avg * Math.abs(acct.qty) + px * qty) / (Math.abs(acct.qty) + qty);
        acct.qty += dir * qty; acct.realized -= fee;
        acct.log.unshift({ t: candles[idx].t, text: (dir > 0 ? 'Bought ' : 'Sold ') + qty + ' @ ' + F.price(px) + (why ? ' (' + why + ')' : '') });
      }
      markers.push({ t: candles[idx].t, side: dir > 0 ? 'buy' : 'sell', text: 'You' });
      chart.setMarkers(markers); renderAcct();
    }

    function renderAcct() {
      var px = candles.length ? price() : 0, unreal = acct.qty ? (px - acct.avg) * acct.qty : 0, total = acct.realized + unreal;
      U.$('[data-acct]', el).innerHTML =
        '<div class="stat"><div class="k">Position</div><div class="v ' + U.cls(acct.qty) + '" style="font-size:13px">' + (acct.qty ? (acct.qty > 0 ? 'Long ' : 'Short ') + Math.abs(acct.qty) + ' @ ' + F.price(acct.avg) : 'None') + '</div></div>' +
        '<div class="stat"><div class="k">Open P&L</div><div class="v ' + U.cls(unreal) + '">' + F.rupee(unreal) + '</div></div>' +
        '<div class="stat"><div class="k">Total P&L</div><div class="v ' + U.cls(total) + '">' + F.rupee(total) + '</div></div>';
      U.$('[data-log]', el).innerHTML = acct.log.slice(0, 6).map(function (l) { return '<div class="kv"><span class="muted">' + F.dateTime(l.t, MF.data.tf(tfId).sec < 86400) + '</span><span>' + F.esc(l.text) + '</span></div>'; }).join('');
    }

    function renderHead() {
      var c = cur(), intra = MF.data.tf(tfId).sec < 86400, base = candles[startIdx].c, d = c.c - base;
      U.$('[data-px]', el).textContent = F.price(c.c);
      var ch = U.$('[data-chg]', el); ch.className = 'num ' + U.cls(d); ch.textContent = F.signed(d) + ' (' + F.pct(100 * d / base) + ') since start';
      U.$('[data-clock]', el).textContent = F.dateTime(c.t, intra);
      U.$('[data-left]', el).textContent = (candles.length - 1 - idx) + ' candles left';
    }

    function renderStrategy() {
      var card = U.$('[data-sigcard]', el);
      if (!sig) { card.classList.add('hidden'); return; }
      var s = ST.byId(stratId), r = BT.run(candles.slice(startIdx, idx + 1), sig.pos.slice(startIdx, idx + 1), cfg()), m = r.metrics, now = sig.pos[idx];
      card.classList.remove('hidden');
      card.innerHTML = '<div class="card-h"><h2>' + s.name + '</h2><span class="pill ' + (now > 0 ? 'up' : now < 0 ? 'down' : '') + '">' + (now > 0 ? 'Wants LONG' : now < 0 ? 'Wants SHORT' : 'Wants no position') + '</span></div>' +
        '<div class="grid3"><div class="stat"><div class="k">Strategy P&L</div><div class="v ' + U.cls(m.netProfit) + '">' + F.rupee(m.netProfit) + '</div></div><div class="stat"><div class="k">Trades / win rate</div><div class="v">' + m.trades + ' / ' + F.num(m.winRate || 0, 0) + '%</div></div><div class="stat"><div class="k">Max drawdown</div><div class="v">-' + F.num(m.maxDrawdownPct || 0, 1) + '%</div></div></div>' +
        '<small class="mt-s" style="display:block">On ₹' + F.num(cfg().capital, 0) + ' since the replay started, after costs. Signals appear when a candle closes and are filled at the next candle\'s open.</small>';
    }

    function onBarClose() {
      if (sig) {
        var a = idx > 0 ? sig.pos[idx - 1] : 0, b = sig.pos[idx];
        if (a !== b) {
          var txt = b > 0 ? 'BUY' : b < 0 ? 'SELL' : 'EXIT';
          markers.push({ t: candles[idx].t, side: b > 0 ? 'buy' : b < 0 ? 'sell' : 'exit', text: txt });
          chart.setMarkers(markers);
          if (speed <= 10) U.toast(ST.byId(stratId).name + ': ' + txt + ' signal at ' + F.price(candles[idx].c), 1800);
        }
        chart.updateLines(candles[idx].t, sig.lines.map(function (l) { return l.data[idx]; }));
        renderStrategy();
      }
    }

    function advance() {
      if (!candles.length) return;
      if (ticks && sub < 3) sub++;
      else {
        if (idx >= candles.length - 1) { pause(); U.toast('Replay finished — end of data'); return; }
        idx++; sub = ticks ? 0 : 3;
      }
      chart.update(cur());
      if (sub === 3) onBarClose();
      renderHead(); renderAcct();
    }

    function play() {
      if (!candles.length || playing) return;
      if (idx >= candles.length - 1 && sub === 3) { U.toast('Already at the end. Move the start slider to replay again.'); return; }
      playing = true; U.$('[data-play]', el).textContent = '❚❚ Pause';
      if (root.AndroidBridge) root.AndroidBridge.keepScreenOn(true);
      schedule();
    }
    function schedule() { clearInterval(timer); timer = setInterval(advance, Math.max(30, 1000 / speed / (ticks ? 4 : 1))); }
    function pause() { playing = false; clearInterval(timer); timer = null; U.$('[data-play]', el).textContent = '▶ Play'; if (root.AndroidBridge) root.AndroidBridge.keepScreenOn(false); }

    function resetTo(pct) {
      pause();
      var warm = Math.min(60, Math.floor(candles.length / 3));
      startIdx = Math.max(warm, Math.min(candles.length - 2, Math.round((candles.length - 1) * pct / 100)));
      idx = startIdx; sub = 3; markers = []; resetAcct();
      var intra = MF.data.tf(tfId).sec < 86400, shown = candles.slice(0, idx + 1);
      chart.setIntraday(intra); chart.setCandles(shown); chart.setMarkers([]);
      var s = ST.byId(stratId);
      sig = s ? s.compute(candles, userParams && args.strategy === stratId ? userParams : ST.defaults(s), { allowShort: cfg().allowShort }) : null;
      chart.setLines(shown, sig ? sig.lines : []);
      U.$('[data-legend]', el).innerHTML = sig ? U.legend(sig.lines) : '';
      chart.showLast(Math.min(shown.length, 90));
      U.$('[data-startlbl]', el).textContent = F.dateTime(candles[idx].t, intra);
      renderHead(); renderAcct(); renderStrategy();
    }

    function load() {
      pause(); candles = [];
      U.$('[data-sym]', el).textContent = U.symLabel(sym) + ' ▾';
      U.$('[data-err]', el).innerHTML = U.spinner;
      strategyOptions();
      MF.data.candles(sym, tfId, MF.data.tf(tfId).maxDays > 720 ? 1100 : MF.data.tf(tfId).maxDays).then(function (c) {
        if (dead) return;
        U.$('[data-err]', el).innerHTML = MF.data.isDemo() ? '<div class="note">Demo mode: this is simulated price history. Connect Groww in Settings to replay real market data.</div>' : '';
        if (c.length < 30) { U.$('[data-err]', el).innerHTML = '<div class="err">Not enough history for this symbol and candle size.</div>'; return; }
        candles = c; resetTo(+U.$('[data-start]', el).value);
      }).catch(function (e) { if (!dead) U.$('[data-err]', el).innerHTML = U.error(e, true); });
    }

    U.$('[data-sym]', el).onclick = function () { U.pickSymbol('Choose stock or index', function (s) { sym = s; load(); }); };
    U.$('[data-tf]', el).onchange = function (e) { tfId = e.target.value; load(); };
    U.$('[data-strat]', el).onchange = function (e) { stratId = e.target.value; if (candles.length) resetTo(+U.$('[data-start]', el).value); };
    U.$('[data-start]', el).oninput = function (e) { if (candles.length) resetTo(+e.target.value); };
    U.$('[data-play]', el).onclick = function () { if (playing) pause(); else play(); };
    U.$('[data-step]', el).onclick = function () {
      pause(); if (!candles.length) return;
      if (sub === 3) { if (idx >= candles.length - 1) { U.toast('End of data'); return; } idx++; }
      sub = 3; chart.update(cur()); onBarClose(); renderHead(); renderAcct();
    };
    U.$('[data-speed]', el).onchange = function (e) { speed = +e.target.value; if (playing) schedule(); };
    U.$('[data-ticks]', el).onchange = function (e) { ticks = e.target.checked; if (!ticks && candles.length && sub < 3) { sub = 3; chart.update(cur()); onBarClose(); renderHead(); } if (playing) schedule(); };
    function qty() { return Math.max(1, Math.floor(+U.$('[data-qty]', el).value || 0)); }
    U.$('[data-buy]', el).onclick = function () { order(1, qty()); };
    U.$('[data-sell]', el).onclick = function () { order(-1, qty()); };
    U.$('[data-flat]', el).onclick = function () { if (acct.qty) order(-Math.sign(acct.qty), Math.abs(acct.qty)); else U.toast('No open position'); };
    U.on(el, 'click', '[data-retry]', load);

    resetAcct(); renderAcct(); load();
    return { destroy: function () { dead = true; pause(); chart.destroy(); } };
  };
})(window);
