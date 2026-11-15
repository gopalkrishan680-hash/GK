package com.gk.viralradar.data

data class Video(
    val id: String,
    val title: String,
    val channelId: String,
    val channelTitle: String,
    val publishedAt: Long,
    val thumbnail: String,
    val views: Long,
    val likes: Long,
    val comments: Long,
    val durationSec: Long,
    val tags: List<String>,
    val categoryId: String,
    val description: String,
    val subs: Long? = null,
) {
    val isShort: Boolean get() = durationSec in 1..180
    val url: String get() = if (isShort) "https://youtube.com/shorts/$id" else "https://youtu.be/$id"
}

data class Snapshot(val t: Long, val views: Long, val likes: Long, val comments: Long)

data class TrackedVideo(
    val id: String,
    val title: String,
    val channelTitle: String,
    val thumbnail: String,
    val publishedAt: Long,
    val subs: Long?,
    val addedAt: Long,
    val snapshots: List<Snapshot>,
)

enum class FormatFilter(val label: String) { ALL("All"), SHORTS("Shorts"), LONG("Long videos") }

data class Scored(val video: Video, val score: ScoreResult)

val REGIONS = listOf(
    "IN" to "India", "US" to "USA", "GB" to "UK", "PK" to "Pakistan", "BD" to "Bangladesh",
    "NP" to "Nepal", "AE" to "UAE", "SA" to "Saudi Arabia", "CA" to "Canada", "AU" to "Australia",
    "ID" to "Indonesia", "PH" to "Philippines", "BR" to "Brazil", "MX" to "Mexico", "DE" to "Germany",
    "FR" to "France", "JP" to "Japan", "KR" to "South Korea", "NG" to "Nigeria", "RU" to "Russia",
)
