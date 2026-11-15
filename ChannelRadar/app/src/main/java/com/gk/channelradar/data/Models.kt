package com.gk.channelradar.data

import kotlinx.serialization.Serializable

@Serializable
enum class VideoFormat { ANY, SHORTS, LONG }

@Serializable
data class Video(
    val id: String,
    val channelId: String,
    val channelTitle: String,
    val title: String,
    val publishedAt: Long,
    val thumb: String,
    val views: Long,
    val likes: Long,
    val comments: Long,
    val durationSec: Int,
    val categoryId: String = "",
    val foundBy: String = "",
    val niche: String = "",
) {
    val isShort: Boolean get() = durationSec in 1..180
    val url: String get() = if (isShort) "https://www.youtube.com/shorts/$id" else "https://www.youtube.com/watch?v=$id"
}

@Serializable
data class Channel(
    val id: String,
    val title: String,
    val thumb: String,
    val subs: Long,
    val hiddenSubs: Boolean,
    val totalViews: Long,
    val videoCount: Long,
    val createdAt: Long,
    val uploads: String,
    val country: String = "",
    val description: String = "",
) {
    val url: String get() = "https://www.youtube.com/channel/$id"
}

@Serializable
data class ScoredVideo(
    val video: Video,
    val ageHours: Double,
    val viewsPerHour: Double,
    /** views divided by subscriber count */
    val ratio: Double,
    /** (likes + 2*comments) / views */
    val engagement: Double,
    /** views divided by the channel's normal (median) views; null if not analysed */
    val outlier: Double? = null,
    val score: Double,
)

@Serializable
data class ChannelResult(
    val channel: Channel,
    val hits: List<ScoredVideo>,
    val recentUploads: List<Video> = emptyList(),
    val baselineMedian: Long? = null,
    /** share of recent uploads that got more views than the channel has subscribers */
    val consistency: Double? = null,
    val score: Double,
    val tags: List<String> = emptyList(),
    val niches: List<String> = emptyList(),
    val isNew: Boolean = false,
) {
    val best: ScoredVideo get() = hits.maxBy { it.score }
}

@Serializable
data class WordCount(val word: String, val count: Int)

@Serializable
data class ScanConfig(
    val niches: List<String> = emptyList(), // empty = all niches
    val customKeywords: String = "",
    val keywordsPerScan: Int = 6,
    val pagesPerKeyword: Int = 1,
    val includeTrending: Boolean = true,
    val days: Int = 7,
    val minSubs: Long = 0,
    val maxSubs: Long = 50_000,
    val minViews: Long = 5_000,
    val minRatio: Double = 1.0,
    val format: VideoFormat = VideoFormat.ANY,
    val deep: Boolean = true,
    val region: String = "IN",
    val language: String = "hi",
)

@Serializable
data class ScanResult(
    val time: Long,
    val config: ScanConfig,
    val channels: List<ChannelResult>,
    val videosScanned: Int,
    val channelsChecked: Int,
    val quotaUsed: Int,
    val keywordsUsed: List<String>,
    val warnings: List<String>,
    val trendingWords: List<WordCount>,
)

@Serializable
data class Snapshot(val time: Long, val subs: Long, val views: Long, val videos: Long)

@Serializable
data class WatchItem(
    val channel: Channel,
    val addedAt: Long,
    val snapshots: List<Snapshot>,
)

@Serializable
data class Settings(
    val apiKeys: List<String> = emptyList(),
)

@Serializable
data class QuotaState(
    val day: String = "",
    val used: Map<String, Int> = emptyMap(),
    val exhausted: Set<String> = emptySet(),
)
