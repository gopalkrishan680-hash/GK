package com.gk.viralradar.data

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class ScoreResult(
    val score: Int,
    val label: String,
    val ageHours: Double,
    val viewsPerHour: Double,
    val engagement: Double,
    val outlier: Double?,
    val velocityPart: Double,
    val engagementPart: Double,
    val outlierPart: Double,
    val freshnessPart: Double,
    val predicted24h: Long,
    val predicted7d: Long,
    val reasons: List<String>,
)

/**
 * Viral Score (0-100). YouTube's real ranking system is private, so this uses the public
 * signals it is known to reward: view velocity, engagement, performance vs channel size
 * (outlier) and freshness. Projections are statistical estimates, not guarantees.
 */
object ViralScore {
    private fun clamp(x: Double, lo: Double = 0.0, hi: Double = 1.0) = x.coerceIn(lo, hi)

    fun score(v: Video, now: Long = System.currentTimeMillis(), recentVph: Double? = null): ScoreResult {
        val ageH = max(0.25, (now - v.publishedAt) / 3_600_000.0)
        val vph = recentVph ?: (v.views / ageH)
        val eng = if (v.views > 0) (v.likes + 2.0 * v.comments) / v.views else 0.0
        val outlier = v.subs?.let { v.views.toDouble() / max(it, 100L) }

        // Shorts naturally collect views ~3x faster, so normalise them.
        val normVph = if (v.isShort) vph / 3 else vph
        val vS = clamp((log10(normVph + 1) - 1) / 4.3)          // 10 vph -> 0, 200k vph -> 1
        val eS = clamp(eng / 0.08)                               // 8%+ engagement -> max
        val oS = outlier?.let { clamp((log10(it) + 1) / (log10(20.0) + 1)) } ?: 0.45 // 0.1x -> 0, 20x -> 1
        val fS = clamp(1 - ageH / 168)                           // fresh within a week

        val total = (100 * (0.40 * vS + 0.20 * eS + 0.25 * oS + 0.15 * fS)).roundToInt().coerceIn(0, 100)

        // Momentum projection: young videos keep accelerating, old ones decay.
        val momentum = clamp(1.3 - ageH / 72, 0.15, 1.3)
        val day1 = vph * 24 * momentum
        val r = clamp(0.5 + 0.35 * eS + 0.1 * vS, 0.45, 0.9)
        val p24 = v.views + day1
        val p7 = v.views + day1 * (1 - r.pow(7)) / (1 - r)

        val reasons = buildList {
            if (vS > 0.7) add("Bahut tez growth: ${Fmt.count(vph.toLong())} views/hour")
            else if (vS > 0.45) add("Achhi speed: ${Fmt.count(vph.toLong())} views/hour")
            else add("Slow speed: ${Fmt.count(vph.toLong())} views/hour")
            outlier?.let {
                when {
                    it >= 5 -> add("Breakout: channel ke subscribers se ${"%.1f".format(it)}x zyada views")
                    it >= 1 -> add("Channel size se better perform: ${"%.1f".format(it)}x")
                    else -> add("Channel average ke neeche (${"%.2f".format(it)}x subs)")
                }
            }
            if (eS > 0.6) add("High engagement ${"%.1f".format(eng * 100)}% (likes+comments)")
            else if (eS < 0.2) add("Low engagement ${"%.1f".format(eng * 100)}%")
            if (ageH < 24) add("Fresh: sirf ${Fmt.age(ageH)} purani")
            if (v.isShort) add("Short format (algorithm Shorts feed me push karta hai)")
        }

        return ScoreResult(
            score = total, label = label(total), ageHours = ageH, viewsPerHour = vph, engagement = eng,
            outlier = outlier, velocityPart = vS, engagementPart = eS, outlierPart = oS, freshnessPart = fS,
            predicted24h = p24.toLong(), predicted7d = p7.toLong(), reasons = reasons,
        )
    }

    fun label(s: Int) = when {
        s >= 75 -> "🔥 VIRAL"
        s >= 58 -> "🚀 RISING"
        s >= 40 -> "📈 STEADY"
        else -> "😴 SLOW"
    }
}

object Fmt {
    fun count(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }

    fun age(hours: Double): String = when {
        hours < 1 -> "${(hours * 60).toInt()} min"
        hours < 48 -> "${hours.toInt()} ghante"
        else -> "${(hours / 24).toInt()} din"
    }

    fun duration(sec: Long): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
        else "%d:%02d".format(sec / 60, sec % 60)
}
