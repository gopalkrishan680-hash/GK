package com.gk.viralradar.data

import java.time.Instant
import java.time.ZoneId

data class InsightReport(
    val sampleSize: Int,
    val topKeywords: List<Pair<String, Int>>,
    val topTags: List<Pair<String, Int>>,
    val uploadHours: IntArray,          // 24 buckets, local time, weighted by score
    val bestHours: List<Int>,
    val shortsShare: Double,
    val avgEngagement: Double,
    val medianAgeHours: Double,
    val durationBuckets: List<Pair<String, Int>>,
    val titleAvgLen: Int,
    val pctNumbers: Double,
    val pctQuestion: Double,
    val pctEmoji: Double,
    val pctCaps: Double,
    val pctHashtag: Double,
    val topChannels: List<Pair<String, Int>>,
)

object Insights {
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "my", "me", "you",
        "your", "this", "that", "it", "at", "by", "from", "be", "i", "we", "our", "new", "official", "video",
        "full", "ka", "ki", "ke", "ko", "se", "hai", "me", "mein", "aur", "ye", "vs", "x", "ft", "feat", "|", "-",
        "&", "shorts", "short", "episode", "ep", "part", "song", "music", "2024", "2025", "2026",
    )
    private val emojiRegex = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]")

    fun build(items: List<Scored>): InsightReport {
        val vids = items.map { it.video }
        val n = vids.size.coerceAtLeast(1)
        val words = vids.flatMap { v ->
            v.title.lowercase().split(Regex("[^\\p{L}\\p{N}#]+")).filter { it.length > 2 && it !in STOP && !it.startsWith("#") }.distinct()
        }
        val tags = vids.flatMap { it.tags.map(String::lowercase).distinct() }
        val hours = IntArray(24)
        items.forEach {
            val h = Instant.ofEpochMilli(it.video.publishedAt).atZone(ZoneId.systemDefault()).hour
            hours[h] += 1 + it.score.score / 25
        }
        val buckets = listOf(
            "< 1 min" to vids.count { it.durationSec in 1..60 },
            "1-3 min" to vids.count { it.durationSec in 61..180 },
            "3-10 min" to vids.count { it.durationSec in 181..600 },
            "10-20 min" to vids.count { it.durationSec in 601..1200 },
            "20+ min" to vids.count { it.durationSec > 1200 },
        )
        val ages = items.map { it.score.ageHours }.sorted()
        return InsightReport(
            sampleSize = vids.size,
            topKeywords = words.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(25).map { it.key to it.value },
            topTags = tags.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(25).map { it.key to it.value },
            uploadHours = hours,
            bestHours = hours.withIndex().sortedByDescending { it.value }.take(3).map { it.index },
            shortsShare = vids.count { it.isShort }.toDouble() / n,
            avgEngagement = items.map { it.score.engagement }.average().takeIf { !it.isNaN() } ?: 0.0,
            medianAgeHours = if (ages.isEmpty()) 0.0 else ages[ages.size / 2],
            durationBuckets = buckets,
            titleAvgLen = vids.map { it.title.length }.average().takeIf { !it.isNaN() }?.toInt() ?: 0,
            pctNumbers = vids.count { it.title.any(Char::isDigit) }.toDouble() / n,
            pctQuestion = vids.count { '?' in it.title }.toDouble() / n,
            pctEmoji = vids.count { emojiRegex.containsMatchIn(it.title) }.toDouble() / n,
            pctCaps = vids.count { v -> v.title.split(" ").any { it.length >= 4 && it.all { c -> !c.isLetter() || c.isUpperCase() } && it.any(Char::isLetter) } }.toDouble() / n,
            pctHashtag = vids.count { '#' in it.title }.toDouble() / n,
            topChannels = vids.groupingBy { it.channelTitle }.eachCount().entries.sortedByDescending { it.value }.take(10).map { it.key to it.value },
        )
    }

    /** Scores a draft title against what is currently trending. */
    fun checkTitle(title: String, report: InsightReport?): Pair<Int, List<String>> {
        val t = title.trim()
        if (t.isEmpty()) return 0 to emptyList()
        var s = 50
        val tips = mutableListOf<String>()
        when {
            t.length in 30..70 -> { s += 12; tips += "✅ Length sahi hai (${t.length} chars)" }
            t.length < 30 -> { s -= 5; tips += "⚠️ Title chhota hai — 40-70 chars better perform karte hain" }
            else -> { s -= 8; tips += "⚠️ Title lamba hai — mobile pe cut ho jayega (70 se kam rakho)" }
        }
        if (t.any(Char::isDigit)) { s += 6; tips += "✅ Number hai — curiosity badhata hai" } else tips += "💡 Number add karo (jaise '5 tricks', '₹100')"
        if (emojiRegex.containsMatchIn(t)) { s += 4; tips += "✅ Emoji eye-catching hai" }
        val caps = t.split(" ").count { it.length >= 3 && it.all { c -> !c.isLetter() || c.isUpperCase() } && it.any(Char::isLetter) }
        if (caps in 1..2) { s += 6; tips += "✅ 1-2 CAPS words — emphasis achha hai" }
        else if (caps > 3) { s -= 6; tips += "⚠️ Bahut zyada CAPS — spammy lagta hai" }
        else tips += "💡 Ek power word CAPS me likho (jaise SHOCKING, FREE)"
        val power = listOf("secret", "shocking", "truth", "never", "finally", "real", "exposed", "how", "why", "best",
            "worst", "first", "last", "challenge", "vs", "sach", "pehli baar", "kaise", "kyun", "free", "hack", "mistake")
        val lc = t.lowercase()
        val hits = power.filter { lc.contains(it) }
        if (hits.isNotEmpty()) { s += 8; tips += "✅ Power words: ${hits.joinToString()}" } else tips += "💡 Curiosity word add karo: kaise / kyun / sach / secret"
        if ('?' in t || '!' in t) { s += 3 }
        report?.let { r ->
            val trendHits = r.topKeywords.take(25).map { it.first }.filter { lc.contains(it) }
            if (trendHits.isNotEmpty()) { s += 10; tips += "🔥 Trending keywords match: ${trendHits.joinToString()}" }
            else tips += "💡 Abhi trending keywords: ${r.topKeywords.take(6).joinToString { it.first }}"
        }
        return s.coerceIn(0, 100) to tips
    }
}
