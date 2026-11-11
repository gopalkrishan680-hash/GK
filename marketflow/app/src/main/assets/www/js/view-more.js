/* More tab: alerts, Groww connection settings, strategy guide, help and disclaimer. */
(function (root) {
  'use strict';
  var MF = root.MF, U = MF.ui, F = MF.fmt, ST = MF.strategies;
  MF.views = MF.views || {};

  /* ---------------- price alerts ---------------- */
  var Alerts = MF.alerts = {
    list: function () { return MF.store.get('alerts', []); },
    save: function (l) { MF.store.set('alerts', l); Alerts.sync(); },
    notify: function (title, text) {
      var sent = false;
      try { if (root.AndroidBridge) sent = root.AndroidBridge.notify(title, text); } catch (e) { /* ignore */ }
      U.toast(title + ' — ' + text, 5000);
      return sent;
    },
    ensurePermission: function () { try { if (root.AndroidBridge && !root.AndroidBridge.canNotify()) root.AndroidBridge.requestNotifications(); } catch (e) { /* ignore */ } },
    /* Gives active alerts and the API token to the native background checker. */
    sync: function () {
      if (!root.AndroidBridge) return;
      var s = MF.data.settings(), tok = '';
      if (s.mode === 'groww') tok = s.authMode === 'keysecret' ? ((MF.store.get('growwToken', {}) || {}).token || '') : (s.accessToken || '').trim();
      var act = Alerts.list().filter(function (a) { return a.active; }).map(function (a) { return { id: a.id, ex: a.ex, sym: a.sym, name: a.name, op: a.op, price: a.price }; });
      try { root.AndroidBridge.syncAlerts(JSON.stringify(act), tok); } catch (e) { /* ignore */ }
    },
    check: function () {
      var l = Alerts.list(), act = l.filter(function (a) { return a.active; });
      if (!act.length) return;
      var syms = [], seen = {};
      act.forEach(function (a) { var k = a.ex + ':' + a.sym; if (!seen[k]) { seen[k] = 1; syms.push({ ex: a.ex, sym: a.sym }); } });
      MF.data.quotes(syms, 10).then(function (q) {
        var changed = false;
        l.forEach(function (a) {
          var x = q[a.ex + ':' + a.sym];
          if (!a.active || !x || x.ltp === undefined) return;
          if (a.op === 'above' ? x.ltp >= a.price : x.ltp <= a.price) {
            a.active = false; a.triggeredAt = MF.clock.now(); changed = true;
            Alerts.notify(a.name + (a.op === 'above' ? ' crossed above ' : ' fell below ') + F.price(a.price), 'Last price ' + F.price(x.ltp));
          }
        });
        if (changed) Alerts.save(l);
      }).catch(function () { /* next poll */ });
    },
    onResume: function () {
      if (!root.AndroidBridge) return;
      try {
        var ids = JSON.parse(root.AndroidBridge.takeTriggered() || '[]');
        if (!ids.length) return;
        var l = Alerts.list();
        l.forEach(function (a) { if (ids.indexOf(a.id) >= 0) { a.active = false; a.triggeredAt = MF.clock.now(); } });
        MF.store.set('alerts', l);
      } catch (e) { /* ignore */ }
    },
    start: function () { Alerts.onResume(); Alerts.sync(); U.poll(function () { if (MF.clock.isMarketOpen() || MF.data.isDemo()) Alerts.check(); }, 20000); },
    addSheet: function (sym, ltp, done) {
      var s = U.sheet('Price alert — ' + U.symLabel(sym),
        '<p class="muted">' + (ltp ? 'Last price ' + F.price(ltp) + '. ' : '') + 'You get a notification when the price crosses your level.</p>' +
        '<div class="seg mb"><button data-op="above" class="active">Rises above</button><button data-op="below">Falls below</button></div>' +
        '<div class="field"><label class="f">Price (₹)</label><input type="number" inputmode="decimal" step="0.05" data-price value="' + (ltp ? (Math.round(ltp * 100.5) / 100) : '') + '"></div>' +
        '<button class="btn block" data-save>Create alert</button>' +
        '<small class="mt" style="display:block">While the app is closed, alerts are checked about every 15 minutes during market hours (Groww connection required). With the app open they are checked every 20 seconds.</small>');
      var op = 'above';
      U.on(s.body, 'click', '[data-op]', function (e, b) {
        op = b.getAttribute('data-op'); U.$$('[data-op]', s.body).forEach(function (x) { x.classList.toggle('active', x === b); });
        if (ltp) U.$('[data-price]', s.body).value = Math.round(ltp * (op === 'above' ? 100.5 : 99.5)) / 100;
      });
      U.$('[data-save]', s.body).onclick = function () {
        var p = parseFloat(U.$('[data-price]', s.body).value);
        if (!p || p <= 0) { U.toast('Enter a price'); return; }
        var l = Alerts.list();
        l.unshift({ id: 'a' + Date.now(), ex: sym.ex, sym: sym.sym, name: U.symLabel(sym), op: op, price: p, active: true, created: MF.clock.now() });
        Alerts.save(l); Alerts.ensurePermission(); s.close(); U.toast('Alert created'); if (done) done();
      };
    }
  };

  function alertsPage(el) {
    function render() {
      var l = Alerts.list();
      el.innerHTML = '<button class="btn block mb mt-s" data-new>+ New price alert</button>' + (l.length ? '<div class="card flush"><ul class="list">' + l.map(function (a) {
        return '<li><div class="grow"><div class="t">' + F.esc(a.name) + ' ' + (a.op === 'above' ? '≥' : '≤') + ' ' + F.price(a.price) + '</div><div class="d">' + (a.active ? 'Waiting · created ' + F.dateTime(a.created) : 'Triggered ' + F.dateTime(a.triggeredAt || a.created)) + '</div></div>' +
          '<span class="pill ' + (a.active ? 'info' : 'warn') + '">' + (a.active ? 'Active' : 'Done') + '</span><button class="iconbtn" data-del="' + a.id + '">' + U.icon('trash') + '</button></li>';
      }).join('') + '</ul></div>' : '<div class="empty">No alerts yet. Create one here or with the bell icon on any stock page.</div>') +
        '<div class="note">For “strong buying / selling” notifications, switch on the two options at the bottom of the <b>Flow</b> tab.</div>';
    }
    U.on(el, 'click', '[data-new]', function () { U.pickSymbol('Alert for…', function (s) { MF.data.quotes([s]).then(function (q) { return (q[MF.data.key(s)] || {}).ltp; }, function () { return null; }).then(function (ltp) { Alerts.addSheet(s, ltp, render); }); }); });
    U.on(el, 'click', '[data-del]', function (e, b) { Alerts.save(Alerts.list().filter(function (a) { return a.id !== b.getAttribute('data-del'); })); render(); });
    render();
  }

  /* ---------------- settings ---------------- */
  function settingsPage(el) {
    var s = MF.data.settings();
    function render() {
      var g = s.mode === 'groww';
      el.innerHTML = '<div class="card mt-s"><h2>Data source</h2><div class="seg mb"><button data-mode="demo" class="' + (g ? '' : 'active') + '">Demo (simulated)</button><button data-mode="groww" class="' + (g ? 'active' : '') + '">Groww (live)</button></div>' +
        (g ? '<div class="seg mb"><button data-auth="token" class="' + (s.authMode !== 'keysecret' ? 'active' : '') + '">Access token</button><button data-auth="keysecret" class="' + (s.authMode === 'keysecret' ? 'active' : '') + '">API key + secret</button></div>' +
          (s.authMode === 'keysecret'
            ? '<div class="field"><label class="f">API key</label><textarea data-s="apiKey" spellcheck="false" autocomplete="off">' + F.esc(s.apiKey) + '</textarea></div><div class="field"><label class="f">API secret</label><input type="password" data-s="apiSecret" autocomplete="off" value="' + F.esc(s.apiSecret) + '"></div><small>The app creates a fresh token from the key and secret when needed. Groww requires you to approve the key once a day on its API keys page.</small>'
            : '<div class="field"><label class="f">Access token</label><textarea data-s="accessToken" spellcheck="false" autocomplete="off" placeholder="Paste the token from Groww here">' + F.esc(s.accessToken) + '</textarea></div><small>Groww access tokens expire every day at 6:00 AM. Paste a new one each morning, or use “API key + secret”.</small>') +
          '<button class="btn block mt" data-test>Save and test connection</button><div data-result class="mt-s"></div>'
          : '<p class="muted">Demo mode shows <b>simulated</b> prices so you can try every screen. Nothing in demo mode reflects the real market.</p>') + '</div>' +
        '<div class="card"><h2>How to connect your Groww account</h2><ol class="steps"><li>Open <b>groww.in</b> in a browser and log in.</li><li>Tap your profile picture → <b>Settings</b> → <b>Trading APIs</b>.</li><li>Buy the Trading API subscription if you have not already (Groww charges a monthly fee for it).</li><li>Tap <b>Generate API keys</b>, choose <b>Access Token</b> (or create an API key + secret) and copy it.</li><li>Come back here, choose <b>Groww (live)</b>, paste it and tap <b>Save and test connection</b>.</li></ol></div>' +
        '<div class="card"><h2>Privacy and safety</h2><p class="muted">Your token is stored only on this phone and is sent only to Groww\'s official server (api.groww.in). This app only <b>reads</b> market data — it has no code to place, modify or cancel orders.</p>' +
        '<button class="btn ghost block" data-clear>Remove saved Groww credentials</button></div>';
    }
    function collect() { U.$$('[data-s]', el).forEach(function (i) { s[i.getAttribute('data-s')] = i.value.trim(); }); }
    U.on(el, 'click', '[data-mode]', function (e, b) { collect(); s.mode = b.getAttribute('data-mode'); MF.data.saveSettings(s); MF.app.banner(); Alerts.sync(); render(); });
    U.on(el, 'click', '[data-auth]', function (e, b) { collect(); s.authMode = b.getAttribute('data-auth'); MF.data.saveSettings(s); render(); });
    U.on(el, 'click', '[data-test]', function (e, b) {
      collect(); MF.data.saveSettings(s); MF.store.set('growwToken', null);
      var out = U.$('[data-result]', el); out.innerHTML = U.spinner; b.disabled = true;
      MF.data.test().then(function (msg) { out.innerHTML = '<div class="note" style="border-color:rgba(34,197,94,.4);background:var(--up-bg);color:#b9f5cf">✓ ' + F.esc(msg) + '</div>'; Alerts.sync(); }, function (err) { out.innerHTML = U.error(err); }).then(function () { b.disabled = false; });
    });
    U.on(el, 'click', '[data-clear]', function () {
      U.confirm('Remove credentials?', 'The saved Groww token, API key and secret will be deleted from this phone and the app will return to demo mode.', 'Remove', function () {
        s = { mode: 'demo', authMode: 'token', accessToken: '', apiKey: '', apiSecret: '' }; MF.data.saveSettings(s); MF.store.set('growwToken', null); MF.app.banner(); Alerts.sync(); render(); U.toast('Credentials removed');
      });
    });
    render();
  }

  /* ---------------- guides ---------------- */
  function strategyPage(el) {
    var label = { intraday: 'Intraday', swing: 'Swing', long: 'Long term' };
    el.innerHTML = '<div class="note mt-s">Every strategy here is a fixed set of rules, so it can be tested on history. None of them wins all the time. Use the Backtest tab to see how each one behaved on the stock and period you care about.</div>' +
      ST.list.map(function (s) {
        return '<div class="card"><div class="card-h"><h2>' + s.name + '</h2><div>' + s.styles.map(function (x) { return '<span class="pill info">' + label[x] + '</span>'; }).join(' ') + '</div></div><p>' + s.summary + '</p><p class="muted">' + s.logic + '</p><button class="btn small ghost" data-sid="' + s.id + '" data-style="' + s.styles[0] + '">Backtest this</button></div>';
      }).join('');
    U.on(el, 'click', '[data-sid]', function (e, b) {
      var st = MF.btState(), style = b.getAttribute('data-style'), d = MF.backtest.defaultsFor(style);
      st.style = style; st.strategy = b.getAttribute('data-sid'); st.tf = style === 'intraday' ? '15m' : '1D'; st.days = style === 'intraday' ? 120 : 1825; st.costPct = d.costPct; st.slippagePct = d.slippagePct; st.allowShort = d.allowShort;
      MF.store.set('bt', st); MF.app.show('backtest');
    });
  }

  function helpPage(el) {
    el.innerHTML = '<div class="card mt-s"><h2>What this app can and cannot tell you</h2><p>The exchange does not publish who is buying or selling in real time. What <i>is</i> visible is the footprint large orders leave behind — and that is what this app measures:</p>' +
      '<div class="check"><span class="dot up"></span><span><b>Volume spikes.</b> A candle with 3× the normal volume means big participants were active at that price.</span></div>' +
      '<div class="check"><span class="dot up"></span><span><b>VWAP.</b> The average price paid today. Price above VWAP = buyers are in profit and in control; below = sellers are.</span></div>' +
      '<div class="check"><span class="dot up"></span><span><b>Up-volume vs down-volume and money flow.</b> Whether the volume came on rising or falling candles, and whether candles close near their highs or lows.</span></div>' +
      '<div class="check"><span class="dot up"></span><span><b>Market depth.</b> Pending buy and sell orders. Useful, but orders can be cancelled, so it is the weakest clue.</span></div>' +
      '<div class="check"><span class="dot up"></span><span><b>Open interest (Options tab).</b> Strikes with the largest put OI act as support and the largest call OI as resistance. Rising OI shows new positions being built.</span></div>' +
      '<p class="mt-s">These clues raise or lower the odds of a move. They never make it certain.</p></div>' +
      '<div class="card"><h2>Reading the flow score</h2><div class="kv"><span class="up">+45 or more</span><span>Strong Buying</span></div><div class="kv"><span class="up">+15 to +44</span><span>Buying</span></div><div class="kv"><span>−14 to +14</span><span>Neutral</span></div><div class="kv"><span class="down">−15 to −44</span><span>Selling</span></div><div class="kv"><span class="down">−45 or less</span><span>Strong Selling</span></div></div>' +
      '<div class="card"><h2>Backtest and replay</h2><p><b>Backtest</b> runs a strategy over years of candles in a second and reports profit, drawdown and win rate after brokerage and slippage.</p><p><b>Replay</b> plays the same history forward one candle at a time, like a live market, so you can practise entries and exits with paper money and watch strategy signals appear.</p><p class="muted">Signals are taken at a candle\'s close and filled at the next candle\'s open, so results never use information from the future.</p></div>' +
      '<div class="card"><h2>Data not available in this app</h2><p class="muted">FII/DII daily figures, bulk and block deals and delivery percentages are published by the exchanges after market hours and are not part of the Groww API, so they are not shown.</p></div>';
  }

  MF.views.disclaimer = function (first) {
    var s = U.sheet('Important', '<p><b>GK MarketFlow is an analysis and learning tool. It is not investment advice.</b></p><p>Flow scores, outlooks, strategy signals and backtest results describe past and present data. They do not predict the future and can be wrong. Trading in stocks and especially in futures and options can lose your entire capital.</p><p>Decide for yourself, use a stop-loss, and consult a SEBI-registered adviser if you need advice. The app never places orders.</p>' +
      (first && MF.data.isDemo() ? '<div class="note">The app starts in <b>demo mode</b> with simulated prices. Connect your Groww Trading API in <b>More → Groww connection</b> to see the real market.</div>' : '') +
      '<button class="btn block" data-ok>I understand</button>', function () { MF.store.set('disclaimerSeen', true); });
    U.$('[data-ok]', s.body).onclick = s.close;
  };

  MF.views.more = function (el, args) {
    var ver = ''; try { ver = root.AndroidBridge ? root.AndroidBridge.version() : 'dev'; } catch (e) { /* ignore */ }
    var items = [
      ['alerts', 'Price alerts', function () { var n = Alerts.list().filter(function (a) { return a.active; }).length; return n ? n + ' active' : 'Get notified at your price'; }, alertsPage],
      ['settings', 'Groww connection', function () { return MF.data.isDemo() ? 'Demo mode — tap to connect live data' : 'Live data from Groww'; }, settingsPage],
      ['strategies', 'Strategy guide', function () { return ST.list.length + ' built-in strategies explained'; }, strategyPage],
      ['help', 'How to read the signals', function () { return 'What flow, VWAP and open interest mean'; }, helpPage]
    ];
    function render() {
      el.innerHTML = '<div class="mb"><h1>More</h1></div><div class="card flush"><ul class="list">' + items.map(function (x, i) { return '<li class="tap" data-i="' + i + '"><div class="grow"><div class="t">' + x[1] + '</div><div class="d">' + x[2]() + '</div></div><span class="muted">›</span></li>'; }).join('') +
        '<li class="tap" data-disc><div class="grow"><div class="t">Disclaimer</div><div class="d">Not investment advice</div></div><span class="muted">›</span></li></ul></div>' +
        '<p class="muted center" style="font-size:12px">GK MarketFlow ' + F.esc(ver) + ' · ' + MF.instruments.count + ' instruments (list dated ' + F.esc(MF.instruments.generated) + ')<br>Charts by TradingView Lightweight Charts™</p>';
    }
    function open(i) { MF.app.push(function (page) { items[i][3](page); return {}; }, {}, items[i][1]); }
    U.on(el, 'click', '[data-i]', function (e, li) { open(+li.getAttribute('data-i')); });
    U.on(el, 'click', '[data-disc]', function () { MF.views.disclaimer(false); });
    render();
    if (args.open) { var k = items.map(function (x) { return x[0]; }).indexOf(args.open); if (k >= 0) open(k); }
    return { onShow: render };
  };
})(window);
