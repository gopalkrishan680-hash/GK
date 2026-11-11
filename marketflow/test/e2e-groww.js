/* Exercises the Groww data path end to end against tools/mock-groww.js.
 * Run: MF_ALLOWED_HOSTS=127.0.0.1:8788 node tools/dev-server.js 8789 &  node tools/mock-groww.js 8788 &  node test/e2e-groww.js
 */
'use strict';
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch(), errors = [];
  const run = async (name, settings, body) => {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } }), page = await ctx.newPage();
    page.on('pageerror', e => errors.push(name + ' pageerror: ' + e.message));
    await page.addInitScript(s => { localStorage.setItem('mf.devApiBase', JSON.stringify('http://127.0.0.1:8788')); localStorage.setItem('mf.disclaimerSeen', 'true'); localStorage.setItem('mf.settings', JSON.stringify(s)); }, settings);
    await page.goto('http://localhost:8789/');
    try { await body(page); console.log('ok   ' + name); } catch (e) { errors.push(name + ': ' + e.message.split('\n')[0]); console.log('FAIL ' + name + ' — ' + e.message.split('\n')[0]); }
    await ctx.close();
  };
  const full = async page => {
    if (await page.isVisible('#banner')) throw new Error('demo banner visible in Groww mode');
    await page.waitForSelector('[data-indices] .tile .v'); await page.waitForSelector('[data-gain] li');
    const nifty = await page.textContent('[data-indices] .tile .v'); if (!/^\d/.test(nifty)) throw new Error('no index price: ' + nifty);
    const chg = await page.textContent('[data-watch] li .chg'); if (!/%/.test(chg)) throw new Error('no change %: ' + chg);
    await page.click('[data-watch] li'); await page.waitForSelector('.overlay [data-depth] .lv'); await page.waitForSelector('.overlay [data-levels] .kv'); await page.waitForSelector('.overlay [data-flow] .gauge');
    const candles = await page.evaluate(() => MF.data.candles(MF.instruments.get('NSE:RELIANCE'), '1D', 1825).then(c => ({ n: c.length, sorted: c.every((x, i) => i === 0 || x.t > c[i - 1].t) })));
    if (candles.n < 1100 || !candles.sorted) throw new Error('multi-chunk daily history wrong: ' + JSON.stringify(candles));
    const m5 = await page.evaluate(() => MF.data.candles(MF.instruments.get('NSE:RELIANCE'), '5m', 60).then(c => c.length)); if (m5 < 2000) throw new Error('5m history too short: ' + m5);
    await page.evaluate(() => window.MFBack());
    await page.click('[data-tab="options"]'); await page.waitForSelector('.oi-row'); await page.waitForSelector('table.t tr.atm');
    await page.click('[data-tab="backtest"]'); await page.click('[data-go]'); await page.waitForSelector('[data-res] table.t', { timeout: 30000 });
    if (/simulated demo data/.test(await page.textContent('[data-res]'))) throw new Error('labelled as demo in Groww mode');
    await page.click('[data-tab="flow"]'); await page.click('[data-scope="watch"]'); await page.waitForSelector('[data-out] .gauge', { timeout: 60000 });
  };
  await run('access-token mode', { mode: 'groww', authMode: 'token', accessToken: 'test-token', apiKey: '', apiSecret: '' }, full);
  await run('key + secret mode (checksum token exchange)', { mode: 'groww', authMode: 'keysecret', accessToken: '', apiKey: 'test-key', apiSecret: 'test-secret' }, async page => {
    await page.waitForSelector('[data-indices] .tile .v');
    const tok = await page.evaluate(() => MF.store.get('growwToken', {}).token); if (tok !== 'test-token') throw new Error('token not cached');
  });
  await run('expired / wrong token shows a clear message', { mode: 'groww', authMode: 'token', accessToken: 'wrong', apiKey: '', apiSecret: '' }, async page => {
    await page.waitForSelector('[data-err] .err'); const t = await page.textContent('[data-err]'); if (!/expires every day/.test(t)) throw new Error('message: ' + t);
  });
  await run('wrong secret shows a clear message', { mode: 'groww', authMode: 'keysecret', accessToken: '', apiKey: 'test-key', apiSecret: 'nope' }, async page => {
    await page.waitForSelector('[data-err] .err'); const t = await page.textContent('[data-err]'); if (!/token request/.test(t)) throw new Error('message: ' + t);
  });
  await browser.close();
  console.log(errors.length ? '\n' + errors.join('\n') : '\nGroww data path OK');
  process.exit(errors.length ? 1 : 0);
})();
