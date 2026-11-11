/* Browser smoke test of every screen in demo mode. Run: node tools/dev-server.js & node test/e2e.js [screenshotDir] */
'use strict';
const { chromium } = require('playwright');
const out = process.argv[2] || null;
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));
  const shot = async n => { if (out) await page.screenshot({ path: out + '/' + n + '.png' }); };
  const step = async (name, fn) => { try { await fn(); console.log('ok   ' + name); } catch (e) { errors.push(name + ': ' + e.message.split('\n')[0]); console.log('FAIL ' + name + ' — ' + e.message.split('\n')[0]); } };

  await page.goto('http://localhost:8787/');
  await step('disclaimer shows once', async () => { await page.click('[data-ok]'); });
  await step('market tab', async () => {
    await page.waitForSelector('[data-indices] .tile'); await page.waitForSelector('[data-watch] li'); await page.waitForSelector('[data-gain] li');
    const t = await page.textContent('[data-indices]'); if (!/NIFTY 50/.test(t)) throw new Error('no NIFTY tile');
    await shot('01-market');
  });
  await step('stock detail', async () => {
    await page.click('[data-watch] li'); await page.waitForSelector('.overlay [data-flow] .gauge'); await page.waitForSelector('.overlay [data-levels] .kv'); await page.waitForSelector('.overlay [data-depth] .lv');
    await page.waitForSelector('.overlay [data-plan] .stat'); await page.waitForTimeout(400); await shot('02-detail');
    await page.click('.overlay [data-tfid="1D"]'); await page.waitForTimeout(500);
    await page.click('.overlay [data-ovid="st"]'); await page.waitForTimeout(300);
    await page.evaluate(() => document.querySelector('.overlay .page').scrollTo(0, 900)); await page.waitForTimeout(200); await shot('03-detail-lower');
    await page.evaluate(() => document.querySelector('.overlay .page').scrollTo(0, 2200)); await page.waitForTimeout(200); await shot('04-detail-bottom');
  });
  await step('alert sheet', async () => { await page.click('.overlay [data-alert]'); await page.click('.sheet [data-save]'); });
  await step('index detail', async () => {
    await page.evaluate(() => window.MFBack()); await page.click('[data-indices] .tile'); await page.waitForSelector('.overlay [data-flow] .gauge'); await page.waitForTimeout(300); await shot('05-index');
    await page.evaluate(() => window.MFBack());
  });
  await step('flow scan', async () => {
    await page.click('[data-tab="flow"]'); await page.click('button.btn[data-scan]'); await page.waitForSelector('.heat', { timeout: 60000 }); await page.waitForTimeout(200); await shot('06-flow');
    await page.evaluate(() => document.querySelector('#view').scrollTo(0, 700)); await shot('07-flow-lower');
  });
  await step('options', async () => {
    await page.click('[data-tab="options"]'); await page.waitForSelector('.oi-row'); await page.waitForSelector('table.t tr.atm'); await shot('08-options');
    await page.evaluate(() => document.querySelector('#view').scrollTo(0, 800)); await shot('09-options-lower');
  });
  await step('backtest run', async () => {
    await page.click('[data-tab="backtest"]'); await page.click('[data-go]'); await page.waitForSelector('[data-res] table.t', { timeout: 30000 }); await page.waitForTimeout(600); await shot('10-backtest');
    await page.evaluate(() => document.querySelector('#view').scrollTo(0, 1500)); await page.waitForTimeout(200); await shot('11-backtest-charts');
  });
  await step('backtest intraday', async () => {
    await page.evaluate(() => document.querySelector('#view').scrollTo(0, 0));
    await page.click('[data-style="intraday"]'); await page.selectOption('[data-f="strategy"]', 'orb'); await page.click('[data-go]'); await page.waitForSelector('[data-res] table.t', { timeout: 30000 });
    const t = await page.textContent('[data-res]'); if (!/square-off|signal/.test(t)) throw new Error('no intraday trades');
  });
  await step('rank', async () => {
    await page.evaluate(() => document.querySelector('#view').scrollTo(0, 0));
    await page.click('[data-tab="rank"]'); await page.click('[data-style="swing"]'); await page.click('[data-go]'); await page.waitForSelector('[data-res] tr[data-sid]', { timeout: 30000 }); await page.waitForTimeout(200); await shot('12-rank');
  });
  await step('replay', async () => {
    await page.click('[data-tab="replay"]'); await page.waitForSelector('.overlay [data-startlbl]:not(:empty)', { timeout: 30000 });
    await page.selectOption('.overlay [data-strat]', 'supertrend'); await page.selectOption('.overlay [data-speed]', '30');
    const before = await page.textContent('.overlay [data-left]');
    await page.click('.overlay [data-buy]'); await page.click('.overlay [data-play]'); await page.waitForTimeout(2500); await page.click('.overlay [data-play]');
    const after = await page.textContent('.overlay [data-left]'); if (before === after) throw new Error('replay did not advance');
    await page.click('.overlay [data-step]'); await page.click('.overlay [data-flat]');
    const acct = await page.textContent('.overlay [data-acct]'); if (!/None/.test(acct)) throw new Error('position not closed: ' + acct);
    await shot('13-replay'); await page.evaluate(() => document.querySelector('.overlay .page').scrollTo(0, 600)); await page.waitForTimeout(200); await shot('14-replay-lower');
    await page.evaluate(() => window.MFBack());
  });
  await step('more pages', async () => {
    await page.click('[data-tab="more"]'); await shot('15-more');
    for (let i = 0; i < 4; i++) { await page.click('#view [data-i="' + i + '"]'); await page.waitForSelector('.overlay .page > *'); if (out) await shot('16-more-' + i); await page.evaluate(() => window.MFBack()); }
  });
  await step('settings: groww mode without token gives a clear error', async () => {
    await page.click('#view [data-i="1"]'); await page.click('.overlay [data-mode="groww"]'); await page.click('.overlay [data-test]'); await page.waitForSelector('.overlay [data-result] .err');
    const t = await page.textContent('.overlay [data-result]'); if (!/token/i.test(t)) throw new Error('unexpected: ' + t);
    await page.click('.overlay [data-mode="demo"]'); await page.evaluate(() => window.MFBack());
  });
  await step('back button returns to market then exits', async () => {
    const a = await page.evaluate(() => window.MFBack()); const b = await page.evaluate(() => window.MFBack());
    if (a !== true || b !== false) throw new Error('back: ' + a + ',' + b);
  });
  await browser.close();
  console.log(errors.length ? '\n' + errors.length + ' problem(s):\n' + errors.join('\n') : '\nAll screens OK, no console errors');
  process.exit(errors.length ? 1 : 0);
})();
