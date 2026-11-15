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

## Trends tab (search demand, free – no quota)
Every topic in the YouTube and Google lists automatically shows **Searches / month (est.)**, **Demand** and **Competition**,
measured for free from YouTube's public search results (total videos, views per month on the top results, share of verified channels)
plus autocomplete strength. Lists can be sorted by most searched, highest demand, lowest competition or best opportunity.
- **YouTube**: explores YouTube's live search autocomplete (topic + a–z variations). Phrases that appear in many suggestion lists near the top = high search demand right now. One tap "Hot across all niches".
- **Google**: today's fastest-rising Google searches for your country (Google Trends RSS) with approx. search volume and the news behind it.
- **Keywords** (~102 units per keyword): for any keyword shows
  - **Est. searches / month** – own model: YouTube autocomplete depth (how few letters you type before YouTube suggests it) × views recent videos get. YouTube publishes no exact numbers, so it's shown as a range.
  - **Demand** (0–100), **Competition** (0–100: videos uploaded in 30 days, size of ranking channels, share of big channels) and **Opportunity** (0–100) + verdict
  - Compare table (sort by opportunity / searches / low competition), "Check top 5" bulk research
  - Best day & time to post, Shorts share, typical video length, words used in top titles, related searches, small channels winning on it
- Any idea can be sent to the scanner with ➕ "Use in scan".

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
- Scan history (last 8 scans) and optional daily auto-scan with notification for NEW rising channels
- Hide channels, CSV export (Excel me khulta hai), share

## Quota
Har API key = 10,000 free units/day. Ek keyword search = 100 units; baaki sab bahut sasta.
"Balanced" scan ≈ 900 units → ek key se roz ~10 scans.

## Build
```
cd ChannelRadar
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```
