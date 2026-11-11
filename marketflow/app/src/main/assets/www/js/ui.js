/* UI helpers: DOM, sheets, toast, icons, symbol picker and the chart wrapper. */
(function (root) {
  'use strict';
  var MF = root.MF, F = MF.fmt, doc = root.document, IST = 19800;
  var U = MF.ui = {};

  U.$ = function (sel, el) { return (el || doc).querySelector(sel); };
  U.$$ = function (sel, el) { return Array.prototype.slice.call((el || doc).querySelectorAll(sel)); };
  U.el = function (html) { var d = doc.createElement('div'); d.innerHTML = html.trim(); return d.firstChild; };
  U.on = function (el, ev, sel, fn) {
    el.addEventListener(ev, function (e) {
      var t = e.target;
      while (t && t !== el) { if (t.matches && t.matches(sel)) { fn(e, t); return; } t = t.parentNode; }
    });
  };
  U.cls = function (v) { return v > 0 ? 'up' : v < 0 ? 'down' : ''; };
  U.spinner = '<div class="spinner"></div>';
  U.error = function (e, retry) { return '<div class="err">' + F.esc(e && e.message ? e.message : e) + (retry ? '<div class="mt-s"><button class="btn small ghost" data-retry>Try again</button></div>' : '') + '</div>'; };

  var ICON = {
    back: '<path d="M15 5l-7 7 7 7"/>', close: '<path d="M6 6l12 12M18 6L6 18"/>', plus: '<path d="M12 5v14M5 12h14"/>',
    star: '<path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.5 9.7l5.9-.8z"/>',
    bell: '<path d="M6 16V11a6 6 0 1112 0v5l1.5 2h-15zM10 20a2 2 0 004 0"/>', refresh: '<path d="M20 11a8 8 0 10-2.3 5.7M20 4v7h-7"/>',
    trash: '<path d="M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13"/>', search: '<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>',
    market: '<path d="M4 19V9M10 19V5M16 19v-7M21 19H3"/>', flow: '<path d="M3 17l5-5 4 3 8-9M15 6h5v5"/>',
    options: '<path d="M4 6h16M4 12h16M4 18h16M9 4v16M15 4v16"/>', test: '<path d="M5 4v16M5 12h6V7h5v10h3"/><circle cx="5" cy="12" r="0.5"/>',
    more: '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>',
    play: '<path d="M7 5l12 7-12 7z"/>', pause: '<path d="M8 5v14M16 5v14"/>', step: '<path d="M6 5l9 7-9 7zM18 5v14"/>'
  };
  U.icon = function (n) { return '<svg viewBox="0 0 24 24">' + (ICON[n] || '') + '</svg>'; };

  var toastT = null;
  U.toast = function (msg, ms) {
    var t = U.$('#toast'); t.textContent = msg; t.classList.remove('hidden');
    clearTimeout(toastT); toastT = setTimeout(function () { t.classList.add('hidden'); }, ms || 2600);
  };

  /* Bottom sheet. Returns { el, body, close }. */
  var sheets = [];
  U.sheet = function (title, html, onClose) {
    var bg = U.el('<div class="sheet-bg"><div class="sheet"><div class="sh-h"><h2></h2><button class="iconbtn" data-x>' + U.icon('close') + '</button></div><div class="sh-b"></div></div></div>');
    U.$('h2', bg).textContent = title;
    var body = U.$('.sh-b', bg); body.innerHTML = html;
    var s = { el: bg, body: body, close: function () { var i = sheets.indexOf(s); if (i < 0) return; sheets.splice(i, 1); bg.remove(); if (onClose) onClose(); } };
    bg.addEventListener('click', function (e) { if (e.target === bg) s.close(); });
    U.$('[data-x]', bg).addEventListener('click', s.close);
    U.$('#sheet-root').appendChild(bg); sheets.push(s);
    return s;
  };
  U.closeTopSheet = function () { if (!sheets.length) return false; sheets[sheets.length - 1].close(); return true; };

  U.confirm = function (title, text, okLabel, fn) {
    var s = U.sheet(title, '<p>' + F.esc(text) + '</p><div class="grid2 mt"><button class="btn ghost" data-no>Cancel</button><button class="btn" data-yes>' + F.esc(okLabel) + '</button></div>');
    U.$('[data-no]', s.body).onclick = s.close;
    U.$('[data-yes]', s.body).onclick = function () { s.close(); fn(); };
  };

  /* Searchable instrument picker. filter(sym) optionally restricts the results. */
  U.pickSymbol = function (title, onPick, opts) {
    opts = opts || {};
    var s = U.sheet(title || 'Search', '<div class="field"><input type="search" placeholder="Search name or symbol, e.g. RELIANCE" autocomplete="off" autocapitalize="characters"></div><ul class="list" data-res></ul>');
    var inp = U.$('input', s.body), res = U.$('[data-res]', s.body), cur = [];
    function render(q) {
      cur = q ? MF.instruments.search(q, 40) : (opts.suggest || MF.instruments.mainIndices.concat(MF.instruments.universe.slice(0, 12)));
      if (opts.filter) cur = cur.filter(opts.filter);
      res.innerHTML = cur.length ? cur.map(function (x, i) {
        return '<li class="tap" data-i="' + i + '"><div class="grow"><div class="t">' + F.esc(x.sym) + '</div><div class="d ellipsis">' + F.esc(x.name) + '</div></div><span class="pill">' + (x.type === 'IDX' ? 'Index' : x.ex) + '</span></li>';
      }).join('') : '<div class="empty">No match. Try the NSE trading symbol.</div>';
    }
    inp.addEventListener('input', function () { render(inp.value); });
    U.on(res, 'click', 'li', function (e, li) { var x = cur[+li.getAttribute('data-i')]; s.close(); onPick(x); });
    render('');
    setTimeout(function () { inp.focus(); }, 60);
  };

  U.symLabel = function (s) { return s.type === 'IDX' ? s.name : s.sym; };

  /* ---------- watchlist + recent ---------- */
  var DEF_WATCH = ['NSE:RELIANCE', 'NSE:HDFCBANK', 'NSE:TCS', 'NSE:INFY', 'NSE:ICICIBANK', 'NSE:SBIN'];
  U.watchlist = function () { return MF.store.get('watchlist', DEF_WATCH).map(MF.instruments.get).filter(Boolean); };
  U.inWatchlist = function (s) { return MF.store.get('watchlist', DEF_WATCH).indexOf(MF.data.key(s)) >= 0; };
  U.toggleWatch = function (s) {
    var w = MF.store.get('watchlist', DEF_WATCH).slice(), k = MF.data.key(s), i = w.indexOf(k);
    if (i >= 0) w.splice(i, 1); else w.push(k);
    MF.store.set('watchlist', w); return i < 0;
  };

  /* ---------- chart wrapper around lightweight-charts ---------- */
  U.chart = function (container, opts) {
    opts = opts || {};
    var LW = root.LightweightCharts;
    var chart = LW.createChart(container, {
      autoSize: true,
      layout: { background: { color: 'transparent' }, textColor: '#8b98ad', fontSize: 11 },
      grid: { vertLines: { color: 'rgba(35,48,74,.5)' }, horzLines: { color: 'rgba(35,48,74,.5)' } },
      rightPriceScale: { borderColor: '#23304a', scaleMargins: { top: 0.08, bottom: opts.volume === false ? 0.06 : 0.24 } },
      timeScale: { borderColor: '#23304a', timeVisible: true, secondsVisible: false, rightOffset: 4 },
      crosshair: { mode: 0 },
      handleScale: { axisPressedMouseMove: false }
    });
    var main = opts.line
      ? chart.addLineSeries({ color: '#4c9aff', lineWidth: 2, priceLineVisible: false })
      : chart.addCandlestickSeries({ upColor: '#22c55e', downColor: '#ef4444', borderVisible: false, wickUpColor: '#22c55e', wickDownColor: '#ef4444' });
    var vol = null;
    if (opts.volume !== false && !opts.line) {
      vol = chart.addHistogramSeries({ priceFormat: { type: 'volume' }, priceScaleId: 'vol', lastValueVisible: false, priceLineVisible: false });
      chart.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
    }
    var lines = [], priceLines = [], count = 0;
    function tt(t) { return t + IST; }
    function bar(c) { return { time: tt(c.t), open: c.o, high: c.h, low: c.l, close: c.c }; }
    function vbar(c) { return { time: tt(c.t), value: c.v || 0, color: c.c >= c.o ? 'rgba(34,197,94,.45)' : 'rgba(239,68,68,.45)' }; }
    var api = {
      chart: chart, main: main,
      setCandles: function (c) {
        count = c.length;
        main.setData(c.map(bar));
        if (vol) vol.setData(c.some(function (x) { return x.v > 0; }) ? c.map(vbar) : []);
      },
      update: function (c) { main.update(bar(c)); if (c.t !== api._lastT) { if (api._lastT !== undefined && c.t > api._lastT) count++; api._lastT = c.t; } if (vol && c.v > 0) vol.update(vbar(c)); },
      setLine: function (points) { main.setData(points.map(function (p) { return { time: tt(p.t), value: p.v }; })); },
      /* lines: [{ name, color, data: [values aligned with candles] }] */
      setLines: function (c, defs) {
        lines.forEach(function (l) { chart.removeSeries(l); }); lines = [];
        (defs || []).forEach(function (d) {
          var s = chart.addLineSeries({ color: d.color, lineWidth: d.width || 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, lineStyle: d.dashed ? 2 : 0 });
          var pts = [];
          for (var i = 0; i < c.length && i < d.data.length; i++) if (d.data[i] !== null && d.data[i] !== undefined) pts.push({ time: tt(c[i].t), value: d.data[i] });
          s.setData(pts); lines.push(s);
        });
      },
      /* Appends one more point to each line created by setLines (used by replay). */
      updateLines: function (t, values) {
        for (var i = 0; i < lines.length && i < values.length; i++) if (values[i] !== null && values[i] !== undefined) lines[i].update({ time: tt(t), value: values[i] });
      },
      addLine: function (color, points, dashed) {
        var s = chart.addLineSeries({ color: color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false, lineStyle: dashed ? 2 : 0 });
        s.setData(points.map(function (p) { return { time: tt(p.t), value: p.v }; })); lines.push(s); return s;
      },
      /* markers: [{ t, side: 'buy'|'sell'|'exit'|'vol-buy'|'vol-sell', text }] */
      setMarkers: function (m) {
        var map = {
          buy: { position: 'belowBar', color: '#22c55e', shape: 'arrowUp' }, sell: { position: 'aboveBar', color: '#ef4444', shape: 'arrowDown' },
          exit: { position: 'aboveBar', color: '#f5a524', shape: 'circle' }, 'vol-buy': { position: 'belowBar', color: '#22c55e', shape: 'circle' },
          'vol-sell': { position: 'aboveBar', color: '#ef4444', shape: 'circle' }, 'vol-mixed': { position: 'aboveBar', color: '#8b98ad', shape: 'circle' }
        };
        var arr = (m || []).map(function (x) { var d = map[x.side] || map.exit; return { time: tt(x.t), position: d.position, color: d.color, shape: d.shape, text: x.text || '' }; });
        arr.sort(function (a, b) { return a.time - b.time; });
        main.setMarkers(arr);
      },
      setPriceLines: function (defs) {
        priceLines.forEach(function (p) { main.removePriceLine(p); }); priceLines = [];
        (defs || []).forEach(function (d) { if (d.price === null || d.price === undefined || isNaN(d.price)) return; priceLines.push(main.createPriceLine({ price: d.price, color: d.color || '#8b98ad', lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: d.title || '' })); });
      },
      fit: function () { chart.timeScale().fitContent(); },
      showLast: function (n) { chart.timeScale().setVisibleLogicalRange({ from: Math.max(0, count - n) - 0.5, to: count + 3 }); },
      scrollToEnd: function () { chart.timeScale().scrollToRealTime(); },
      setIntraday: function (intra) { chart.applyOptions({ timeScale: { timeVisible: !!intra } }); },
      destroy: function () { chart.remove(); }
    };
    return api;
  };

  U.legend = function (lines) { return '<div class="legend">' + lines.map(function (l) { return '<span><i style="background:' + l.color + '"></i>' + F.esc(l.name) + '</span>'; }).join('') + '</div>'; };

  U.flowPill = function (label) { var c = /Buying/.test(label) ? 'up' : /Selling/.test(label) ? 'down' : ''; return '<span class="pill ' + c + '">' + label + '</span>'; };
  U.verdictPill = function (v) { var c = /Bullish/.test(v) ? 'up' : /Bearish/.test(v) ? 'down' : ''; return '<span class="pill ' + c + '">' + v + '</span>'; };
  U.gauge = function (score) { return '<div class="gauge"><b style="left:' + (50 + score / 2) + '%"></b></div><div class="row between"><small>Selling</small><small>Neutral</small><small>Buying</small></div>'; };
})(window);
