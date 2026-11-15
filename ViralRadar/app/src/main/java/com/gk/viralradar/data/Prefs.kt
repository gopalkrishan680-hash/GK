package com.gk.viralradar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("viral_radar", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()
    var region: String
        get() = sp.getString("region", "IN") ?: "IN"
        set(v) = sp.edit().putString("region", v).apply()
    /** Background refresh interval in minutes (min 15, WorkManager limit). */
    var intervalMin: Int
        get() = sp.getInt("interval", 60)
        set(v) = sp.edit().putInt("interval", v).apply()
    var alertThreshold: Int
        get() = sp.getInt("alert_threshold", 70)
        set(v) = sp.edit().putInt("alert_threshold", v).apply()
    /** Comma separated niche keywords watched in the background for breakout videos. */
    var nicheKeywords: String
        get() = sp.getString("niche", "") ?: ""
        set(v) = sp.edit().putString("niche", v).apply()
    var nicheHoursBack: Int
        get() = sp.getInt("niche_hours", 24)
        set(v) = sp.edit().putInt("niche_hours", v).apply()
    var lastNicheCheck: Long
        get() = sp.getLong("last_niche", 0)
        set(v) = sp.edit().putLong("last_niche", v).apply()

    var alertedIds: Set<String>
        get() = sp.getStringSet("alerted", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("alerted", v.toList().takeLast(500).toSet()).apply()

    // ---- Watchlist ----
    fun loadWatchlist(): List<TrackedVideo> {
        val raw = sp.getString("watch", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val snaps = o.getJSONArray("s")
                TrackedVideo(
                    id = o.getString("id"), title = o.getString("title"), channelTitle = o.optString("ch"),
                    thumbnail = o.optString("th"), publishedAt = o.optLong("pub"),
                    subs = if (o.has("subs")) o.getLong("subs") else null, addedAt = o.optLong("added"),
                    snapshots = (0 until snaps.length()).map { j ->
                        val s = snaps.getJSONArray(j)
                        Snapshot(s.getLong(0), s.getLong(1), s.getLong(2), s.getLong(3))
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveWatchlist(list: List<TrackedVideo>) {
        val a = JSONArray()
        list.forEach { t ->
            val o = JSONObject()
                .put("id", t.id).put("title", t.title).put("ch", t.channelTitle).put("th", t.thumbnail)
                .put("pub", t.publishedAt).put("added", t.addedAt)
            t.subs?.let { o.put("subs", it) }
            val s = JSONArray()
            t.snapshots.takeLast(600).forEach { s.put(JSONArray().put(it.t).put(it.views).put(it.likes).put(it.comments)) }
            o.put("s", s)
            a.put(o)
        }
        sp.edit().putString("watch", a.toString()).apply()
    }
}
