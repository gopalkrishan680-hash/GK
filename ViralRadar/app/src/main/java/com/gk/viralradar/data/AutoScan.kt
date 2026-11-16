package com.gk.viralradar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.log10

/**
 * Fully automatic "what should I make right now" scan:
 *  1. Collect live trending videos for every category (cheap: ~2 units per category).
 *  2. Mine topics from their tags, hashtags and title phrases, weighting topics where
 *     small channels are over-performing (proof that demand > supply).
 *  3. Add Google Trends "trending now" searches for the region (free).
 *  4. Verify with YouTube autocomplete that people actually search the topic (free).
 *  5. Deep-analyse the best N with live search data -> Opportunity Score.
 */
object AutoScan {
    const val SRC_TRENDING = "🔥 Trending videos"
    const val SRC_SMALL = "🐣 Chhote channels jeet rahe"
    const val SRC_GTRENDS = "📈 Google Trends"
    const val SRC_SEARCHED = "🔎 Log search kar rahe"

    private val GENERIC = setOf(
        "trending", "viral", "latest", "today", "live", "youtube", "videos", "hindi", "india", "indian", "status",
        "reels", "reel", "subscribe", "like", "share", "comment", "channel", "funny video", "new song", "full video",
        "official video", "music video", "lyrical", "lyrics", "audio", "trailer", "teaser", "new", "best", "top",
        "viral video", "trending video", "shorts video", "youtube shorts", "viralvideo", "viralshorts", "trend",
        "shortsfeed", "shortvideo", "ytshorts", "foryou", "fyp", "explore", "explorepage", "short feed", "feed",
        "का", "की", "के", "में", "है", "और", "से", "को", "पर", "नया", "नई", "तरीका", "वीडियो", "आज", "सबसे", "कैसे",
        "बनाने", "आसान", "वाला", "वाली", "kaise", "wala", "wali", "sabse", "naya", "aaj",
    )

    internal class Cand(val phrase: String) {
        var weight = 0.0
        val channels = mutableSetOf<String>()
        var smallHits = 0
        val sources = mutableSetOf<String>()
    }

    private fun tokens(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotBlank() }

    private fun phrasesOf(v: Video): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        val ch = v.channelTitle.lowercase().trim()
        fun ok(p: String) = p.length in 3..40 && p !in Insights.STOP && p !in GENERIC &&
            !p.all { it.isDigit() || it == ' ' } && (ch.length < 3 || (!ch.contains(p) && !p.contains(ch)))
        fun put(p: String, w: Double) { if (ok(p)) out[p] = maxOf(out[p] ?: 0.0, w) }

        v.tags.take(25).forEach { t ->
            val n = tokens(t)
            if (n.size in 1..4) put(n.joinToString(" "), if (n.size == 1) 0.7 else 1.0)
        }
        Regex("#([\\p{L}\\p{M}\\p{N}_]+)").findAll(v.title + " " + v.description.take(300)).take(6)
            .forEach { put(it.groupValues[1].lowercase().replace('_', ' '), 0.9) }
        val w = tokens(v.title)
        for (n in 2..3) for (i in 0..(w.size - n)) {
            val g = w.subList(i, i + n)
            if (g.first() in Insights.STOP || g.last() in Insights.STOP || g.any { it.length < 2 }) continue
            put(g.joinToString(" "), 1.0)
        }
        w.filter { it.length >= 4 && it !in Insights.STOP }.forEach { put(it, 0.5) }
        return out
    }

    /** Google Trends "trending now" RSS for a country. Free; empty list on failure. */
    fun googleTrends(region: String): List<Pair<String, Long>> {
        val conn = URL("https://trends.google.com/trending/rss?geo=${URLEncoder.encode(region, "UTF-8")}")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 10000
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(body).mapNotNull { m ->
                val title = Regex("<title>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>").find(m.value)?.groupValues?.get(1)
                    ?.replace("&amp;", "&")?.replace("&#39;", "'")?.replace("&quot;", "\"")?.trim()?.lowercase()
                val traffic = Regex("<ht:approx_traffic>([\\d,]+)").find(m.value)?.groupValues?.get(1)
                    ?.replace(",", "")?.toLongOrNull() ?: 1000
                title?.takeIf { it.isNotBlank() }?.let { it to traffic }
            }.toList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /** Steps 2-3: mine topics from live videos + Google Trends, ranked and de-duplicated. */
    internal fun rankTopics(videos: List<Video>, trends: List<Pair<String, Long>>, now: Long): List<Cand> {
        val scored = videos.map { it to ViralScore.score(it, now) }
        val cands = mutableMapOf<String, Cand>()
        scored.forEach { (v, s) ->
            val small = (v.subs ?: Long.MAX_VALUE) < 100_000
            val outlier = (s.outlier ?: 0.0) >= 3
            val w = 1 + s.score / 40.0 + (if (outlier) 2.0 else 0.0) + (if (small) 1.0 else 0.0)
            phrasesOf(v).forEach { (p, pw) ->
                val c = cands.getOrPut(p) { Cand(p) }
                c.weight += w * pw
                c.channels += v.channelId
                c.sources += SRC_TRENDING
                if (small && (outlier || s.score >= 58)) { c.smallHits++; c.sources += SRC_SMALL }
            }
        }
        // Topic must appear on at least 2 different channels (avoids one channel's tag spam).
        cands.values.removeAll { it.channels.size < 2 }
        cands.values.forEach { it.weight *= 1 + 0.15 * it.channels.size + 0.4 * it.smallHits }

        trends.take(20).forEach { (t, traffic) ->
            if (t.length !in 3..40) return@forEach
            val c = cands.getOrPut(t) { Cand(t) }
            c.weight += 4 + 2 * log10(traffic.toDouble().coerceAtLeast(10.0))
            c.sources += SRC_GTRENDS
        }

        val ranked = mutableListOf<Cand>()
        for (c in cands.values.sortedByDescending { it.weight }) {
            if (ranked.none { it.phrase.contains(c.phrase) || c.phrase.contains(it.phrase) }) ranked += c
            if (ranked.size >= 30) break
        }
        return ranked
    }

    fun run(ctx: Context, prefs: Prefs, progress: (stage: String, done: Int, total: Int) -> Unit): List<KeywordReport> {
        val key = prefs.apiKey
        val region = prefs.region
        val now = System.currentTimeMillis()

        // 1. Live trending across categories
        progress("Live trending videos collect ho rahe hain…", 0, 0)
        val vids = linkedMapOf<String, Video>()
        YouTubeApi.trending(key, region, null).forEach { vids[it.id] = it }
        val cats = runCatching { YouTubeApi.categories(key, region) }.getOrDefault(emptyList())
        cats.forEachIndexed { i, (id, name) ->
            progress("Category scan: $name", i + 1, cats.size)
            runCatching { YouTubeApi.trending(key, region, id, pages = 1) }.getOrNull()?.forEach { vids.putIfAbsent(it.id, it) }
        }
        progress("${vids.size} videos se topics nikaal rahe hain…", 0, 0)
        progress("Google Trends check ho raha hai…", 0, 0)
        val ranked = rankTopics(vids.values.toList(), googleTrends(region), now)

        // 4. Autocomplete verification (free)
        val searched = mutableSetOf<String>()
        ranked.forEachIndexed { i, c ->
            progress("Search demand verify: ${c.phrase}", i + 1, ranked.size)
            val sug = YouTubeApi.suggestions(c.phrase, region)
            if (sug.any { it.lowercase().startsWith(c.phrase) || c.phrase in it.lowercase() }) {
                searched += c.phrase; c.sources += SRC_SEARCHED; c.weight *= 1.35
            } else if (SRC_GTRENDS !in c.sources) c.weight *= 0.8
        }
        val pick = ranked.sortedByDescending { it.weight }.take(prefs.autoScanSize.coerceIn(3, 15))

        // 5. Deep analysis
        val reports = mutableListOf<KeywordReport>()
        val stored = JSONArray()
        pick.forEachIndexed { i, c ->
            progress("Analyzing \"${c.phrase}\"", i + 1, pick.size)
            val r = try {
                YouTubeApi.searchWithTotal(key, c.phrase, prefs.autoScanHours, region)
            } catch (e: ApiException) {
                if (reports.isEmpty()) throw e else return@forEachIndexed  // quota hit mid-way: keep what we have
            }
            val (v, total) = r
            if (v.isEmpty()) return@forEachIndexed
            reports += Opportunity.build(c.phrase, v, total, c.phrase in searched, prefs.autoScanHours).copy(sources = c.sources)
            stored.put(entryJson(c.phrase, c.sources, total, c.phrase in searched, prefs.autoScanHours, now, v))
        }
        if (reports.isNotEmpty()) {
            File(ctx.filesDir, FILE).writeText(JSONObject().put("t", now).put("region", region).put("items", stored).toString())
            prefs.lastAutoScan = now
        }
        return reports.sortedByDescending { it.score }
    }

    // ---------- persistence: store raw data, rebuild reports on load ----------
    private const val FILE = "autoscan.json"

    fun load(ctx: Context): Pair<Long, List<KeywordReport>> = runCatching {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return 0L to emptyList()
        val o = JSONObject(f.readText())
        val items = o.getJSONArray("items")
        val reps = (0 until items.length()).map { i ->
            val e = items.getJSONObject(i)
            val vs = e.getJSONArray("v")
            val videos = (0 until vs.length()).map { videoFromJson(vs.getJSONObject(it)) }
            val src = e.getJSONArray("src").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            Opportunity.build(e.getString("k"), videos, e.getLong("total"), e.getBoolean("auto"), e.getInt("h"))
                .copy(sources = src, updatedAt = e.getLong("t"))
        }
        o.getLong("t") to reps.sortedByDescending { it.score }
    }.getOrDefault(0L to emptyList())

    private fun entryJson(k: String, src: Set<String>, total: Long, auto: Boolean, h: Int, t: Long, v: List<Video>) =
        JSONObject().put("k", k).put("src", JSONArray(src.toList())).put("total", total).put("auto", auto)
            .put("h", h).put("t", t).put("v", JSONArray().apply { v.forEach { put(videoJson(it)) } })

    private fun videoJson(v: Video) = JSONObject()
        .put("id", v.id).put("ti", v.title).put("ci", v.channelId).put("ct", v.channelTitle).put("p", v.publishedAt)
        .put("th", v.thumbnail).put("vw", v.views).put("lk", v.likes).put("cm", v.comments).put("d", v.durationSec)
        .put("tg", JSONArray(v.tags.take(15))).put("cat", v.categoryId).put("s", v.subs ?: -1)
        .put("wl", v.wasLive)

    private fun videoFromJson(o: JSONObject) = Video(
        id = o.getString("id"), title = o.getString("ti"), channelId = o.optString("ci"), channelTitle = o.optString("ct"),
        publishedAt = o.getLong("p"), thumbnail = o.optString("th"), views = o.getLong("vw"), likes = o.optLong("lk"),
        comments = o.optLong("cm"), durationSec = o.optLong("d"),
        tags = o.optJSONArray("tg")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        categoryId = o.optString("cat"), description = "", subs = o.optLong("s", -1).takeIf { it >= 0 },
        wasLive = o.optBoolean("wl"),
    )

    /** Rough quota estimate for one scan. */
    fun quotaEstimate(size: Int) = 40 + size * 102

}
