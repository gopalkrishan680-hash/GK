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
    val autoScan: Boolean = false,
)

@Serializable
data class QuotaState(
    val day: String = "",
    val used: Map<String, Int> = emptyMap(),
    val exhausted: Set<String> = emptySet(),
)

// ---------------------------------------------------------------------- trends / demand

@Serializable
data class SearchIdea(
    val phrase: String,
    /** relative demand 1..100 inside this exploration */
    val demand: Double,
    /** how many autocomplete lists contained it */
    val appearances: Int,
    val seed: String,
)

@Serializable
data class TrendItem(
    val title: String,
    val traffic: String,
    val time: Long,
    val picture: String,
    val newsTitle: String,
    val newsUrl: String,
    val newsSource: String,
)

@Serializable
data class KeywordReport(
    val keyword: String,
    val time: Long,
    val region: String,
    val videosChecked: Int,
    /** median views of the top recent videos for this keyword */
    val medianViews: Long,
    val topViews: Long,
    /** share of top videos uploaded in the last 7 days */
    val freshShare: Double,
    /** share of top videos that come from small channels (< 100K subs) */
    val smallChannelShare: Double,
    /** share of small-channel videos that beat their own subscriber count */
    val smallWinRate: Double,
    val opportunity: Double,
    val verdict: String,
    val examples: List<ScoredVideo> = emptyList(),
    val exampleChannels: Map<String, Long> = emptyMap(),
    // ---- search volume & competition (v1.2)
    /** estimated monthly searches on YouTube (model, not an official number) */
    val estSearches: Long = 0,
    /** 0..100 search popularity (autocomplete depth + views) */
    val searchIndex: Double = 0.0,
    /** autocomplete signal 0..100 */
    val autocompleteScore: Double = 0.0,
    /** letters typed before YouTube suggested it, -1 = not suggested */
    val prefixLength: Int = -1,
    /** total views of the top recent videos (≈ monthly attention on the topic) */
    val monthlyViews: Long = 0,
    /** approx number of videos uploaded on this keyword in the last 30 days (YouTube totalResults) */
    val recentUploads: Long = 0,
    /** 0..100 – how hard it is to rank */
    val competition: Double = 0.0,
    val bigChannelShare: Double = 0.0,
    val medianTopSubs: Long = 0,
    val shortsShare: Double = 0.0,
    val typicalLengthSec: Int = 0,
    val bestDay: String = "",
    val bestHour: Int = -1,
    val related: List<String> = emptyList(),
    val titleWords: List<String> = emptyList(),
)
