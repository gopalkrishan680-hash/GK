@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gk.smartcompressor.compress

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.gk.smartcompressor.core.Job
import com.gk.smartcompressor.core.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio (MP3/WAV/FLAC/M4A…) ko AAC .m4a me convert karke chhota karta hai. */
object AudioCompressor : Compressor {
    override suspend fun compress(ctx: Context, job: Job, workDir: File, progress: Progress): CompressResult {
        val o = job.options
        var bitrate = when (o.level) {
            Level.HIGH_QUALITY -> 160_000
            Level.BALANCED -> 112_000
            Level.MAX_SAVING -> 64_000
        }
        val target = job.targetBytes()
        val durMs = runCatching {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(ctx, job.uri)
            val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            r.release(); d
        }.getOrNull() ?: 0L
        if (target != null && durMs > 0) bitrate = (target * 8.0 * 1000 / durMs * 0.97).toInt().coerceIn(32_000, 256_000)
        val mono = bitrate <= 64_000

        val processors = if (mono) {
            listOf(ChannelMixingAudioProcessor().apply {
                putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1))
                putChannelMixingMatrix(ChannelMixingMatrix.create(2, 1))
            })
        } else emptyList()
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(job.uri))
            .setRemoveVideo(true)
            .setEffects(Effects(processors, emptyList()))
            .build()
        val out = File(workDir, "out_${job.id}.m4a").also { it.delete() }
        val note = "AAC • ${bitrate / 1000} kbps${if (mono) " • mono" else ""}"

        withContext(Dispatchers.Main) {
            val enc = DefaultEncoderFactory.Builder(ctx)
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrate).build())
                .setEnableFallback(true).build()
            suspendCancellableCoroutine<Unit> { cont ->
                val t = Transformer.Builder(ctx)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(enc)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(RuntimeException("Audio encode fail: ${exportException.errorCodeName}", exportException))
                        }
                    }).build()
                t.start(edited, out.absolutePath)
                val holder = ProgressHolder()
                val poller = CoroutineScope(Dispatchers.Main).launch {
                    while (cont.isActive) {
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f, note)
                        delay(400)
                    }
                }
                cont.invokeOnCancellation {
                    poller.cancel()
                    CoroutineScope(Dispatchers.Main).launch { t.cancel() }
                }
            }
        }
        return CompressResult(out, "m4a", "audio/mp4")
    }
}
