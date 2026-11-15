package com.gk.channelradar.engine

import com.gk.channelradar.data.ApiException
import com.gk.channelradar.data.Channel
import com.gk.channelradar.data.ChannelResult
import com.gk.channelradar.data.QuotaExhaustedException
import com.gk.channelradar.data.ScanConfig
import com.gk.channelradar.data.ScanResult
import com.gk.channelradar.data.ScoredVideo
import com.gk.channelradar.data.Store
import com.gk.channelradar.data.Video
import com.gk.channelradar.data.VideoFormat
import com.gk.channelradar.data.WordCount
import com.gk.channelradar.data.YouTubeApi
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
class DiscoveryEngine(private val api: YouTubeApi, private val store: Store) {

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
                    warnings += "Quota bachane ke liye kuch keywords skip kiye (aaj ka quota lagbhag khatam)."
                    break@loop
                }
                progress("Searching: \"$kw\"" + if (page > 0) " (page ${page + 1})" else "", step / totalSteps)
                try {
                    val (ids, next) = api.searchVideos(kw, after, cfg.region, cfg.language, cfg.format, token)
                    ids.forEach { origin.putIfAbsent(it, kw to niche) }
                    token = next
                } catch (e: QuotaExhaustedException) {
                    if (origin.isEmpty()) throw e
                    warnings += e.message ?: "Quota khatam"
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
                warnings += e.message ?: "Quota khatam"
                break
            } catch (e: Exception) {
                // some categories have no chart in some regions – that's fine
            }
            step++
        }

        if (origin.isEmpty()) {
            return emptyResult(cfg, keywords.map { it.first }, warnings + "Koi video nahi mila. Filters dheele karo ya doosre keywords try karo.", startUsed)
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
                    warnings += "Deep analysis beech me ruka: ${ex.message}"
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
