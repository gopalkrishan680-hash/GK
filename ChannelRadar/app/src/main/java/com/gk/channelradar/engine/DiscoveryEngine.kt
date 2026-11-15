package com.gk.channelradar.engine

import com.gk.channelradar.data.ApiException
import com.gk.channelradar.data.Channel
import com.gk.channelradar.data.ChannelResult
import com.gk.channelradar.data.KeywordReport
import com.gk.channelradar.data.QuotaExhaustedException
import com.gk.channelradar.data.ScanConfig
import com.gk.channelradar.data.ScanResult
import com.gk.channelradar.data.ScoredVideo
import com.gk.channelradar.data.Store
import com.gk.channelradar.data.Video
import com.gk.channelradar.data.VideoFormat
import com.gk.channelradar.data.WordCount
import com.gk.channelradar.data.YouTubeApi
import com.gk.channelradar.data.TrendsApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The "Small Channel Radar" process – our own pipeline, no third-party tools:
 *
 *  1. Collect fresh videos: YouTube search (sorted by views, last N days) across many niche
 *     keywords + the trending charts of many categories.
 *  2. Fetch exact stats of every video and every channel.
 *  3. Keep only SMALL channels (subscriber range you choose).
 *  4. Score each video:   views ÷ subscribers (reach beyond own audience),
 *                         views per hour (speed), engagement, and –
 *  5. Deep analysis: fetch the channel's last 15 uploads and compare the hit video with the
 *     channel's normal (median) views → "outlier ×". Also finds extra hits and consistency.
 *  6. Rank channels by a 0-100 Breakout Score.
 */
class DiscoveryEngine(private val api: YouTubeApi, private val store: Store, private val trends: TrendsApi) {

    suspend fun scan(cfg: ScanConfig, progress: (String, Float) -> Unit): ScanResult {
        val ctx = currentCoroutineContext()
        val startUsed = store.totalUsedToday()
        val warnings = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val after = now - cfg.days * 86_400_000L
        val keyCount = store.settings.apiKeys.count { it.isNotBlank() }.coerceAtLeast(1)
        val dailyBudget = 10_000 * keyCount

        // ---------------------------------------------------------------- 1. candidates
        val origin = LinkedHashMap<String, Pair<String, String>>() // videoId -> (foundBy, niche)
        val prefetched = HashMap<String, Video>()
        val keywords = Niches.pickKeywords(cfg)
        val trendingCats = if (cfg.includeTrending) Niches.trendingCategories else emptyList()
        val totalSteps = (keywords.size * cfg.pagesPerKeyword + trendingCats.size + 4).toFloat()
        var step = 0f

        loop@ for ((kw, niche) in keywords) {
            var token: String? = null
            for (page in 0 until cfg.pagesPerKeyword) {
                ctx.ensureActive()
                if (store.totalUsedToday() + 100 + 60 > dailyBudget) {
                    warnings += "Some keywords were skipped to save quota (today's quota is almost used up)."
                    break@loop
                }
                progress("Searching: \"$kw\"" + if (page > 0) " (page ${page + 1})" else "", step / totalSteps)
                try {
                    val (ids, next) = api.searchVideos(kw, after, cfg.region, cfg.language, cfg.format, token)
                    ids.forEach { origin.putIfAbsent(it, kw to niche) }
                    token = next
                } catch (e: QuotaExhaustedException) {
                    if (origin.isEmpty()) throw e
                    warnings += e.message ?: "Quota used up"
                    break@loop
                } catch (e: ApiException) {
                    if (e.reason == "noKey" || e.code == 400 && e.message?.contains("key", true) == true) throw e
                    warnings += "\"$kw\": ${e.message}"
                }
                step++
                if (token == null) break
            }
        }

        for ((catId, catName) in trendingCats) {
            ctx.ensureActive()
            progress("Trending chart: $catName", step / totalSteps)
            try {
                var token: String? = null
                val pages = if (catId == null) 4 else 1
                for (p in 0 until pages) {
                    val (vids, next) = api.trending(cfg.region, catId, token)
                    vids.forEach { v ->
                        prefetched[v.id] = v
                        origin.putIfAbsent(v.id, "Trending · $catName" to trendNiche(catName))
                    }
                    token = next ?: break
                }
            } catch (e: QuotaExhaustedException) {
                warnings += e.message ?: "Quota used up"
                break
            } catch (e: Exception) {
                // some categories have no chart in some regions – that's fine
            }
            step++
        }

        if (origin.isEmpty()) {
            return emptyResult(cfg, keywords.map { it.first }, warnings + "No videos found. Try looser filters or different keywords.", startUsed)
        }

        // ---------------------------------------------------------------- 2. video stats
        ctx.ensureActive()
        progress("Fetching stats of ${origin.size} videos…", step++ / totalSteps)
        val missing = origin.keys.filter { it !in prefetched }
        val fetched = api.videos(missing).associateBy { it.id }
        val allVideos = (prefetched + fetched).values.map { v ->
            val (by, niche) = origin[v.id] ?: ("" to "")
            v.copy(foundBy = by, niche = niche)
        }
        val videos = allVideos.filter { v ->
            v.publishedAt >= after && v.views >= cfg.minViews && formatOk(v, cfg.format)
        }

        // ---------------------------------------------------------------- 3. channel stats
        ctx.ensureActive()
        val hidden = store.hidden
        val channelIds = videos.map { it.channelId }.distinct().filter { it !in hidden }
        progress("Checking ${channelIds.size} channels…", step++ / totalSteps)
        val channels = api.channels(channelIds).associateBy { it.id }
        val small = channels.values.filter { c ->
            !c.hiddenSubs && c.subs >= cfg.minSubs && c.subs <= cfg.maxSubs
        }.associateBy { it.id }

        // ---------------------------------------------------------------- 4. first scoring
        val firstHits = videos.filter { it.channelId in small }
            .map { score(it, small.getValue(it.channelId), null, now) }
            .filter { it.ratio >= cfg.minRatio }
        var grouped: Map<String, List<ScoredVideo>> = firstHits.groupBy { it.video.channelId }

        // ---------------------------------------------------------------- 5. deep analysis
        val uploadsByChannel = HashMap<String, List<Video>>()
        if (cfg.deep && grouped.isNotEmpty()) {
            val top = grouped.entries.sortedByDescending { e -> e.value.maxOf { it.score } }.take(80)
            val idsByChannel = HashMap<String, List<String>>()
            top.forEachIndexed { i, e ->
                ctx.ensureActive()
                if (i % 5 == 0) progress("Deep analysis: channel ${i + 1}/${top.size}", (step + i.toFloat() / top.size) / totalSteps)
                try {
                    idsByChannel[e.key] = api.uploadIds(small.getValue(e.key).uploads, 15)
                } catch (ex: QuotaExhaustedException) {
                    warnings += "Deep analysis stopped early: ${ex.message}"
                    return@forEachIndexed
                } catch (_: Exception) {
                }
            }
            step++
            progress("Comparing with each channel's normal views…", step / totalSteps)
            try {
                val upVideos = api.videos(idsByChannel.values.flatten()).associateBy { it.id }
                idsByChannel.forEach { (cid, ids) -> uploadsByChannel[cid] = ids.mapNotNull { upVideos[it] } }
            } catch (ex: Exception) {
                warnings += "Deep analysis incomplete: ${ex.message}"
            }
        }
        step++

        // ---------------------------------------------------------------- 6. build results
        val seen = store.seen
        val results = small.values.mapNotNull { ch ->
            val uploads = uploadsByChannel[ch.id] ?: emptyList()
            val baseHits = grouped[ch.id] ?: return@mapNotNull null
            // extra hits discovered among the channel's own recent uploads
            val hitIds = baseHits.map { it.video.id }.toSet()
            val extra = uploads.filter {
                it.id !in hitIds && it.publishedAt >= after && it.views >= cfg.minViews && formatOk(it, cfg.format)
            }.map { it.copy(foundBy = "Channel uploads", niche = baseHits.first().video.niche) }

            val matured = uploads.filter { now - it.publishedAt > 48 * 3_600_000L }
            val allHits = (baseHits.map { it.video } + extra).map { v ->
                val pool = matured.filter { it.id != v.id && it.isShort == v.isShort }
                    .takeIf { it.size >= 3 } ?: matured.filter { it.id != v.id }
                val baseline = if (pool.size >= 3) median(pool.map { it.views }) else null
                score(v, ch, baseline, now)
            }.filter { it.ratio >= cfg.minRatio }
            if (allHits.isEmpty()) return@mapNotNull null

            val baselineAll = if (matured.size >= 3) median(matured.map { it.views }) else null
            val consistency = if (matured.size >= 3) matured.count { it.views >= max(ch.subs, 1) }.toDouble() / matured.size else null
            val best = allHits.maxOf { it.score }
            var chScore = best + min(10.0, 3.0 * (allHits.size - 1))
            if (consistency != null && consistency >= 0.5) chScore += 5
            ChannelResult(
                channel = ch,
                hits = allHits.sortedByDescending { it.score },
                recentUploads = uploads,
                baselineMedian = baselineAll,
                consistency = consistency,
                score = min(100.0, chScore),
                tags = tags(ch, allHits, consistency, now),
                niches = allHits.map { it.video.niche }.filter { it.isNotBlank() }.distinct(),
                isNew = ch.id !in seen,
            )
        }.sortedByDescending { it.score }

        store.seen = (seen + results.map { it.channel.id }).let { if (it.size > 20_000) it.toList().takeLast(20_000).toSet() else it }

        progress("Done", 1f)
        return ScanResult(
            time = now,
            config = cfg,
            channels = results,
            videosScanned = allVideos.size,
            channelsChecked = channels.size,
            quotaUsed = store.totalUsedToday() - startUsed,
            keywordsUsed = keywords.map { it.first },
            warnings = warnings.distinct(),
            trendingWords = trendingWords(results),
        )
    }

    /** Loads a channel's latest uploads (costs ~2 units). Used by the channel screen and watchlist. */
    fun loadUploads(channel: Channel): List<Video> {
        val ids = api.uploadIds(channel.uploads, 20)
        if (ids.isEmpty()) return emptyList()
        val byId = api.videos(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun refreshChannels(ids: List<String>): List<Channel> = api.channels(ids)

    /**
     * Keyword research (~102 units + free autocomplete calls):
     *  • Search volume  – estimated from YouTube autocomplete depth (how early YouTube suggests it)
     *                     + total views that recent videos on the keyword are getting.
     *  • Competition    – how many videos were uploaded on it in 30 days, how big the ranking
     *                     channels are, and how many top results belong to big channels.
     *  • Opportunity    – demand high, competition beatable, small channels winning, fresh results.
     */
    fun analyzeKeyword(keyword: String, region: String, language: String): KeywordReport {
        val now = System.currentTimeMillis()
        val auto = try { trends.searchPopularity(keyword, region, language) } catch (_: Exception) { TrendsApi.AutoSignal(0.0, -1, emptyList()) }
        val page = api.searchPage(keyword, now - 30 * 86_400_000L, region, language, VideoFormat.ANY, null)
        val vids = api.videos(page.ids).filter { it.views > 0 }
        if (vids.isEmpty()) return KeywordReport(
            keyword, now, region, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, "🧊 No recent videos found for this keyword",
            autocompleteScore = auto.score, prefixLength = auto.prefixLength, related = auto.related,
            searchIndex = auto.score * 0.5, recentUploads = page.totalResults,
        )
        val chans = api.channels(vids.map { it.channelId }).associateBy { it.id }
        fun subsOf(v: Video) = chans[v.channelId]?.takeIf { !it.hiddenSubs }?.subs

        // ---- demand / search volume
        val median = median(vids.map { it.views })
        val monthlyViews = vids.sumOf { it.views }
        val viewNorm = ((log10(max(monthlyViews, 1L).toDouble()) - 4) / 4).coerceIn(0.0, 1.0)   // 10K→0, 100M→1
        val searchIndex = (0.5 * auto.score + 50 * viewNorm).coerceIn(0.0, 100.0)
        // Rough model: ~8% of a topic's views come from search, scaled by how strongly autocomplete pushes it.
        val estSearches = (monthlyViews * 0.08 * (0.4 + 1.2 * auto.score / 100)).toLong()

        // ---- competition
        val subsList = vids.mapNotNull { subsOf(it) }
        val medianSubs = if (subsList.isEmpty()) 0L else median(subsList)
        val bigShare = vids.count { (subsOf(it) ?: 0L) >= 100_000 }.toDouble() / vids.size
        val supply = ((log10(page.totalResults + 1.0) - 2) / 3).coerceIn(0.0, 1.0)              // 100→0, 100K→1
        val subsNorm = ((log10(max(medianSubs, 1L).toDouble()) - 3) / 4).coerceIn(0.0, 1.0)     // 1K→0, 10M→1
        val competition = 100 * (0.35 * supply + 0.35 * bigShare + 0.30 * subsNorm)

        // ---- small-channel success & freshness
        val fresh = vids.count { now - it.publishedAt < 7 * 86_400_000L }.toDouble() / vids.size
        val small = vids.filter { (subsOf(it) ?: Long.MAX_VALUE) < 100_000 }
        val smallWins = small.filter { it.views >= max(subsOf(it) ?: 1L, 1L) }
        val smallWinRate = if (small.isEmpty()) 0.0 else smallWins.size.toDouble() / small.size
        val successShare = smallWins.size.toDouble() / vids.size

        val demand = searchIndex / 100
        val opp = (100 * (0.35 * demand + 0.30 * (1 - competition / 100) + 0.20 * min(1.0, successShare * 2.5) + 0.15 * fresh)).coerceIn(0.0, 100.0)
        val verdict = when {
            opp >= 65 -> "🔥 Hot opportunity: high demand and beatable competition"
            demand >= 0.55 && competition >= 65 -> "🏰 High demand, but very competitive"
            demand < 0.25 -> "🧊 Low search demand right now"
            competition < 35 && demand >= 0.35 -> "💎 Hidden gem: decent demand, low competition"
            opp >= 45 -> "👍 Good: worth making a video on this"
            else -> "😐 Average: needs a unique angle"
        }

        // ---- content insights from the top videos
        val shortsShare = vids.count { it.isShort }.toDouble() / vids.size
        val longs = vids.filter { !it.isShort && it.durationSec > 0 }
        val typicalLen = if (longs.isEmpty()) 0 else median(longs.map { it.durationSec.toLong() }).toInt()
        val zone = java.time.ZoneId.systemDefault()
        val topHalf = vids.sortedByDescending { it.views }.take(max(5, vids.size / 2))
        val times = topHalf.map { java.time.Instant.ofEpochMilli(it.publishedAt).atZone(zone) }
        val bestDay = times.groupingBy { it.dayOfWeek }.eachCount().maxByOrNull { it.value }?.key
            ?.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) ?: ""
        val bestHour = times.groupingBy { it.hour }.eachCount().maxByOrNull { it.value }?.key ?: -1
        val kwWords = keyword.lowercase().split(' ').toSet()
        val splitter = Regex("[^\\p{L}\\p{M}\\p{N}#]+")
        val titleWords = vids.flatMap { v -> splitter.split(v.title.lowercase()).filter { it.length >= 3 }.toSet() }
            .filter { it !in stop && it !in kwWords && it.toIntOrNull() == null }
            .groupingBy { it }.eachCount().filter { it.value >= 3 }
            .entries.sortedByDescending { it.value }.take(10).map { it.key }

        val examples = smallWins.map { v -> score(v, chans.getValue(v.channelId), null, now) }
            .sortedByDescending { it.ratio }.take(5)
        return KeywordReport(
            keyword = keyword, time = now, region = region, videosChecked = vids.size,
            medianViews = median, topViews = vids.maxOf { it.views }, freshShare = fresh,
            smallChannelShare = small.size.toDouble() / vids.size, smallWinRate = smallWinRate, opportunity = opp,
            verdict = verdict, examples = examples,
            exampleChannels = examples.associate { it.video.channelId to (chans[it.video.channelId]?.subs ?: 0L) },
            estSearches = estSearches, searchIndex = searchIndex, autocompleteScore = auto.score,
            prefixLength = auto.prefixLength, monthlyViews = monthlyViews, recentUploads = page.totalResults,
            competition = competition, bigChannelShare = bigShare, medianTopSubs = medianSubs,
            shortsShare = shortsShare, typicalLengthSec = typicalLen, bestDay = bestDay, bestHour = bestHour,
            related = auto.related, titleWords = titleWords,
        )
    }

    // ====================================================================== scoring

    private fun score(v: Video, ch: Channel, baseline: Long?, now: Long): ScoredVideo {
        val ageHours = max(1.0, (now - v.publishedAt) / 3_600_000.0)
        val vph = v.views / ageHours
        val ratio = v.views.toDouble() / max(ch.subs, 50L)
        val engagement = if (v.views > 0) (v.likes + 2.0 * v.comments) / v.views else 0.0
        val outlier = baseline?.let { v.views.toDouble() / max(it, 10L) }

        // each part normalised to 0..1 on a log scale
        val r = ((log10(max(ratio, 0.01)) + 1) / 3).coerceIn(0.0, 1.0)        // 0.1x→0, 1x→.33, 10x→.67, 100x→1
        val s = (log10(max(vph, 1.0)) / 4).coerceIn(0.0, 1.0)                  // 10/h→.25 … 10k/h→1
        val o = outlier?.let { (ln(max(it, 1.0)) / ln(2.0) / 5).coerceIn(0.0, 1.0) } ?: r // 2x→.2, 32x→1
        val e = (engagement / 0.08).coerceIn(0.0, 1.0)
        val total = 100 * (0.35 * r + 0.25 * o + 0.25 * s + 0.15 * e)
        return ScoredVideo(v, ageHours, vph, ratio, engagement, outlier, total)
    }

    private fun tags(ch: Channel, hits: List<ScoredVideo>, consistency: Double?, now: Long): List<String> {
        val best = hits.maxBy { it.score }
        val t = mutableListOf<String>()
        val maxRatio = hits.maxOf { it.ratio }
        if (maxRatio >= 10) t += "🚀 Viral ${fmtX(maxRatio)} subs"
        val maxOut = hits.mapNotNull { it.outlier }.maxOrNull()
        if (maxOut != null && maxOut >= 3) t += "📈 Outlier ${fmtX(maxOut)}"
        val ageDays = (now - ch.createdAt) / 86_400_000L
        if (ch.createdAt > 0 && ageDays < 365) t += "🌱 New channel (${max(1, ageDays / 30)} mo)"
        if (hits.size >= 2) t += "🔁 ${hits.size} hits"
        if (consistency != null && consistency >= 0.5) t += "✅ Consistent"
        if (best.ageHours < 72 && best.viewsPerHour >= 1000) t += "⚡ Fast rising"
        if (best.engagement >= 0.05) t += "💬 High engagement"
        t += if (best.video.isShort) "📱 Shorts" else "🎬 Long-form"
        return t
    }

    private fun fmtX(x: Double) = if (x >= 10) "${x.toInt()}x" else "%.1fx".format(x)

    private fun formatOk(v: Video, f: VideoFormat) = when (f) {
        VideoFormat.ANY -> true
        VideoFormat.SHORTS -> v.isShort
        VideoFormat.LONG -> !v.isShort
    }

    private fun median(xs: List<Long>): Long {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun trendNiche(cat: String) = when (cat) {
        "Gaming" -> "Gaming"; "Comedy" -> "Comedy"; "Education" -> "Education"; "Music" -> "Music"
        "Sports" -> "Sports"; "News" -> "News & Politics"; "Autos" -> "Auto"; "Pets & Animals" -> "Pets & Animals"
        "Science & Tech" -> "Tech"; "Howto & Style" -> "DIY & Crafts"; "Film & Animation" -> "Movies & Reviews"
        "People & Blogs" -> "Vlogs"
        else -> "Trending"
    }

    private fun emptyResult(cfg: ScanConfig, kws: List<String>, warnings: List<String>, startUsed: Int) = ScanResult(
        time = System.currentTimeMillis(), config = cfg, channels = emptyList(), videosScanned = 0,
        channelsChecked = 0, quotaUsed = store.totalUsedToday() - startUsed, keywordsUsed = kws,
        warnings = warnings, trendingWords = emptyList(),
    )

    // ====================================================================== title insights

    private val stop = setOf(
        "the", "and", "for", "you", "your", "with", "this", "that", "from", "are", "was", "how", "what", "why",
        "who", "all", "can", "not", "but", "out", "new", "have", "has", "will", "into", "our", "its", "get",
        "video", "videos", "shorts", "short", "#shorts", "#short", "#viral", "#trending", "#youtubeshorts",
        "hai", "hain", "aur", "kya", "kaise", "nahi", "bhi", "mein", "par", "wala", "wali", "kar", "karo",
        "है", "और", "का", "की", "के", "में", "से", "को", "पर", "ये", "यह", "भी", "नहीं", "क्या", "कैसे", "एक", "तो", "हैं",
        "part", "full", "episode", "official",
    )

    private fun trendingWords(results: List<ChannelResult>): List<WordCount> {
        val counts = HashMap<String, Int>()
        val splitter = Regex("[^\\p{L}\\p{M}\\p{N}#]+")
        results.forEach { r ->
            // count each word once per channel so one channel can't dominate
            r.hits.flatMap { h -> splitter.split(h.video.title.lowercase()) }
                .map { it.trim() }
                .filter { (it.length >= 3 || it.startsWith("#") && it.length > 2) && it !in stop && it.toIntOrNull() == null }
                .toSet()
                .forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.filter { it.value >= 2 }.entries.sortedByDescending { it.value }.take(25)
            .map { WordCount(it.key, it.value) }
    }
}
