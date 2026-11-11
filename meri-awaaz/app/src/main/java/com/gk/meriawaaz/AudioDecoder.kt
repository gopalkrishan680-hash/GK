package com.gk.meriawaaz

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.gk.meriawaaz.engine.MODEL_RATE
import com.gk.meriawaaz.engine.PcmWriter
import com.gk.meriawaaz.engine.StreamResampler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NoAudioException : Exception()

/** Decodes any audio (or the audio track of a video) Android can read into a raw 22.05 kHz mono file. */
object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    /** Returns the number of samples written. [maxSeconds] <= 0 means the whole file. */
    fun decode(
        context: Context,
        uri: Uri,
        out: File,
        maxSeconds: Int,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val writer = PcmWriter(out)
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw NoAudioException()
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw NoAudioException()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val limit = if (maxSeconds > 0) maxSeconds.toLong() * MODEL_RATE else Long.MAX_VALUE
            var durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (maxSeconds > 0) durationUs = minOf(durationUs, maxSeconds * 1_000_000L).coerceAtLeast(1L)

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = pcmEncoding(format)
            var resampler: StreamResampler? = null
            var mono = FloatArray(0)

            fun feed(buf: ByteBuffer, offset: Int, size: Int, timeUs: Long) {
                val r = resampler ?: StreamResampler(rate, MODEL_RATE) { b, n -> writer.write(b, 0, n) }
                    .also { resampler = it }
                val bytes = bytesPerSample(encoding)
                val frames = size / (bytes * channels)
                if (mono.size < frames) mono = FloatArray(frames)
                buf.order(ByteOrder.LITTLE_ENDIAN)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += sample(buf, offset + (f * channels + c) * bytes, encoding)
                    mono[f] = sum / channels
                }
                r.process(mono, frames)
                if (durationUs > 0) progress((timeUs.toFloat() / durationUs).coerceIn(0f, 1f))
            }

            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                // WAV and other PCM files: the extractor already hands out PCM.
                val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                val buf = ByteBuffer.allocate(maxOf(maxInput, 1 shl 18))
                while (!cancelled() && writer.samples < limit) {
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) break
                    feed(buf, 0, n, extractor.sampleTime)
                    extractor.advance()
                }
            } else {
                val c = MediaCodec.createDecoderByType(mime).also { codec = it }
                c.configure(format, null, null, 0)
                c.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && !cancelled() && writer.samples < limit) {
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = pcmEncoding(f)
                    } else if (o >= 0) {
                        if (info.size > 0) feed(c.getOutputBuffer(o)!!, info.offset, info.size, info.presentationTimeUs)
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            resampler?.finish()
            return writer.samples
        } finally {
            writer.close()
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun pcmEncoding(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING)
        else AudioFormat.ENCODING_PCM_16BIT

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT -> 4
        ENCODING_PCM_24BIT_PACKED -> 3
        else -> 2
    }

    private fun sample(b: ByteBuffer, i: Int, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ((b.get(i).toInt() and 0xff) - 128) / 128f
        AudioFormat.ENCODING_PCM_FLOAT -> b.getFloat(i)
        ENCODING_PCM_32BIT -> b.getInt(i) / 2147483648f
        ENCODING_PCM_24BIT_PACKED ->
            ((b.get(i + 2).toInt() shl 16) or ((b.get(i + 1).toInt() and 0xff) shl 8) or (b.get(i).toInt() and 0xff)) / 8388608f
        else -> b.getShort(i) / 32768f
    }

    // AudioFormat constants added in API 31; the values are stable.
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22
}
