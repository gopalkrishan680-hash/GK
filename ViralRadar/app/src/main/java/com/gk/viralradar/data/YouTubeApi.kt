package com.gk.viralradar.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class ApiException(message: String, val code: Int = 0) : Exception(message)

/** Thin client over the official YouTube Data API v3 (free key, 10,000 units/day). */
object YouTubeApi {
    private const val BASE = "https://www.googleapis.com/youtube/v3/"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(path: String, params: Map<String, String>, key: String): JSONObject {
        if (key.isBlank()) throw ApiException("API key missing. Settings tab me YouTube API key daalo.")
        val q = params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val conn = URL("$BASE$path?$q&key=${enc(key.trim())}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(friendlyError(code, body), code)
            JSONObject(body)
        } catch (e: java.io.IOException) {
            throw ApiException("Network error: internet check karo (${e.message})")
        } finally {
            conn.disconnect()
        }
    }

    private fun friendlyError(code: Int, body: String): String {
        val reason = runCatching {
            JSONObject(body).getJSONObject("error").getJSONArray("errors").getJSONObject(0).optString("reason")
        }.getOrDefault("")
        val msg = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrDefault("")
        return when (reason) {
            "quotaExceeded", "dailyLimitExceeded" -> "Aaj ka API quota khatam (10,000 units/day). Kal reset hoga, ya doosri key daalo."
            "keyInvalid" -> "API key galat hai. Settings me sahi key daalo."
            "accessNotConfigured" -> "Google Cloud me 'YouTube Data API v3' enable nahi hai. Enable karke dobara try karo."
            "chartNotFound", "videoChartNotFound" -> "Is region/category ke liye trending chart available nahi hai. Doosri category chuno."
            else -> "YouTube API error $code: ${msg.ifBlank { reason }}"
        }
    }

    private fun parseVideo(item: JSONObject): Video? {
        val sn = item.optJSONObject("snippet") ?: return null
        val st = item.optJSONObject("statistics") ?: JSONObject()
        val cd = item.optJSONObject("contentDetails") ?: JSONObject()
        val live = item.optJSONObject("liveStreamingDetails")
        val liveNow = sn.optString("liveBroadcastContent") == "live"
        // For streams, age should count from when the stream actually started.
        val start = live?.optString("actualStartTime")?.takeIf { it.isNotBlank() } ?: sn.optString("publishedAt")
        val th = sn.optJSONObject("thumbnails")
        val thumb = th?.let {
            (it.optJSONObject("high") ?: it.optJSONObject("medium") ?: it.optJSONObject("default"))?.optString("url")
        } ?: ""
        val tags = sn.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return Video(
            id = item.optString("id"),
            title = sn.optString("title"),
            channelId = sn.optString("channelId"),
            channelTitle = sn.optString("channelTitle"),
            publishedAt = runCatching { Instant.parse(start).toEpochMilli() }
                .getOrDefault(System.currentTimeMillis()),
            thumbnail = thumb,
            views = st.optString("viewCount", "0").toLongOrNull() ?: 0,
            likes = st.optString("likeCount", "0").toLongOrNull() ?: 0,
            comments = st.optString("commentCount", "0").toLongOrNull() ?: 0,
            durationSec = parseDuration(cd.optString("duration")),
            tags = tags,
            categoryId = sn.optString("categoryId"),
            description = sn.optString("description").take(500),
            liveNow = liveNow,
            wasLive = live != null,
            concurrentViewers = live?.optString("concurrentViewers")?.toLongOrNull(),
        )
    }

    fun parseDuration(iso: String): Long {
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(iso) ?: return 0
        val (d, h, mi, s) = m.destructured
        return (d.toLongOrNull() ?: 0) * 86400 + (h.toLongOrNull() ?: 0) * 3600 +
            (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
    }

    private fun items(o: JSONObject): List<JSONObject> =
        o.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    /** Most-popular chart = YouTube's own trending list. Cost: 1 unit per page. */
    fun trending(key: String, region: String, categoryId: String?, pages: Int = 2): List<Video> {
        val out = mutableListOf<Video>()
        var token: String? = null
        repeat(pages) {
            val p = mutableMapOf(
                "part" to "snippet,statistics,contentDetails,liveStreamingDetails", "chart" to "mostPopular",
                "regionCode" to region, "maxResults" to "50",
            )
            if (!categoryId.isNullOrBlank()) p["videoCategoryId"] = categoryId
            token?.let { p["pageToken"] = it }
            val r = get("videos", p, key)
            out += items(r).mapNotNull(::parseVideo)
            token = r.optString("nextPageToken").ifBlank { null }
            if (token == null) return withSubs(key, out)
        }
        return withSubs(key, out)
    }

    /** Cost: 1 unit per 50 ids. */
    fun videosByIds(key: String, ids: List<String>): List<Video> =
        ids.distinct().chunked(50).flatMap { chunk ->
            items(get("videos", mapOf("part" to "snippet,statistics,contentDetails,liveStreamingDetails", "id" to chunk.joinToString(",")), key))
                .mapNotNull(::parseVideo)
        }

    /**
     * YouTube search autocomplete = what people are actually typing right now (demand signal).
     * Public endpoint, no API key, no quota.
     */
    fun suggestions(query: String, region: String): List<String> {
        val conn = URL("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&ie=utf-8&oe=utf-8" +
            "&hl=en&gl=${enc(region)}&q=${enc(query)}").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 10000
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val cs = Regex("charset=([\\w-]+)").find(conn.contentType ?: "")?.groupValues?.get(1) ?: "UTF-8"
            val body = conn.inputStream.readBytes().toString(charset(cs))
            val arr = org.json.JSONArray(body).getJSONArray(1)
            (0 until arr.length()).map { arr.get(it).let { x -> if (x is org.json.JSONArray) x.getString(0) else x.toString() } }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /** Like [search] but also returns YouTube's estimate of how many matching videos exist (supply). */
    fun searchWithTotal(key: String, query: String, hoursBack: Int, region: String): Pair<List<Video>, Long> {
        val after = Instant.ofEpochMilli(System.currentTimeMillis() - hoursBack * 3_600_000L).toString()
        val r = get("search", mapOf(
            "part" to "id", "type" to "video", "q" to query, "order" to "viewCount",
            "publishedAfter" to after, "maxResults" to "50", "regionCode" to region,
        ), key)
        val total = r.optJSONObject("pageInfo")?.optLong("totalResults") ?: 0
        val ids = items(r).mapNotNull { it.optJSONObject("id")?.optString("videoId") }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyList<Video>() to total
        return withSubs(key, videosByIds(key, ids)) to total
    }

    /** Search recent uploads for a niche. Cost: 100 units + 1-2. */
    fun search(
        key: String, query: String, hoursBack: Int, region: String,
        order: String = "viewCount", duration: String? = null, categoryId: String? = null,
    ): List<Video> {
        val after = Instant.ofEpochMilli(System.currentTimeMillis() - hoursBack * 3_600_000L).toString()
        val p = mutableMapOf(
            "part" to "id", "type" to "video", "q" to query, "order" to order,
            "publishedAfter" to after, "maxResults" to "50", "regionCode" to region,
        ).apply { if (query.isNotBlank()) put("q", query) else remove("q") }
        duration?.let { p["videoDuration"] = it }
        categoryId?.let { p["videoCategoryId"] = it }
        val ids = items(get("search", p, key)).mapNotNull { it.optJSONObject("id")?.optString("videoId") }
            .filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyList()
        return withSubs(key, videosByIds(key, ids))
    }

    /** Cost: 1 unit per 50 channels. */
    fun channelSubs(key: String, channelIds: List<String>): Map<String, Long> =
        channelIds.distinct().filter { it.isNotBlank() }.chunked(50).flatMap { chunk ->
            items(get("channels", mapOf("part" to "statistics", "id" to chunk.joinToString(",")), key)).map {
                val st = it.optJSONObject("statistics")
                val hidden = st?.optBoolean("hiddenSubscriberCount") ?: true
                it.optString("id") to (if (hidden) -1L else st?.optString("subscriberCount")?.toLongOrNull() ?: -1L)
            }
        }.toMap()

    fun withSubs(key: String, videos: List<Video>): List<Video> {
        val subs = runCatching { channelSubs(key, videos.map { it.channelId }) }.getOrDefault(emptyMap())
        return videos.map { v -> v.copy(subs = subs[v.channelId]?.takeIf { it >= 0 }) }
    }

    fun categories(key: String, region: String): List<Pair<String, String>> =
        items(get("videoCategories", mapOf("part" to "snippet", "regionCode" to region, "hl" to "en"), key))
            .filter { it.optJSONObject("snippet")?.optBoolean("assignable") == true }
            .map { it.optString("id") to it.getJSONObject("snippet").optString("title") }

    /** Accepts any YouTube URL form or a bare 11-char id. */
    fun extractId(input: String): String? {
        val s = input.trim()
        Regex("""(?:v=|youtu\.be/|shorts/|live/|embed/)([A-Za-z0-9_-]{11})""").find(s)?.let { return it.groupValues[1] }
        return if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(s)) s else null
    }
}
