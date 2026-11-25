package com.gk.smartcompressor.core

import android.net.Uri

enum class FileKind(val emoji: String, val label: String) {
    VIDEO("🎬", "Video"),
    IMAGE("🖼️", "Photo"),
    PDF("📄", "PDF"),
    AUDIO("🎵", "Audio"),
    OTHER("📦", "File");
}

/** Compression strength. */
enum class Level(val label: String, val hint: String) {
    HIGH_QUALITY("Best Quality", "Quality almost same, ~60-80% chhota"),
    BALANCED("Balanced", "Recommended – ~75-90% chhota"),
    MAX_SAVING("Max Saving", "Sabse chhota – ~85-95%, thodi quality kam"),
}

enum class VideoRes(val label: String, val shortSide: Int?) {
    AUTO("Auto", null), P1080("1080p", 1080), P720("720p", 720), P480("480p", 480), P360("360p", 360)
}

enum class ImageFormat(val label: String) { JPEG("JPG"), WEBP("WebP") }

enum class PdfMode(val label: String) {
    SMART("Smart (text same rahega)"),
    SCAN("Scan mode (sabse chhota)")
}

data class Options(
    val level: Level = Level.BALANCED,
    /** null = level ke hisab se; warna kitna % chhota karna hai (e.g. 90) */
    val targetReduction: Int? = null,
    val videoRes: VideoRes = VideoRes.AUTO,
    val removeAudio: Boolean = false,
    val h264Compat: Boolean = false,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
    val pdfMode: PdfMode = PdfMode.SMART,
)

sealed interface JobState {
    data object Queued : JobState
    data class Running(val progress: Float, val note: String = "") : JobState
    data class Done(val outUri: Uri, val outName: String, val outSize: Long, val mime: String) : JobState
    data class AlreadySmall(val bestSize: Long) : JobState
    data class Failed(val message: String) : JobState
    data object Cancelled : JobState
}

data class Job(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val kind: FileKind,
    val mime: String,
    val options: Options,
    val state: JobState = JobState.Queued,
)

data class HistoryItem(
    val name: String,
    val kind: FileKind,
    val origSize: Long,
    val newSize: Long,
    val outUri: String,
    val mime: String,
    val time: Long,
)
