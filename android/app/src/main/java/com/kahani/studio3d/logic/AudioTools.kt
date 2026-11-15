package com.kahani.studio3d.logic

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object AudioTools {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2

    fun durationMs(path: String): Long = runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(path)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    }.getOrDefault(0L)

    /**
     * Decodes any Android-supported audio file (mp3, m4a, aac, wav, ogg, opus, flac...)
     * into a raw PCM file: 16-bit little-endian, stereo, 44.1 kHz.
     * Returns number of stereo frames written.
     */
    fun decodeToPcm(src: String, out: File, onProgress: (Float) -> Unit = {}): Long {
        val ex = MediaExtractor()
        ex.setDataSource(src)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { track = i; break }
        }
        require(track >= 0) { "Is file me audio track nahi mila" }
        ex.selectTrack(track)
        val inFormat = ex.getTrackFormat(track)
        val durUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var srcRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var srcCh = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        var resampler = Resampler(srcRate, SAMPLE_RATE)

        val os = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var frames = 0L
        val outBuf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

        try {
            while (!outputDone) {
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
                            if (durUs > 0) onProgress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(srcRate, SAMPLE_RATE)
                    }
                    oi >= 0 -> {
                        val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val bytesPerSample = if (pcmFloat) 4 else 2
                        val nFrames = info.size / (bytesPerSample * srcCh)
                        val l = FloatArray(nFrames); val r = FloatArray(nFrames)
                        for (i in 0 until nFrames) {
                            var left = 0f; var right = 0f
                            for (c in 0 until srcCh) {
                                val s = if (pcmFloat) b.float else b.short / 32768f
                                when {
                                    srcCh == 1 -> { left = s; right = s }
                                    c == 0 -> left = s
                                    c == 1 -> right = s
                                }
                            }
                            l[i] = left; r[i] = right
                        }
                        codec.releaseOutputBuffer(oi, false)
                        resampler.process(l, r) { ol, or ->
                            outBuf.clear()
                            for (i in ol.indices) {
                                if (outBuf.remaining() < 4) {
                                    os.write(outBuf.array(), 0, outBuf.position()); outBuf.clear()
                                }
                                outBuf.putShort(toShort(ol[i])); outBuf.putShort(toShort(or[i]))
                            }
                            os.write(outBuf.array(), 0, outBuf.position())
                            frames += ol.size
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            os.close()
            codec.stop(); codec.release(); ex.release()
        }
        onProgress(1f)
        return frames
    }

    private fun toShort(f: Float): Short = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    /** RMS envelope of a pcm file, one value per [windowMs]. */
    fun envelope(pcm: File, windowMs: Int = 20): FloatArray {
        val framesPerWin = SAMPLE_RATE * windowMs / 1000
        val total = (pcm.length() / 4).toInt()
        val res = FloatArray(total / framesPerWin + 1)
        DataInputStream(BufferedInputStream(FileInputStream(pcm), 1 shl 16)).use { ins ->
            val bytes = ByteArray(framesPerWin * 4)
            var w = 0
            while (true) {
                val n = readFully(ins, bytes)
                if (n <= 0) break
                val bb = ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                val cnt = n / 4
                for (i in 0 until cnt) {
                    val s = (bb.short + bb.short) / 65536.0
                    sum += s * s
                }
                if (w < res.size) res[w++] = sqrt(sum / cnt.coerceAtLeast(1)).toFloat()
                if (n < bytes.size) break
            }
        }
        return res
    }

    fun readFully(ins: java.io.InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val n = ins.read(b, off, b.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /**
     * Finds the middle (ms) of each pause (silence) in the voiceover.
     * Used to snap scene cuts onto natural breathing pauses.
     */
    fun findPauses(env: FloatArray, windowMs: Int = 20, minPauseMs: Int = 180): List<Long> {
        if (env.isEmpty()) return emptyList()
        val sorted = env.sorted()
        val noise = sorted[(sorted.size * 0.1).toInt()]
        val speech = sorted[(sorted.size * 0.7).toInt()]
        val thr = noise + (speech - noise) * 0.18f
        val pauses = ArrayList<Long>()
        var start = -1
        for (i in env.indices) {
            if (env[i] < thr) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if ((i - start) * windowMs >= minPauseMs) pauses += ((start + i) / 2L) * windowMs
                start = -1
            }
        }
        return pauses
    }
}

/** Streaming linear resampler for stereo float audio. */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0 // position relative to the previous chunk's last sample (index -1)
    private var lastL = 0f
    private var lastR = 0f
    private var hasLast = false

    fun process(l: FloatArray, r: FloatArray, sink: (FloatArray, FloatArray) -> Unit) {
        if (l.isEmpty()) return
        if (inRate == outRate) { sink(l, r); return }
        // Virtual array: [last, l0, l1, ...] ; index 0 == last
        val n = l.size + 1
        if (!hasLast) { lastL = l[0]; lastR = r[0]; hasLast = true }
        val outL = ArrayList<Float>((l.size / step).toInt() + 2)
        val outR = ArrayList<Float>((l.size / step).toInt() + 2)
        while (pos < n - 1) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            val a0 = if (i == 0) lastL else l[i - 1]
            val b0 = l[i]
            val a1 = if (i == 0) lastR else r[i - 1]
            val b1 = r[i]
            outL += a0 + (b0 - a0) * frac
            outR += a1 + (b1 - a1) * frac
            pos += step
        }
        pos -= (n - 1)
        lastL = l[l.size - 1]; lastR = r[r.size - 1]
        sink(outL.toFloatArray(), outR.toFloatArray())
    }
}
