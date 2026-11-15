package com.gk.viralradar.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class ApiException(message: String) : Exception(message)

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
            if (code !in 200..299) throw ApiException(friendlyError(code, body))
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
            publishedAt = runCatching { Instant.parse(sn.optString("publishedAt")).toEpochMilli() }
                .getOrDefault(System.currentTimeMillis()),
            thumbnail = thumb,
            views = st.optString("viewCount", "0").toLongOrNull() ?: 0,
            likes = st.optString("likeCount", "0").toLongOrNull() ?: 0,
            comments = st.optString("commentCount", "0").toLongOrNull() ?: 0,
            durationSec = parseDuration(cd.optString("duration")),
            tags = tags,
            categoryId = sn.optString("categoryId"),
            description = sn.optString("description").take(500),
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
                "part" to "snippet,statistics,contentDetails", "chart" to "mostPopular",
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
            items(get("videos", mapOf("part" to "snippet,statistics,contentDetails", "id" to chunk.joinToString(",")), key))
                .mapNotNull(::parseVideo)
        }

    /** Search recent uploads for a niche. Cost: 100 units + 1-2. */
    fun search(
        key: String, query: String, hoursBack: Int, region: String,
        order: String = "viewCount", duration: String? = null,
    ): List<Video> {
        val after = Instant.ofEpochMilli(System.currentTimeMillis() - hoursBack * 3_600_000L).toString()
        val p = mutableMapOf(
            "part" to "id", "type" to "video", "q" to query, "order" to order,
            "publishedAfter" to after, "maxResults" to "50", "regionCode" to region,
        )
        duration?.let { p["videoDuration"] = it }
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
