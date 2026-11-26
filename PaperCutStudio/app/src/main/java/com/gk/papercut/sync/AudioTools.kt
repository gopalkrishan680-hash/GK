package com.gk.papercut.sync

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any audio file Android can read (mp3, m4a, wav, ogg, aac...) into PCM chunks. */
object AudioTools {

    class PcmFormat(val sampleRate: Int, val channels: Int, val float: Boolean)

    fun audioTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        error("No audio track in this file")
    }

    /**
     * Streams decoded PCM. [onFormat] is called once before the first chunk;
     * [onChunk] receives the raw PCM bytes (little-endian, interleaved).
     */
    fun decode(file: File, onFormat: (PcmFormat) -> Unit, onChunk: (ByteBuffer, Int) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        val track = audioTrack(ex)
        ex.selectTrack(track)
        val inFormat = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var formatSent = false
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !formatSent) {
                    onFormat(formatOf(codec.outputFormat)); formatSent = true
                } else if (out >= 0) {
                    if (!formatSent) { onFormat(formatOf(codec.outputFormat)); formatSent = true }
                    if (info.size > 0) {
                        val b = codec.getOutputBuffer(out)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        onChunk(b.slice().order(ByteOrder.LITTLE_ENDIAN), info.size)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
    }

    private fun formatOf(f: MediaFormat): PcmFormat {
        val enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        return PcmFormat(
            f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            enc == AudioFormat.ENCODING_PCM_FLOAT,
        )
    }

    /** Loudness envelope: one RMS value per [hopMs] window, plus total duration in seconds. */
    class Envelope(val rms: FloatArray, val hopSec: Double, val duration: Double)

    fun envelope(file: File, hopMs: Int = 20): Envelope {
        val values = ArrayList<Float>()
        var fmt: PcmFormat? = null
        var hop = 0
        var acc = 0.0
        var count = 0
        var totalFrames = 0L
        decode(file, { f -> fmt = f; hop = f.sampleRate * hopMs / 1000 }) { buf, size ->
            val f = fmt!!
            val bytesPerSample = if (f.float) 4 else 2
            val frames = size / (bytesPerSample * f.channels)
            for (i in 0 until frames) {
                var s = 0f
                for (c in 0 until f.channels) {
                    s += if (f.float) buf.getFloat() else buf.getShort() / 32768f
                }
                s /= f.channels
                acc += s * s; count++
                if (count >= hop) { values += kotlin.math.sqrt(acc / count).toFloat(); acc = 0.0; count = 0 }
            }
            totalFrames += frames
        }
        val sr = fmt?.sampleRate ?: 44100
        return Envelope(values.toFloatArray(), hopMs / 1000.0, totalFrames.toDouble() / sr)
    }
}
