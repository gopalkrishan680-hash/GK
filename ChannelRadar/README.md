# Channel Radar 📡

Android app jo **chote YouTube channels** dhoondhta hai jinke **recent videos ache views** le rahe hain – kisi bhi niche me.
Koi vidIQ / credit-based tool nahi: app seedha YouTube ke official **free** Data API v3 se data leta hai aur apna scoring process chalata hai.

## Install
1. `apk/ChannelRadar.apk` phone par download karo (ya GitHub → Actions → latest run → artifact).
2. Open karo → "Install unknown apps" allow karo → Install.
3. App kholo → **Settings** → apni free YouTube API key paste karo (steps app ke andar likhe hain).

## Process (khud ka algorithm)
1. **Collect** – kai niches ke keywords par search (last N days, views ke hisaab se sort) + 14 categories ke trending charts.
2. **Stats** – har video aur channel ke exact views / subscribers / likes / comments.
3. **Filter** – sirf chote channels (subscriber range aap chuno), min views, Shorts / long.
4. **Score** – har video:
   - *Views ÷ Subscribers* – channel apni audience se kitna aage gaya
   - *Views per hour* – speed
   - *Engagement* – (likes + 2×comments) ÷ views
   - *Outlier ×* – channel ke last 15 videos ke median views se kitna guna (deep analysis)
5. **Breakout Score (0-100)** = 35% ratio + 25% outlier + 25% speed + 15% engagement, + bonus for multiple hits & consistency.

## Features
- 28 niches (Hindi + English keywords) ya apne custom keywords, "All" = random mix of niches
- Presets: Quick, Balanced, Deep dive, Shorts hunter, Tiny (<1K subs), Long-form gems
- Filters: video age, min/max subscribers, min views, min views÷subs, Shorts/Long, region, language
- Results: Channel view & Video view, 7 sort options, niche filter, search, "NEW" (pehle kabhi nahi dikha) badge
- Tags: 🚀 Viral, 📈 Outlier, 🌱 New channel, 🔁 Multiple hits, ✅ Consistent, ⚡ Fast rising, 💬 High engagement
- 🔥 Breakout title words – viral titles me common words
- Channel page: stats, upload frequency, bar chart of recent uploads vs normal views & subscriber line
- Watchlist with growth tracking (subs/day, % growth, sparkline)
- Multiple API keys with auto-rotation + quota meter
- Hide channels, CSV export (Excel me khulta hai), share

## Quota
Har API key = 10,000 free units/day. Ek keyword search = 100 units; baaki sab bahut sasta.
"Balanced" scan ≈ 900 units → ek key se roz ~10 scans.

## Build
```
cd ChannelRadar
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```
