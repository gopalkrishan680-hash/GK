# PriceHunt – Price comparison app for Android

Search once, see the price of the same product on **Amazon, Flipkart, Myntra, Meesho, AJIO, Croma**
(plus optional **Tata CLiQ, Snapdeal, JioMart**) and jump straight to the cheapest one.

## Install
Download **`PriceHunt-v1.1.0.apk`** from this folder on your phone, open it and allow
"Install unknown apps" when Android asks. (Android 8.0 or newer.)

## Features
- **On-screen price compare (new)** – while you look at a product in the Amazon / Flipkart / Myntra / Meesho / AJIO /
  Tata CLiQ / Snapdeal / JioMart app, a PriceHunt bubble appears on the screen: *"▼ ₹200 less on Flipkart"*.
  Tap it for a card with the same item's price on every other platform; tap a row to open it there.
  Turn on in *Settings → On-screen price compare* (uses Android Accessibility; only those shopping apps are read).
  If Android says *"Restricted setting"*: App info → ⋮ → *Allow restricted settings*, then switch it on again.
- **Price on other platforms** – every product page in PriceHunt automatically shows the same item's price on the
  other stores, with the difference (₹ less / more).
- **Smarter matching** – detects different variants (128 GB vs 256 GB, 500 ml vs 1 L) so they aren't treated as the same item.
- **One search, all stores** – results load in parallel; each store shows its status (results / blocked / retry).
- **Lowest price card** – the cheapest matching item, how much you save vs. the costliest store, and a *Buy* button.
- **"Same item, every store" table** – best match from each store, sorted by price, with the difference shown.
- **Open directly in the store app** – Amazon/Flipkart/Myntra/Meesho/AJIO… app opens on that exact product (browser if the app isn't installed).
- **Price history** – every price PriceHunt sees is saved; product page shows a touchable chart, lowest / average / highest and a
  *"Great time to buy / wait"* verdict. Amazon items also link to Keepa for long-term history.
- **Price tracker + drop alerts** – track any product with an optional target price; it's re-checked in the background
  (every 3/6/12/24 h) and you get a notification when it drops.
- **Share from any shopping app** – in Amazon/Flipkart/Myntra/Meesho tap *Share → PriceHunt*; or paste a product link in search.
- Sort (best match, price, discount, rating), filter by store, hide accessories/unrelated items, hide sponsored listings.
- Recent searches, dark mode, choose which stores to search. No login, no server – everything stays on your phone.

## How it works
The stores have no public price API, so PriceHunt loads each store's public search / product page in a hidden
WebView on the phone (exactly like a browser) and reads the products from it (`app/src/main/assets/search.js`,
`product.js`). Price history starts from the first time the app sees a product and grows as you search/track.

**Limitations:** stores change their websites often and sometimes show a captcha; if a store stops returning
results, its extractor in `search.js` / its URL pattern in `data/Store.kt` needs updating. Bank/coupon offers
are not included – always confirm the final price in the store before paying.

## Build from source
Android Studio (Ladybug or newer) → *Open* this `PriceHunt` folder → Run.
Command line: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
GitHub Actions (`.github/workflows/pricehunt-apk.yml`) also builds the APK on every push.

Tech: Kotlin, Jetpack Compose (Material 3), Room, WorkManager, Coil.
