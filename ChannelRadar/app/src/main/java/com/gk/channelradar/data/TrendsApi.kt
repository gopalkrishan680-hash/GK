package com.gk.channelradar.data

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.max
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Free, key-less public sources of "what people are searching right now":
 *  - YouTube search autocomplete (what users are typing on YouTube)
 *  - Google Trends daily trending searches RSS
 * No credits, no third-party services.
 */
private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

class TrendsApi {

    // ------------------------------------------------------------------ YouTube autocomplete

    fun suggestions(query: String, region: String, language: String): List<String> {
        val hl = language.ifBlank { "en" }
        val q = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&ie=utf-8&oe=utf-8&hl=$hl&gl=$region&q=$q",
            "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&hl=$hl&gl=$region&q=$q",
        )
        var last: Exception? = null
        for (u in urls) {
            try {
                return parseSuggest(get(u))
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Could not load YouTube suggestions: ${last?.message}", last)
    }

    private fun parseSuggest(body: String): List<String> {
        // firefox client: ["q",["a","b",...], ...]   youtube client: window.google.ac.h(["q",[["a",0],["b",0]],...])
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = JSONArray(body.substring(start, end + 1))
        val list = arr.optJSONArray(1) ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            when (val item = list.get(i)) {
                is String -> item
                is JSONArray -> item.optString(0)
                else -> null
            }
        }.map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * Explores the autocomplete tree of a seed: "seed", "seed a" … "seed z", plus a few
     * intent prefixes. A phrase that shows up in many expansions and near the top is in demand.
     */
    fun explore(seeds: List<String>, region: String, language: String, deep: Boolean, progress: (Float) -> Unit): List<SearchIdea> {
        val queries = mutableListOf<Pair<String, String>>() // query -> seed
        seeds.forEach { s ->
            queries += s to s
            if (deep) {
                ('a'..'z').forEach { c -> queries += "$s $c" to s }
                listOf("how to", "best", "new", "why", "kaise").forEach { p -> queries += "$p $s" to s }
            }
        }
        val pool = Executors.newFixedThreadPool(6)
        val score = HashMap<String, Double>()
        val hits = HashMap<String, Int>()
        val seedOf = HashMap<String, String>()
        var failures = 0
        try {
            val futures: List<Pair<Pair<String, String>, Future<List<String>>>> =
                queries.map { q -> q to pool.submit<List<String>> { suggestions(q.first, region, language) } }
            futures.forEachIndexed { i, (q, f) ->
                val list = try { f.get() } catch (e: Exception) { failures++; emptyList() }
                list.forEachIndexed { pos, s ->
                    val key = s.lowercase()
                    score[key] = (score[key] ?: 0.0) + (10 - pos).coerceAtLeast(1)
                    hits[key] = (hits[key] ?: 0) + 1
                    seedOf.putIfAbsent(key, q.second)
                }
                progress((i + 1f) / futures.size)
            }
        } finally {
            pool.shutdownNow()
        }
        if (score.isEmpty() && failures > 0) throw IOException("YouTube suggestions are not reachable right now. Check your internet connection.")
        val max = score.values.maxOrNull() ?: 1.0
        return score.entries.sortedByDescending { it.value }.take(80).map { (k, v) ->
            SearchIdea(k, (v / max * 100).coerceIn(1.0, 100.0), hits[k] ?: 1, seedOf[k] ?: "")
        }
    }

    data class AutoSignal(
        /** 0..100 – how early YouTube autocompletes this phrase while typing (earlier = more searched) */
        val score: Double,
        /** characters typed before it was suggested, or -1 if never suggested */
        val prefixLength: Int,
        val related: List<String>,
    )

    /**
     * Free search-popularity signal from YouTube autocomplete. YouTube suggests the most searched
     * phrases first, so a phrase that already appears after typing 2-3 letters is searched a lot.
     */
    fun searchPopularity(keyword: String, region: String, language: String): AutoSignal {
        val k = keyword.trim().lowercase()
        val lengths = listOf(1, 2, 3, 4, 5, 6, 8, 10, 13, 16).filter { it < k.length } + k.length
        val pool = Executors.newFixedThreadPool(6)
        try {
            val prefixFutures = lengths.distinct().map { n -> n to pool.submit<List<String>> { suggestions(k.take(n), region, language) } }
            val relatedFuture = pool.submit<List<String>> { suggestions("$k ", region, language) }
            var found = -1
            var position = 9
            for ((n, f) in prefixFutures) {
                val list = try { f.get().map { it.lowercase() } } catch (_: Exception) { emptyList() }
                val idx = list.indexOfFirst { it == k || it.startsWith("$k ") }
                if (idx >= 0) { found = n; position = idx; break }
            }
            val related = try { relatedFuture.get() } catch (_: Exception) { emptyList() }
                .filter { !it.equals(k, true) }.distinct().take(10)
            val score = if (found < 0) {
                if (related.isEmpty()) 5.0 else 15.0 + related.size
            } else {
                val typedShare = found.toDouble() / k.length.coerceAtLeast(1)
                // 2 letters → ~95, full phrase needed → ~35; small bonus for top position
                val base = when {
                    found <= 2 -> 95.0
                    found <= 3 -> 85.0
                    found <= 4 -> 75.0
                    else -> 35.0 + (1 - typedShare) * 50
                }
                (base + (9 - position) * 0.6).coerceIn(10.0, 100.0)
            }
            return AutoSignal(score, found, related)
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ free keyword numbers

    private data class WebVideo(val views: Long, val ageDays: Double, val verified: Boolean)

    /**
     * Measures a keyword for free from YouTube's own public search (what anyone sees on youtube.com):
     * total number of results, the views the top results get per month and how many come from verified
     * channels. From that we estimate searches/month, demand and competition. No API key, no quota.
     * [autocompleteHint] (0..100, optional) = how strongly YouTube autocomplete pushes the phrase.
     */
    fun keywordStats(keyword: String, region: String, language: String, autocompleteHint: Double?): KeywordStats {
        val data = searchData(keyword, region, language)
        val total = data.optString("estimatedResults").toLongOrNull() ?: 0L
        val vids = mutableListOf<WebVideo>()
        collectVideos(data, vids, 0)
        if (vids.isEmpty()) throw IOException("No search results for \"$keyword\"")
        val top = vids.take(20)

        // views flowing to the top results per month: Σ views / age(days) × 30
        val monthlyViews = top.sumOf { it.views / max(it.ageDays, 1.0) * 30.0 }.toLong()
        val ac = autocompleteHint?.coerceIn(0.0, 100.0) ?: 50.0
        // ~8% of a topic's views come from search, adjusted by autocomplete strength
        val est = (monthlyViews * 0.08 * (0.5 + ac / 100)).toLong()
        val demand = ((log10(max(est, 1L).toDouble()) - 2) / 5 * 100).coerceIn(0.0, 100.0)        // 100→0, 10M→100

        val sortedViews = top.map { it.views }.sorted()
        val medianViews = sortedViews[sortedViews.size / 2]
        val verified = top.count { it.verified }.toDouble() / top.size
        val recent = top.count { it.ageDays <= 30 }.toDouble() / top.size
        val supply = ((log10(total + 1.0) - 3) / 4).coerceIn(0.0, 1.0)                                // 1K→0, 10M→1
        val strength = ((log10(max(medianViews, 1L).toDouble()) - 3) / 4).coerceIn(0.0, 1.0)          // 1K→0, 10M→1
        val competition = (100 * (0.35 * supply + 0.35 * verified + 0.30 * strength)).coerceIn(0.0, 100.0)
        // fresh results = YouTube is still rewarding new uploads on this topic
        val opportunity = (0.5 * demand + 0.35 * (100 - competition) + 15 * recent).coerceIn(0.0, 100.0)
        return KeywordStats(
            phrase = keyword, time = System.currentTimeMillis(), totalResults = total, estSearches = est,
            monthlyViews = monthlyViews, demand = demand, competition = competition, opportunity = opportunity,
            medianViews = medianViews, recentShare = recent, verifiedShare = verified, resultsChecked = top.size,
        )
    }

    private fun searchData(keyword: String, region: String, language: String): JSONObject {
        val hl = if (language.isBlank()) "en" else language
        // 1) the JSON endpoint youtube.com itself uses
        try {
            val body = JSONObject()
                .put("context", JSONObject().put("client", JSONObject()
                    .put("clientName", "WEB").put("clientVersion", WEB_VERSION).put("hl", hl).put("gl", region)))
                .put("query", keyword)
            val res = JSONObject(post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString()))
            if (res.has("contents")) return res
        } catch (_: Exception) {
        }
        // 2) fallback: the normal search page, which embeds the same data as ytInitialData
        val html = get("https://www.youtube.com/results?hl=$hl&gl=$region&search_query=" + URLEncoder.encode(keyword, "UTF-8"))
        val marker = html.indexOf("ytInitialData")
        if (marker < 0) throw IOException("YouTube search page format changed")
        val start = html.indexOf('{', marker)
        val end = html.indexOf(";</script>", start)
        if (start < 0 || end < 0) throw IOException("YouTube search page format changed")
        return JSONObject(html.substring(start, end))
    }

    private fun collectVideos(node: Any?, out: MutableList<WebVideo>, depth: Int) {
        if (depth > 40 || out.size >= 30) return
        when (node) {
            is JSONObject -> {
                val vr = node.optJSONObject("videoRenderer")
                if (vr != null) {
                    parseWebVideo(vr)?.let(out::add)
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) collectVideos(node.opt(keys.next()), out, depth + 1)
            }
            is JSONArray -> for (i in 0 until node.length()) collectVideos(node.opt(i), out, depth + 1)
        }
    }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }
    }

    private fun parseWebVideo(vr: JSONObject): WebVideo? {
        val viewsText = text(vr.optJSONObject("viewCountText"))
        if (viewsText.contains("watching", true)) return null // live right now
        val views = parseCount(viewsText) ?: return null
        val age = parseAgeDays(text(vr.optJSONObject("publishedTimeText"))) ?: 365.0
        val badges = vr.optJSONArray("ownerBadges")?.toString() ?: ""
        val verified = badges.contains("VERIFIED", true)
        return WebVideo(views, age, verified)
    }

    companion object {
        private const val WEB_VERSION = "2.20251101.00.00"

        /** "1,234,567 views", "1.2M views", "No views" → number */
        fun parseCount(t: String): Long? {
            val s = t.lowercase().replace(",", "").trim()
            if (s.startsWith("no ")) return 0
            val m = Regex("""([\d.]+)\s*([kmb])?""").find(s) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2]) { "k" -> 1e3; "m" -> 1e6; "b" -> 1e9; else -> 1.0 }
            return (n * mult).toLong()
        }

        /** "3 days ago", "Streamed 2 weeks ago" → days */
        fun parseAgeDays(t: String): Double? {
            val m = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)""").find(t.lowercase()) ?: return null
            val n = m.groupValues[1].toDouble()
            return when (m.groupValues[2]) {
                "second" -> n / 86400; "minute" -> n / 1440; "hour" -> n / 24; "day" -> n
                "week" -> n * 7; "month" -> n * 30; else -> n * 365
            }
        }
    }

    // ------------------------------------------------------------------ Google Trends

    fun googleTrends(region: String): List<TrendItem> {
        val body = get("https://trends.google.com/trending/rss?geo=$region")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(body.reader())
        val out = mutableListOf<TrendItem>()
        var inItem = false
        var inNews = false
        var title = ""; var traffic = ""; var date = 0L; var picture = ""
        var newsTitle = ""; var newsUrl = ""; var newsSource = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "item" -> { inItem = true; title = ""; traffic = ""; date = 0; picture = ""; newsTitle = ""; newsUrl = ""; newsSource = "" }
                    "news_item" -> inNews = true
                    "title" -> if (inItem && !inNews) title = parser.nextText().trim()
                    "approx_traffic" -> if (inItem) traffic = parser.nextText().trim()
                    "pubDate" -> if (inItem) date = parseRssDate(parser.nextText().trim())
                    "picture" -> if (inItem) picture = parser.nextText().trim()
                    "news_item_title" -> if (inNews && newsTitle.isBlank()) newsTitle = decode(parser.nextText().trim())
                    "news_item_url" -> if (inNews && newsUrl.isBlank()) newsUrl = parser.nextText().trim()
                    "news_item_source" -> if (inNews && newsSource.isBlank()) newsSource = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> when (name) {
                    "news_item" -> inNews = false
                    "item" -> {
                        inItem = false
                        if (title.isNotBlank()) out += TrendItem(title, traffic, date, picture, newsTitle, newsUrl, newsSource)
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun decode(s: String) = s.replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&")

    private fun parseRssDate(s: String): Long = try {
        ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0
    }

    // ------------------------------------------------------------------ http

    private fun post(url: String, json: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = 15_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("X-YouTube-Client-Name", "1")
            c.setRequestProperty("X-YouTube-Client-Version", WEB_VERSION)
            c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1")
            c.outputStream.use { it.write(json.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1")
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
