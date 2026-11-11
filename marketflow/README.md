# GK MarketFlow

An Android app for tracking the Indian stock market (NSE / BSE): it shows **where buying and selling pressure is building** in stocks and indices, analyses the option chain, and lets you **backtest and replay** trading strategies on past data.

The whole app is in English. It only *reads* market data — it contains no code to place orders.

> **Not investment advice.** Flow scores, outlooks and backtests describe data; they do not predict the future.

## Install

1. Download [`release/GK-MarketFlow-1.0.0.apk`](release/GK-MarketFlow-1.0.0.apk) on your Android phone (Android 8.0 or newer).
2. Open it and allow "install from this source" when Android asks.
3. The app starts in **demo mode** with simulated prices so every screen can be tried immediately.

## Connect real data (Groww)

Live data comes from the official [Groww Trading API](https://groww.in/trade-api/docs/curl). It needs your own Groww account with the Trading API subscription (Groww charges a monthly fee for it).

1. On groww.in: profile → **Settings → Trading APIs → Generate API keys**.
2. Either copy an **Access Token** (expires daily at 6:00 AM), or create an **API key + secret** (approved once a day on the same page).
3. In the app: **More → Groww connection → Groww (live)**, paste, then **Save and test connection**.

The token is stored only on the phone and sent only to `api.groww.in`.

## Screens

| Tab | What it does |
| --- | --- |
| **Market** | Indices, market breadth, watchlist, sector performance, top gainers / losers |
| **Flow** | Scans 50 large caps or your watchlist and ranks them by buying / selling pressure (VWAP, up vs down volume, money flow, relative volume), flow by sector, latest volume spikes, optional notifications |
| **Options** | Option chain, open interest by strike, PCR, max pain, support / resistance from OI, OI change through the day, expected move |
| **Backtest** | Test one of 13 strategies on history, rank all strategies on a symbol, or **replay** history candle by candle like a live market with paper trading |
| **More** | Price alerts, Groww connection, strategy guide, help |

Stock page: live price, chart (1m – 1W) with EMA / VWAP / Supertrend / Bollinger / pivot levels / volume-spike markers, flow gauge, market depth, intraday / swing / long-term outlook, key levels and a risk-based trade plan calculator.

### Built-in strategies

EMA Crossover, Supertrend, RSI(2) Dip Buying, MACD + Trend Filter, Bollinger Band Reversion, Donchian Breakout, Volume Breakout, RSI Pullback, Opening Range Breakout, VWAP Trend, Golden Cross, 200-Day Trend Following, 52-Week High Momentum.

Backtests take signals at a candle's close and fill at the next candle's open, charge brokerage and slippage on both sides, and square off intraday trades by 15:15 IST.

## What it cannot show

The exchanges do not publish who is buying or selling in real time. FII/DII figures, bulk / block deals and delivery percentages are released after market hours and are not part of the Groww API, so they are not in the app.

## Development

```
node test/run.js                         # unit tests (indicators, engine, parsers, analytics)
node tools/dev-server.js                 # UI in a desktop browser at http://localhost:8787
node test/e2e.js                         # browser smoke test of every screen (demo mode)

# Groww data path against a local mock of the documented API
node tools/mock-groww.js 8788 &
MF_ALLOWED_HOSTS=127.0.0.1:8788 node tools/dev-server.js 8789 &
node test/e2e-groww.js

./gradlew assembleRelease                # APK -> app/build/outputs/apk/release/
python3 tools/build_instruments.py instrument.csv   # refresh the bundled symbol list
```

Layout: the UI and all analytics are plain JavaScript in `app/src/main/assets/www`, hosted in a WebView. The Java layer (`app/src/main/java`) provides HTTPS access restricted to Groww's hosts, notifications and the background price-alert job. Charts use TradingView Lightweight Charts (Apache-2.0).

`keystore/sideload.keystore` is a shared, non-secret signing key (standard Android debug credentials) so that every build can be installed over the previous one. Do not use it for a Play Store release.
