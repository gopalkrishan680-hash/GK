package com.gk.meriawaaz.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * On-device voice conversion with the OpenVoice V2 tone-colour converter (ONNX).
 * All audio is mono at [MODEL_RATE].
 */
class VoiceEngine(encoderPath: String, converterPath: String, threads: Int) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val converter: OrtSession

    init {
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(threads)
            encoder = env.createSession(encoderPath, o)
            converter = env.createSession(converterPath, o)
        }
    }

    /**
     * Voice fingerprint ("tone colour") of a speaker: the mean encoder output over ~10 s
     * pieces of the first [maxSeconds] of speech, with silence removed.
     */
    fun embed(src: PcmSource, maxSeconds: Int = 120, progress: (Float) -> Unit = {}): FloatArray {
        val n = minOf(src.length, maxSeconds.toLong() * MODEL_RATE).toInt()
        val raw = FloatArray(n)
        src.read(0, raw, 0, n)
        var speech = removeSilence(raw)
        if (speech.size < MODEL_RATE) speech = raw
        require(speech.size >= MODEL_RATE / 2) { "Audio is too short" }

        val seg = 10 * MODEL_RATE
        val sum = FloatArray(EMBED_DIM)
        var count = 0
        var pos = 0
        while (pos < speech.size) {
            var end = minOf(speech.size, pos + seg)
            if (speech.size - end < 3 * MODEL_RATE) end = speech.size
            val g = encode(speech.copyOfRange(pos, end))
            for (i in sum.indices) sum[i] += g[i]
            count++
            pos = end
            progress(pos.toFloat() / speech.size)
        }
        for (i in sum.indices) sum[i] /= count
        return sum
    }

    /** Re-voices [src] from speaker [gSrc] to speaker [gTgt]. Returns false if cancelled. */
    fun convert(
        src: PcmSource,
        gSrc: FloatArray,
        gTgt: FloatArray,
        sink: (FloatArray, Int, Int) -> Unit,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Boolean = Chunker(CORE, CTX, XF, HOP, MIN_INPUT)
        .run(src, { convertChunk(it, gSrc, gTgt) }, sink, progress, cancelled)

    private fun encode(audio: FloatArray): FloatArray =
        tensor(audio, audio.size).use { t ->
            encoder.run(mapOf("audio" to t)).use { r -> floats(r) }
        }

    private fun convertChunk(audio: FloatArray, gSrc: FloatArray, gTgt: FloatArray): FloatArray =
        tensor(audio, audio.size).use { a ->
            tensor(gSrc, EMBED_DIM).use { s ->
                tensor(gTgt, EMBED_DIM).use { t ->
                    converter.run(mapOf("audio" to a, "g_src" to s, "g_tgt" to t)).use { r -> floats(r) }
                }
            }
        }

    private fun tensor(data: FloatArray, n: Int): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data, 0, n), longArrayOf(1, n.toLong()))

    private fun floats(result: OrtSession.Result): FloatArray {
        val fb = (result.get(0) as OnnxTensor).floatBuffer
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    override fun close() {
        encoder.close()
        converter.close()
    }

    companion object {
        const val EMBED_DIM = 256
        const val HOP = 256
        private const val CORE = 432 * HOP // ~5 s of new audio per step
        private const val CTX = 48 * HOP
        private const val XF = 512
        private const val MIN_INPUT = 32 * HOP

        /** Drops stretches quieter than about -26 dB below the loud parts of the recording. */
        fun removeSilence(x: FloatArray): FloatArray {
            val frame = 512
            val frames = x.size / frame
            if (frames < 8) return x
            val rms = FloatArray(frames) { f ->
                var e = 0.0
                for (i in f * frame until (f + 1) * frame) e += x[i] * x[i]
                sqrt(e / frame).toFloat()
            }
            val loud = rms.sortedArray()[(frames * 0.95f).toInt().coerceAtMost(frames - 1)]
            val threshold = maxOf(loud * 0.05f, 1e-4f)
            val keep = BooleanArray(frames)
            for (f in 0 until frames) {
                if (rms[f] >= threshold) {
                    for (k in maxOf(0, f - HANG)..minOf(frames - 1, f + HANG)) keep[k] = true
                }
            }
            val out = FloatArray(keep.count { it } * frame)
            var o = 0
            for (f in 0 until frames) {
                if (keep[f]) {
                    System.arraycopy(x, f * frame, out, o, frame)
                    o += frame
                }
            }
            return out
        }

        private const val HANG = 4

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in a.indices) {
                ab += a[i] * b[i]
                aa += a[i] * a[i]
                bb += b[i] * b[i]
            }
            return (ab / (sqrt(aa) * sqrt(bb) + 1e-12)).toFloat()
        }
    }
}
