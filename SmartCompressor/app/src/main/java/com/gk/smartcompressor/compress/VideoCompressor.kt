@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gk.smartcompressor.compress

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.gk.smartcompressor.core.Job
import com.gk.smartcompressor.core.Level
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Video compression: H.265 (HEVC) hardware encoder + smart bitrate + resolution.
 * Phone camera ki videos (jo bahut zyada bitrate pe record hoti hain) 85-95% tak chhoti ho jaati hain
 * bina dikhne wale farq ke.
 */
object VideoCompressor : Compressor {

    private data class Meta(
        val width: Int, val height: Int, val durationMs: Long,
        val bitrate: Long, val fps: Float, val audioBitrate: Int, val hasAudio: Boolean,
    )

    private fun readMeta(ctx: Context, job: Job): Meta {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, job.uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            if (br <= 0 && dur > 0) br = job.size * 8000 / dur
            var fps = 30f
            var aBr = 128_000
            var hasAudio = false
            runCatching {
                val ex = MediaExtractor()
                ex.setDataSource(ctx, job.uri, null)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                            .getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                    }
                    if (mime.startsWith("audio/")) {
                        hasAudio = true
                        if (f.containsKey(MediaFormat.KEY_BIT_RATE)) aBr = f.getInteger(MediaFormat.KEY_BIT_RATE)
                    }
                }
                ex.release()
            }
            return Meta(w, h, dur, br, fps.coerceIn(10f, 60f), aBr, hasAudio)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun hasHevcEncoder(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, true) }
        }
    }.getOrDefault(false)

    override suspend fun compress(ctx: Context, job: Job, workDir: File, progress: Progress): CompressResult {
        progress(0f, "Video analyse ho raha hai…")
        val m = readMeta(ctx, job)
        val o = job.options
        val hevc = !o.h264Compat && hasHevcEncoder()
        val codecFactor = if (hevc) 1.0 else 1.5   // H.264 ko same quality ke liye ~50% zyada bitrate chahiye

        // Bits-per-pixel per level (HEVC). Ye values visually "same jaisi" quality deti hain.
        val bpp = when (o.level) {
            Level.HIGH_QUALITY -> 0.075
            Level.BALANCED -> 0.05
            Level.MAX_SAVING -> 0.035
        } * codecFactor
        val levelMaxShort = when (o.level) {
            Level.HIGH_QUALITY -> 1080
            Level.BALANCED -> 1080
            Level.MAX_SAVING -> 720
        }
        val srcShort = min(m.width, m.height)
        val srcLong = max(m.width, m.height)
        val fps = min(m.fps, 60f)
        fun pixelsFor(short: Int) = short.toDouble() * (srcLong.toDouble() * short / srcShort)

        val audioBps = if (o.removeAudio || !m.hasAudio) 0 else min(m.audioBitrate, 192_000)
        var shortSide: Int
        var videoBitrate: Int
        val target = job.targetBytes()

        if (target != null && m.durationMs > 0) {
            // Target size mode: bitrate size ke hisaab se nikalo, resolution khud adjust
            val total = target * 8.0 * 1000 / m.durationMs * 0.97 // 3% container overhead
            videoBitrate = max(120_000.0, total - audioBps).toInt()
            val cap = o.videoRes.shortSide ?: 1080
            val ladder = listOf(2160, 1440, 1080, 720, 540, 480, 360, 240).filter { it <= min(cap, srcShort) }
                .ifEmpty { listOf(srcShort) }
            shortSide = ladder.firstOrNull { videoBitrate / (pixelsFor(it) * fps) >= 0.03 * codecFactor } ?: ladder.last()
        } else {
            shortSide = min(srcShort, o.videoRes.shortSide ?: levelMaxShort)
            videoBitrate = (pixelsFor(shortSide) * fps * bpp).toInt()
            // Kabhi bhi original se zyada bitrate mat do – hamesha size kam ho
            if (m.bitrate > 0) videoBitrate = min(videoBitrate, ((m.bitrate - audioBps) * 0.6).toInt())
            videoBitrate = max(videoBitrate, 200_000)
        }
        shortSide -= shortSide % 2

        // Effects frames ko display orientation (rotation lagne ke baad) me dekhte hain
        val outHeight = if (m.height > m.width) (shortSide.toLong() * m.height / m.width).toInt().let { it - it % 2 } else shortSide
        val videoEffects = if (shortSide < srcShort) listOf(Presentation.createForHeight(outHeight)) else emptyList()
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(job.uri))
            .setRemoveAudio(o.removeAudio)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val out = File(workDir, "out_${job.id}.mp4")
        val note = "${if (hevc) "H.265" else "H.264"} • ${shortSide}p • ${videoBitrate / 1000} kbps"
        try {
            out.delete()
            export(ctx, edited, out, hevc, videoBitrate, toneMap = false, note, progress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // HDR / kuch phones ke encoder issues: H.264 + HDR→SDR ke saath dobara try
            out.delete()
            val br2 = if (hevc) (videoBitrate * 1.5).toInt() else videoBitrate
            export(ctx, edited, out, false, br2, toneMap = true, "Retry: H.264 • ${shortSide}p • ${br2 / 1000} kbps", progress)
        }
        return CompressResult(out, "mp4", "video/mp4")
    }

    private suspend fun export(
        ctx: Context, edited: EditedMediaItem, out: File, hevc: Boolean, bitrate: Int,
        toneMap: Boolean, note: String, progress: Progress,
    ) = withContext(Dispatchers.Main) {
        progress(0f, note)
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(edited))
            .apply { if (toneMap) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
            .build()
        suspendCancellableCoroutine<Unit> { cont ->
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(
                            RuntimeException("Video encode fail: ${exportException.errorCodeName}", exportException)
                        )
                    }
                })
                .build()
            transformer.start(composition, out.absolutePath)
            val holder = ProgressHolder()
            val poller = CoroutineScope(Dispatchers.Main).launch {
                while (cont.isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        progress(holder.progress / 100f, note)
                    }
                    delay(400)
                }
            }
            cont.invokeOnCancellation {
                poller.cancel()
                CoroutineScope(Dispatchers.Main).launch { transformer.cancel() }
            }
        }
    }
}
