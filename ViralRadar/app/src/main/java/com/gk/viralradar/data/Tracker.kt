package com.gk.viralradar.data

import kotlin.math.max

data class TrackStats(
    val currentViews: Long,
    val recentVph: Double?,
    val previousVph: Double?,
    val acceleration: Double?,   // +0.5 = 50% faster than previous interval
    val status: String,
    val predicted24h: Long?,
    val score: ScoreResult?,
)

object Tracker {
    fun stats(t: TrackedVideo, now: Long = System.currentTimeMillis()): TrackStats {
        val s = t.snapshots
        val last = s.lastOrNull()
        val recent = vphBetween(s, 1)
        val prev = vphBetween(s, 2)
        val accel = if (recent != null && prev != null && prev > 0) (recent - prev) / prev else null
        val status = when {
            accel == null -> if (s.size < 2) "⏳ Data collect ho raha hai" else "📊 Tracking"
            accel > 0.25 -> "🚀 Accelerating"
            accel < -0.25 -> "📉 Slowing down"
            else -> "➡️ Stable"
        }
        val score = last?.let {
            ViralScore.score(
                Video(t.id, t.title, "", t.channelTitle, t.publishedAt, t.thumbnail, it.views, it.likes,
                    it.comments, 0, emptyList(), "", "", t.subs),
                now, recent,
            )
        }
        val p24 = if (last != null && recent != null) {
            val factor = when {
                accel == null -> 0.8
                accel > 0 -> minOf(1.0 + accel * 0.5, 1.6)
                else -> max(0.3, 1.0 + accel)
            }
            (last.views + recent * 24 * factor).toLong()
        } else null
        return TrackStats(last?.views ?: 0, recent, prev, accel, status, p24, score)
    }

    /** Views/hour between snapshot n-from-end and the one before it. */
    private fun vphBetween(s: List<Snapshot>, fromEnd: Int): Double? {
        if (s.size < fromEnd + 1) return null
        val b = s[s.size - fromEnd]
        val a = s[s.size - fromEnd - 1]
        val hours = (b.t - a.t) / 3_600_000.0
        if (hours <= 0.01) return null
        return max(0.0, (b.views - a.views) / hours)
    }

    /** Fetch fresh stats for all tracked videos (1 API unit per 50 videos) and append snapshots. */
    fun refresh(prefs: Prefs): Pair<List<TrackedVideo>, List<String>> {
        val list = prefs.loadWatchlist()
        if (list.isEmpty()) return list to emptyList()
        val fresh = YouTubeApi.videosByIds(prefs.apiKey, list.map { it.id }).associateBy { it.id }
        val now = System.currentTimeMillis()
        val alerts = mutableListOf<String>()
        val updated = list.map { t ->
            val v = fresh[t.id] ?: return@map t
            val lastT = t.snapshots.lastOrNull()?.t ?: 0
            val snaps = if (now - lastT < 60_000) t.snapshots else t.snapshots + Snapshot(now, v.views, v.likes, v.comments)
            val nt = t.copy(title = v.title, snapshots = snaps)
            val st = stats(nt, now)
            if (st.acceleration != null && st.acceleration > 0.5 && (st.recentVph ?: 0.0) > 500) {
                alerts += "🚀 \"${t.title.take(60)}\" ki speed ${(st.acceleration * 100).toInt()}% badh gayi " +
                    "(${Fmt.count(st.recentVph!!.toLong())} views/hr)"
            }
            nt
        }
        prefs.saveWatchlist(updated)
        return updated to alerts
    }
}
