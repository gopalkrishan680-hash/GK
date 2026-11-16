package com.gk.viralradar.data

import kotlin.math.log10
import kotlin.math.roundToInt

data class KeywordReport(
    val keyword: String,
    val score: Int,
    val label: String,
    val demand: Double,        // 0..1, higher = more people watching
    val competition: Double,   // 0..1, higher = big channels dominate
    val supply: Double,        // 0..1, higher = lots of videos already
    val smallWin: Double,      // share of top videos made by small channels
    val medianViews: Long,
    val avgViewsPerHour: Double,
    val medianSubs: Long,
    val bigChannelShare: Double,
    val totalVideos: Long,
    val inAutocomplete: Boolean,
    val bestSmall: Scored?,
    val top: List<Scored>,
    val verdict: List<String>,
    val hoursBack: Int,
    val updatedAt: Long,
    val totalFetched: Int = 0,
    val sources: Set<String> = emptySet(),
)

/**
 * Demand vs supply finder. For one keyword it looks at the most-viewed uploads of the chosen
 * window and asks: are people watching a lot (demand), how many videos exist (supply), and
 * are the winners big channels or can small channels also win (competition)?
 */
object Opportunity {
    private fun clamp(x: Double) = x.coerceIn(0.0, 1.0)

    private fun median(xs: List<Long>): Long = if (xs.isEmpty()) 0 else xs.sorted()[xs.size / 2]

    fun build(keyword: String, videos: List<Video>, total: Long, inAutocomplete: Boolean, hoursBack: Int): KeywordReport {
        val now = System.currentTimeMillis()
        val scored = videos.map { Scored(it, ViralScore.score(it, now)) }.sortedByDescending { it.video.views }
        val top = scored.take(20)
        val medViews = median(top.map { it.video.views })
        val subsKnown = top.mapNotNull { it.video.subs }
        val medSubs = median(subsKnown)
        val big = if (subsKnown.isEmpty()) 0.0 else subsKnown.count { it >= 1_000_000 }.toDouble() / subsKnown.size
        val small = top.filter { (it.video.subs ?: Long.MAX_VALUE) < 100_000 }
        val smallWin = if (top.isEmpty()) 0.0 else small.count { it.video.views >= 10_000 }.toDouble() / top.size
        val avgVph = top.map { it.score.viewsPerHour }.average().takeIf { !it.isNaN() } ?: 0.0

        // Demand: median views of top results (1K -> 0, 1M -> 1), bonus if people search it.
        val demand = clamp((log10(medViews + 1.0) - 3) / 3 + if (inAutocomplete) 0.1 else 0.0)
        // Competition: how big are the channels that win (1K subs -> 0, 10M -> 1) + big-channel share.
        val competition = clamp(0.7 * clamp((log10(medSubs + 1.0) - 3) / 4) + 0.3 * big)
        // Supply: number of videos uploaded in the window (100 -> 0, 1M -> 1). YouTube's count is approximate.
        val supply = clamp((log10(total + 1.0) - 2) / 4)

        val score = if (top.isEmpty()) 0 else
            (100 * (0.40 * demand + 0.25 * (1 - competition) + 0.15 * (1 - supply) + 0.20 * clamp(smallWin * 1.5)))
                .roundToInt().coerceIn(0, 100)

        val verdict = buildList {
            when {
                demand >= 0.6 -> add("✅ Demand HIGH — top videos ka median ${Fmt.count(medViews)} views")
                demand >= 0.35 -> add("➖ Demand medium — median ${Fmt.count(medViews)} views")
                else -> add("❌ Demand kam — median sirf ${Fmt.count(medViews)} views")
            }
            when {
                competition < 0.4 -> add("✅ Competition LOW — jeetne wale channels ka median sirf ${Fmt.count(medSubs)} subs")
                competition < 0.65 -> add("➖ Competition medium — median ${Fmt.count(medSubs)} subs")
                else -> add("❌ Bade channels ka kabza — median ${Fmt.count(medSubs)} subs, ${(big * 100).toInt()}% channels 1M+")
            }
            when {
                supply < 0.45 -> add("✅ Videos kam bane hain — ${windowLabel(hoursBack)} me approx ${Fmt.count(total)} uploads")
                supply < 0.7 -> add("➖ Videos theek-thaak — approx ${Fmt.count(total)} uploads")
                else -> add("❌ Bahut saare videos — approx ${Fmt.count(total)} uploads")
            }
            if (smallWin >= 0.3) add("💎 Top 20 me ${(smallWin * 20).roundToInt()} videos chhote channels (<100K) ke hain — naye creator ke liye mauka")
            if (inAutocomplete) add("🔎 Log ye YouTube search me type kar rahe hain")
            bestSmall(scored)?.let {
                add("💡 Example: \"${it.video.title.take(60)}\" — ${Fmt.count(it.video.subs ?: 0)} subs channel pe ${Fmt.count(it.video.views)} views")
            }
        }

        return KeywordReport(
            keyword, score, label(score), demand, competition, supply, smallWin, medViews, avgVph, medSubs, big,
            total, inAutocomplete, bestSmall(scored), top, verdict, hoursBack, now, videos.size,
        )
    }

    private fun bestSmall(s: List<Scored>) = s.filter { it.video.subs != null && it.video.subs < 100_000 }
        .maxByOrNull { it.score.outlier ?: 0.0 }

    fun label(s: Int) = when {
        s >= 70 -> "💎 GOLDEN"
        s >= 55 -> "✅ GOOD"
        s >= 40 -> "⚖️ MEDIUM"
        else -> "🔴 SATURATED"
    }

    fun windowLabel(h: Int) = when (h) { 168 -> "7 din"; 720 -> "30 din"; 2160 -> "90 din"; else -> "$h ghante" }
}
