# 📡 Viral Radar — YouTube viral predictor (Android)

Live YouTube data se batata hai kaunsi video viral ho rahi hai / hone wali hai.

## Features
| Tab | Kya karta hai |
|---|---|
| **Trending** | 20 countries + har category ka live YouTube trending (top 100). Har video ka **Viral Score 0-100**, views/hour, outlier (views ÷ subs). Har 10 min auto-refresh + pull-to-refresh. Filter: Shorts / Long, sort: score / speed / outlier / engagement / newest |
| **Discover** | Apne niche ka keyword daalo (ya kisi video ka link) → last 6h–30 din ke uploads me se breakout videos (chhota channel, bade views) dhoondta hai. Kisi bhi app se YouTube link "Share → Viral Radar" karke bhi analyze kar sakte ho |
| **Video detail** | Score breakdown, 24h / 7 din views prediction, "kyun viral hai" reasons, saare tags, share |
| **Ideas → 🤖 Auto** | Kuch likhne ki zarurat nahi. App khud har category ke live trending videos (khaas kar jahan chhote channels jeet rahe hain), Google Trends aur YouTube search autocomplete se topics nikaalti hai, phir top 5/8/12 topics ka live demand vs competition check karke 💎 Golden topics batati hai. Har 12 ghante background me khud chalta hai aur notification bhejta hai (≈850 units/scan) |
| **Ideas → ✍️ Apna topic** | Topic likho → YouTube autocomplete se wo searches aati hain jo log abhi type kar rahe hain (free). Keywords chuno → har keyword ka live **Opportunity Score**: Demand (top videos ke views), Competition (jeetne wale channels kitne bade hain), chhote channels ki jeet, aur kitne videos already bane hain. 💎 GOLDEN = demand high, competition/videos low. ~102 quota units per keyword, 6 ghante cache |
| **Tracker** | Kisi bhi video (apni ya competitor ki) ko track karo — background me har 15 min–6 ghante stats save hote hain, graph banta hai, **acceleration** detect hota hai. Speed 50%+ badhe to notification |
| **Insights** | Trending se nikla pattern: best upload time (tumhare time zone me), trending keywords & tags, Shorts share, ideal length, title formula (numbers / CAPS / emoji %), top channels. **Title Checker** — apna title daalo, score + tips |
| **Niche alerts** | 5 keywords tak — har 3 ghante me naye breakout videos check, score threshold cross ho to notification |

## Viral Score
YouTube ka asli algorithm private hai; ye app wahi public signals measure karti hai jinpe wo chalta hai:
- Velocity 40% (views/hour, Shorts normalised)
- Outlier 25% (views ÷ channel subscribers)
- Engagement 20% ((likes + 2×comments) ÷ views)
- Freshness 15%

75+ = 🔥 VIRAL, 58+ = 🚀 RISING, 40+ = 📈 STEADY. Predictions estimate hain, guarantee nahi.

## Setup
1. APK install karo (Unknown sources allow karna padega).
2. Free API key: console.cloud.google.com → project → *YouTube Data API v3* enable → Credentials → API key.
3. App → Settings → key paste → Save. Free quota 10,000 units/day.

## Build
`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
GitHub Actions har push pe APK build karta hai (Actions tab → artifact `ViralRadar-apk`).
`keystore/viralradar.jks` ek personal sideload key hai taaki har nayi build purani app ko update kar sake.
