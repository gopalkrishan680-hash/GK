/* Instrument lookup on top of the bundled Groww instrument list (instruments-data.js). */
(function (root) {
  'use strict';
  var MF = root.MF = root.MF || {};
  var D = root.MF_INSTRUMENTS || { eq: [], idx: [], fno: {}, generated: '' };

  var all = [], byKey = {};
  D.idx.forEach(function (r) { var s = { ex: r[2], sym: r[0], name: r[1], type: 'IDX' }; all.push(s); byKey[s.ex + ':' + s.sym] = s; });
  D.eq.forEach(function (r) { var s = { ex: 'NSE', sym: r[0], name: r[1], type: 'EQ' }; all.push(s); byKey['NSE:' + r[0]] = s; });

  /* A liquid large-cap universe used by the scanner, grouped by sector. Symbols missing from the bundled list are dropped. */
  var SECTORS = {
    'Banks': ['HDFCBANK', 'ICICIBANK', 'SBIN', 'KOTAKBANK', 'AXISBANK', 'INDUSINDBK'],
    'Financials': ['BAJFINANCE', 'BAJAJFINSV', 'HDFCLIFE', 'SBILIFE', 'SHRIRAMFIN', 'JIOFIN'],
    'IT': ['TCS', 'INFY', 'HCLTECH', 'WIPRO', 'TECHM'],
    'Auto': ['MARUTI', 'M&M', 'BAJAJ-AUTO', 'EICHERMOT', 'HEROMOTOCO'],
    'Pharma & Health': ['SUNPHARMA', 'CIPLA', 'DRREDDY', 'APOLLOHOSP', 'MAXHEALTH'],
    'FMCG & Consumer': ['ITC', 'HINDUNILVR', 'NESTLEIND', 'TATACONSUM', 'TITAN', 'ASIANPAINT', 'TRENT'],
    'Energy & Power': ['RELIANCE', 'ONGC', 'NTPC', 'POWERGRID', 'COALINDIA'],
    'Metals & Cement': ['TATASTEEL', 'JSWSTEEL', 'HINDALCO', 'ULTRACEMCO', 'GRASIM'],
    'Infra & Others': ['LT', 'BHARTIARTL', 'ADANIENT', 'ADANIPORTS', 'BEL', 'INDIGO']
  };
  var universe = [], sectorOf = {};
  Object.keys(SECTORS).forEach(function (sec) {
    SECTORS[sec] = SECTORS[sec].filter(function (x) { return !!byKey['NSE:' + x]; });
    SECTORS[sec].forEach(function (x) { universe.push(byKey['NSE:' + x]); sectorOf[x] = sec; });
  });

  function idx(sym, ex) { return byKey[(ex || 'NSE') + ':' + sym]; }
  var mainIndices = [idx('NIFTY'), idx('BANKNIFTY'), idx('SENSEX', 'BSE'), idx('FINNIFTY'), idx('NIFTYMIDCAP'), idx('INDIAVIX')].filter(Boolean);
  var sectorIndices = ['NIFTYIT', 'NIFTYAUTO', 'NIFTYPHARMA', 'NIFTYFMCG', 'NIFTYMETAL', 'NIFTYPSUBANK', 'NIFTYPVTBANK', 'NIFTYREALTY', 'NIFTYMEDIA'].map(function (x) { return idx(x); }).filter(Boolean);

  function search(q, limit) {
    q = String(q || '').trim().toUpperCase(); limit = limit || 30;
    if (!q) return [];
    var a = [], b = [], c = [];
    for (var i = 0; i < all.length; i++) {
      var s = all[i], sym = s.sym.toUpperCase(), name = s.name.toUpperCase();
      if (sym === q) a.unshift(s);
      else if (sym.indexOf(q) === 0) a.push(s);
      else if (name.indexOf(q) === 0 || name.indexOf(' ' + q) >= 0) b.push(s);
      else if (sym.indexOf(q) > 0 || name.indexOf(q) > 0) c.push(s);
      if (a.length >= limit) break;
    }
    return a.concat(b, c).slice(0, limit);
  }

  MF.instruments = {
    generated: D.generated, count: all.length,
    get: function (key) { return byKey[key] || null; },
    search: search, universe: universe, sectors: SECTORS, sectorOf: function (sym) { return sectorOf[sym] || null; },
    mainIndices: mainIndices, sectorIndices: sectorIndices,
    isFno: function (sym) { return !!D.fno[sym]; },
    lotSize: function (sym) { return D.fno[sym] ? D.fno[sym][1] : null; },
    fnoExchange: function (sym) { return D.fno[sym] ? D.fno[sym][0] : null; },
    optionUnderlyings: function () {
      var top = ['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY', 'SENSEX', 'BANKEX'].filter(function (x) { return !!D.fno[x]; });
      var rest = Object.keys(D.fno).filter(function (x) { return top.indexOf(x) < 0; }).sort();
      return top.concat(rest).map(function (x) { var s = byKey[D.fno[x][0] + ':' + x] || { ex: D.fno[x][0], sym: x, name: x, type: 'IDX' }; return s; });
    }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = MF;
})(typeof window !== 'undefined' ? window : globalThis);
