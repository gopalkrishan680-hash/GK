#!/usr/bin/env node
/* Serves the bundled web UI for development in a desktop browser and relays API calls
 * (the browser cannot call the broker API directly because of CORS).
 * Usage: node tools/dev-server.js [port]     then open http://localhost:8787
 */
'use strict';
const http = require('http'), https = require('https'), fs = require('fs'), path = require('path');
const ROOT = path.join(__dirname, '../app/src/main/assets/www'), PORT = +process.argv[2] || 8787;
const ALLOWED = new Set((process.env.MF_ALLOWED_HOSTS || 'api.groww.in,growwapi-assets.groww.in').split(','));
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json' };

http.createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/proxy') {
    let body = '';
    req.on('data', d => body += d);
    req.on('end', () => {
      let o; try { o = JSON.parse(body); } catch (e) { res.end(JSON.stringify({ status: 0, body: 'bad request' })); return; }
      const u = new URL(o.url);
      if (!ALLOWED.has(u.host)) { res.end(JSON.stringify({ status: 0, body: 'Host not allowed: ' + u.host })); return; }
      const lib = u.protocol === 'http:' ? http : https;
      const r = lib.request(u, { method: o.method || 'GET', headers: o.headers || {} }, up => {
        let t = ''; up.setEncoding('utf8'); up.on('data', d => t += d);
        up.on('end', () => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ status: up.statusCode, body: t })); });
      });
      r.on('error', e => res.end(JSON.stringify({ status: 0, body: String(e.message) })));
      if (o.body) r.write(o.body);
      r.end();
    });
    return;
  }
  const p = path.normalize(path.join(ROOT, decodeURIComponent(req.url.split('?')[0] === '/' ? '/index.html' : req.url.split('?')[0])));
  if (!p.startsWith(ROOT)) { res.statusCode = 403; res.end(); return; }
  fs.readFile(p, (err, data) => {
    if (err) { res.statusCode = 404; res.end('not found'); return; }
    res.setHeader('Content-Type', TYPES[path.extname(p)] || 'application/octet-stream');
    res.setHeader('Cache-Control', 'no-store');
    res.end(data);
  });
}).listen(PORT, '127.0.0.1', () => console.log('GK MarketFlow dev server on http://localhost:' + PORT));
