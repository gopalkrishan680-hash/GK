#!/usr/bin/env node
/* A local stand-in for the Groww Trade API, used only by automated tests.
 * It answers with the response shapes documented at https://groww.in/trade-api/docs/curl using simulated prices.
 * Accepts the bearer token "test-token", or issues one for API key "test-key" + secret "test-secret".
 */
'use strict';
const http = require('http'), crypto = require('crypto'), path = require('path');
const W = path.join(__dirname, '../app/src/main/assets/www/js/');
globalThis.window = globalThis;
['core', 'indicators', 'instruments-data', 'instruments', 'data'].forEach(f => require(W + f + '.js'));
const MF = globalThis.MF, Demo = MF.data._internal.Demo, PORT = +process.argv[2] || 8788;
const ist = t => new Date((t + 19800) * 1000).toISOString().slice(0, 19);
const TF = { '1minute': '1m', '5minute': '5m', '15minute': '15m', '1hour': '1h', '1day': '1D', '1week': '1W' };
const sym = (ex, s) => MF.instruments.get(ex + ':' + s) || { ex, sym: s, name: s, type: 'EQ' };
const ohlcStr = q => `{open: ${q.open.toFixed(2)},high: ${q.high.toFixed(2)},low: ${q.low.toFixed(2)},close: ${q.prevClose.toFixed(2)}}`;
let hits = 0;

http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x'), q = u.searchParams; hits++;
  const send = (code, obj) => { res.statusCode = code; res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(obj)); };
  const fail = (code, c, m) => send(code, { status: 'FAILURE', error: { code: c, message: m } });
  try {
    if (u.pathname === '/__hits') return send(200, { hits });
    if (u.pathname === '/v1/token/api/access') {
      let b = ''; for await (const d of req) b += d; b = JSON.parse(b);
      const good = crypto.createHash('sha256').update('test-secret' + b.timestamp).digest('hex');
      if (req.headers.authorization !== 'Bearer test-key' || b.key_type !== 'approval' || b.checksum !== good) return fail(401, 'GA005', 'User not authorised to perform this operation');
      return send(200, { token: 'test-token', tokenRefId: 'ref-1', sessionName: 'mock', expiry: '2099-01-01T06:00:00', isActive: true });
    }
    if (req.headers.authorization !== 'Bearer test-token' || req.headers['x-api-version'] !== '1.0') return fail(401, 'GA005', 'User not authorised to perform this operation');
    if (u.pathname === '/v1/live-data/ltp' || u.pathname === '/v1/live-data/ohlc') {
      const out = {}, list = q.get('exchange_symbols').split(',');
      if (list.length > 50) return fail(400, 'GA001', 'Bad request');
      for (const es of list) { const i = es.indexOf('_'), x = await Demo.quote(sym(es.slice(0, i), es.slice(i + 1))); out[es] = u.pathname.endsWith('ltp') ? +x.ltp.toFixed(2) : ohlcStr(x); }
      return send(200, { status: 'SUCCESS', payload: out });
    }
    if (u.pathname === '/v1/live-data/quote') {
      const x = await Demo.quote(sym(q.get('exchange'), q.get('trading_symbol')));
      return send(200, { status: 'SUCCESS', payload: { last_price: x.ltp, day_change: x.chg, day_change_perc: x.chgPct, ohlc: ohlcStr(x), volume: x.volume, average_price: x.avgPrice, total_buy_quantity: x.totalBuyQty, total_sell_quantity: x.totalSellQty, depth: x.depth || { buy: [], sell: [] }, upper_circuit_limit: x.upperCircuit, lower_circuit_limit: x.lowerCircuit, week_52_high: x.high52, week_52_low: x.low52, last_trade_time: Date.now() } });
    }
    if (u.pathname === '/v1/historical/candles') {
      const gs = q.get('groww_symbol'), i = gs.indexOf('-'), tf = TF[q.get('candle_interval')];
      if (!tf) return fail(400, 'GA001', 'Bad request');
      const p = s => Date.parse(s.replace(' ', 'T') + 'Z') / 1000 - 19800, a = p(q.get('start_time')), b = p(q.get('end_time'));
      const all = await Demo.candles(sym(gs.slice(0, i), gs.slice(i + 1)), tf, 4000);
      const rows = all.filter(c => c.t >= a && c.t <= b).map(c => [ist(c.t), c.o, c.h, c.l, c.c, c.v, null]);
      return send(200, { status: 'SUCCESS', payload: { candles: rows, closing_price: rows.length ? rows[rows.length - 1][4] : null, start_time: q.get('start_time'), end_time: q.get('end_time'), interval_in_minutes: 0 } });
    }
    if (u.pathname === '/v1/historical/expiries') {
      const all = await Demo.expiries(), m = String(q.get('month')).padStart(2, '0');
      return send(200, { status: 'SUCCESS', payload: { expiries: all.filter(d => d.slice(0, 7) === q.get('year') + '-' + m) } });
    }
    const oc = /^\/v1\/option-chain\/exchange\/(\w+)\/underlying\/([^/]+)$/.exec(u.pathname);
    if (oc) {
      const c = await Demo.optionChain(sym(oc[1], decodeURIComponent(oc[2])), q.get('expiry_date')), strikes = {};
      const leg = x => ({ greeks: { delta: x.delta, gamma: 0, theta: 0, vega: 0, rho: 0, iv: x.iv }, trading_symbol: x.symbol, ltp: x.ltp, open_interest: x.oi, volume: x.volume });
      c.rows.forEach(r => { strikes[r.strike] = { CE: leg(r.ce), PE: leg(r.pe) }; });
      return send(200, { status: 'SUCCESS', payload: { underlying_ltp: c.spot, strikes } });
    }
    fail(404, 'GA004', 'Requested entity does not exist');
  } catch (e) { fail(500, 'GA000', String(e.message)); }
}).listen(PORT, '127.0.0.1', () => console.log('mock Groww API on http://127.0.0.1:' + PORT));
