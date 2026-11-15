package com.gk.channelradar.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

class ApiException(val code: Int, val reason: String, message: String) : Exception(message)
class QuotaExhaustedException(message: String) : Exception(message)

/**
 * Thin client for the official YouTube Data API v3 (free daily quota from Google).
 * Supports several API keys: when one key's daily quota is used up it moves to the next.
 */
class YouTubeApi(private val store: Store) {

    private val base = "https://www.googleapis.com/youtube/v3/"
    private val quotaReasons = setOf("quotaExceeded", "dailyLimitExceeded")

    // ------------------------------------------------------------------ public calls

    /** search.list – costs 100 units. Returns video ids sorted by views + next page token. */
    fun searchVideos(
        query: String,
        publishedAfter: Long,
        region: String,
        language: String,
        format: VideoFormat,
        pageToken: String?,
    ): Pair<List<String>, String?> {
        val res = call(
            "search", mapOf(
                "part" to "snippet",
                "type" to "video",
                "order" to "viewCount",
                "maxResults" to "50",
                "q" to query,
                "publishedAfter" to Instant.ofEpochMilli(publishedAfter).truncatedTo(ChronoUnit.SECONDS).toString(),
                "regionCode" to region,
                "relevanceLanguage" to language,
                "videoDuration" to if (format == VideoFormat.SHORTS) "short" else null,
                "pageToken" to pageToken,
            ), cost = 100
        )
        val items = res.optJSONArray("items")
        val ids = mutableListOf<String>()
        if (items != null) for (i in 0 until items.length()) {
            items.getJSONObject(i).optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let(ids::add)
        }
        return ids to res.optString("nextPageToken").ifBlank { null }
    }

    /** videos.list chart=mostPopular – costs 1 unit per page of 50. */
    fun trending(region: String, categoryId: String?, pageToken: String?): Pair<List<Video>, String?> {
        val res = call(
            "videos", mapOf(
                "part" to "snippet,statistics,contentDetails",
                "chart" to "mostPopular",
                "regionCode" to region,
                "videoCategoryId" to categoryId,
                "maxResults" to "50",
                "pageToken" to pageToken,
            ), cost = 1
        )
        return parseVideos(res) to res.optString("nextPageToken").ifBlank { null }
    }

    /** videos.list by id – 1 unit per 50 ids. */
    fun videos(ids: Collection<String>): List<Video> {
        val out = mutableListOf<Video>()
        ids.distinct().chunked(50).forEach { chunk ->
            val res = call(
                "videos", mapOf(
                    "part" to "snippet,statistics,contentDetails",
                    "id" to chunk.joinToString(","),
                    "maxResults" to "50",
                ), cost = 1
            )
            out += parseVideos(res)
        }
        return out
    }

    /** channels.list – 1 unit per 50 ids. */
    fun channels(ids: Collection<String>): List<Channel> {
        val out = mutableListOf<Channel>()
        ids.distinct().chunked(50).forEach { chunk ->
            val res = call(
                "channels", mapOf(
                    "part" to "snippet,statistics,contentDetails",
                    "id" to chunk.joinToString(","),
                    "maxResults" to "50",
                ), cost = 1
            )
            val items = res.optJSONArray("items") ?: return@forEach
            for (i in 0 until items.length()) out += parseChannel(items.getJSONObject(i))
        }
        return out
    }

    /** playlistItems.list on the channel's uploads playlist – 1 unit. Latest uploads first. */
    fun uploadIds(uploadsPlaylist: String, max: Int): List<String> {
        if (uploadsPlaylist.isBlank()) return emptyList()
        val res = try {
            call(
                "playlistItems", mapOf(
                    "part" to "contentDetails",
                    "playlistId" to uploadsPlaylist,
                    "maxResults" to max.coerceIn(1, 50).toString(),
                ), cost = 1
            )
        } catch (e: ApiException) {
            if (e.code == 404) return emptyList() else throw e
        }
        val items = res.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull {
            items.getJSONObject(it).optJSONObject("contentDetails")?.optString("videoId")?.takeIf { id -> id.isNotBlank() }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun call(endpoint: String, params: Map<String, String?>, cost: Int): JSONObject {
        val keys = store.settings.apiKeys.map { it.trim() }.filter { it.isNotBlank() }
        if (keys.isEmpty()) throw ApiException(0, "noKey", "No API key found. Add your free YouTube Data API key in Settings.")
        val usable = keys.filter { !store.isExhausted(it) }
        if (usable.isEmpty()) throw QuotaExhaustedException(
            "Today's quota is used up on all keys. It resets daily at midnight Pacific time (about 1:30 PM IST), or add another key in Settings."
        )
        var last: Exception? = null
        for (key in usable) {
            try {
                val res = withRetry { http(endpoint, params, key) }
                store.addQuota(key, cost)
                return res
            } catch (e: ApiException) {
                if (e.reason in quotaReasons) {
                    store.markExhausted(key)
                    last = e
                    continue
                }
                throw e
            }
        }
        throw QuotaExhaustedException(
            "Today's quota is used up (${last?.message ?: "quotaExceeded"}). It resets tomorrow, or add a new key in Settings."
        )
    }

    private fun <T> withRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                attempt++
                if (attempt >= 3) throw IOException("Network error: ${e.message}. Please check your internet connection.", e)
                Thread.sleep(800L * attempt)
            }
        }
    }

    private fun http(endpoint: String, params: Map<String, String?>, key: String): JSONObject {
        val query = params.filterValues { !it.isNullOrBlank() }
            .map { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            .joinToString("&")
        val url = URL("$base$endpoint?$query&key=${URLEncoder.encode(key, "UTF-8")}")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                var reason = "http$code"
                var msg = "YouTube API error $code"
                try {
                    val err = JSONObject(body).getJSONObject("error")
                    msg = err.optString("message", msg)
                    reason = err.optJSONArray("errors")?.optJSONObject(0)?.optString("reason") ?: reason
                } catch (_: Exception) {
                }
                if (msg.contains("API key not valid", true)) msg = "Invalid API key (${Store.keyLabel(key)}). Please enter a correct key in Settings."
                if (reason == "accessNotConfigured" || msg.contains("has not been used", true) || msg.contains("is disabled", true))
                    msg = "'YouTube Data API v3' is not enabled in your Google Cloud project. Follow the steps in Settings."
                throw ApiException(code, reason, msg)
            }
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ parsing

    private fun parseVideos(res: JSONObject): List<Video> {
        val items = res.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull {
            try { parseVideo(items.getJSONObject(it)) } catch (_: Exception) { null }
        }
    }

    private fun parseVideo(o: JSONObject): Video {
        val sn = o.getJSONObject("snippet")
        val st = o.optJSONObject("statistics")
        val cd = o.optJSONObject("contentDetails")
        return Video(
            id = o.getString("id"),
            channelId = sn.optString("channelId"),
            channelTitle = sn.optString("channelTitle"),
            title = sn.optString("title"),
            publishedAt = parseTime(sn.optString("publishedAt")),
            thumb = thumb(sn),
            views = st?.optString("viewCount")?.toLongOrNull() ?: 0,
            likes = st?.optString("likeCount")?.toLongOrNull() ?: 0,
            comments = st?.optString("commentCount")?.toLongOrNull() ?: 0,
            durationSec = parseDuration(cd?.optString("duration")),
            categoryId = sn.optString("categoryId"),
        )
    }

    private fun parseChannel(o: JSONObject): Channel {
        val sn = o.getJSONObject("snippet")
        val st = o.optJSONObject("statistics")
        val uploads = o.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads")
        return Channel(
            id = o.getString("id"),
            title = sn.optString("title"),
            thumb = thumb(sn),
            subs = st?.optString("subscriberCount")?.toLongOrNull() ?: 0,
            hiddenSubs = st?.optBoolean("hiddenSubscriberCount") ?: false,
            totalViews = st?.optString("viewCount")?.toLongOrNull() ?: 0,
            videoCount = st?.optString("videoCount")?.toLongOrNull() ?: 0,
            createdAt = parseTime(sn.optString("publishedAt")),
            uploads = uploads?.takeIf { it.isNotBlank() } ?: ("UU" + o.getString("id").removePrefix("UC")),
            country = sn.optString("country"),
            description = sn.optString("description").take(400),
        )
    }

    private fun thumb(sn: JSONObject): String {
        val t = sn.optJSONObject("thumbnails") ?: return ""
        for (k in listOf("medium", "high", "default")) {
            t.optJSONObject(k)?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    companion object {
        fun parseTime(s: String?): Long = try {
            if (s.isNullOrBlank()) 0 else Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            0
        }

        private val durRegex = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")

        fun parseDuration(s: String?): Int {
            if (s.isNullOrBlank()) return 0
            val m = durRegex.matchEntire(s) ?: return 0
            val (d, h, mi, se) = m.destructured
            return (d.toIntOrNull() ?: 0) * 86400 + (h.toIntOrNull() ?: 0) * 3600 +
                (mi.toIntOrNull() ?: 0) * 60 + (se.toIntOrNull() ?: 0)
        }
    }
}
