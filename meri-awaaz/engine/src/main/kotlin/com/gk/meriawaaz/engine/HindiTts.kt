package com.gk.meriawaaz.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs

/**
 * Offline Hindi text-to-speech: Meta's MMS-TTS (a VITS model that reads Devanagari characters
 * directly) run with ONNX Runtime. It speaks in its own neutral voice; [Studio.speak] then
 * re-voices the result.
 */
class HindiTts(modelPath: String, tokensPath: String, threads: Int) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokens = HashMap<Char, Long>()

    init {
        File(tokensPath).forEachLine { line ->
            val cut = line.lastIndexOf(' ')
            if (cut == 1) tokens[line[0]] = line.substring(cut + 1).trim().toLong()
        }
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(threads)
            session = env.createSession(modelPath, o)
        }
    }

    /**
     * Speaks Devanagari [text] at [speed] (1 = normal) and delivers audio at [MODEL_RATE].
     * Punctuation and line breaks become pauses. Returns false if cancelled.
     */
    fun speak(
        text: String,
        speed: Float,
        sink: (FloatArray, Int, Int) -> Unit,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Boolean {
        val resampler = StreamResampler(SAMPLE_RATE, MODEL_RATE) { b, n -> sink(b, 0, n) }
        val parts = segments(text)
        for ((index, part) in parts.withIndex()) {
            if (cancelled()) return false
            val audio = synthesize(part.text, speed)
            if (audio.isNotEmpty()) {
                resampler.process(audio, audio.size)
                val pause = FloatArray(part.pauseMs * SAMPLE_RATE / 1000)
                resampler.process(pause, pause.size)
            }
            progress((index + 1f) / parts.size)
        }
        resampler.finish()
        return true
    }

    /** One phrase at 16 kHz with the silence at both ends trimmed; empty if nothing is speakable. */
    fun synthesize(phrase: String, speed: Float): FloatArray {
        val ids = ArrayList<Long>()
        ids.add(BLANK)
        for (ch in phrase) {
            val id = tokens[ch] ?: tokens[ch.lowercaseChar()] ?: continue
            ids.add(id)
            ids.add(BLANK)
        }
        if (ids.size < 3) return FloatArray(0)
        val x = LongArray(ids.size) { ids[it] }
        fun scalar(v: Float) = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(v)), longArrayOf(1))
        OnnxTensor.createTensor(env, LongBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { tx ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(x.size.toLong())), longArrayOf(1)).use { len ->
                scalar(NOISE).use { noise ->
                    scalar(1f / speed.coerceIn(0.5f, 2f)).use { length ->
                        scalar(NOISE_W).use { noiseW ->
                            val feed = mapOf("x" to tx, "x_length" to len, "noise_scale" to noise,
                                "length_scale" to length, "noise_scale_w" to noiseW)
                            session.run(feed).use { r ->
                                val fb = (r.get(0) as OnnxTensor).floatBuffer
                                return trim(FloatArray(fb.remaining()).also { fb.get(it) })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() = session.close()

    class Segment(val text: String, val pauseMs: Int)

    companion object {
        const val SAMPLE_RATE = 16000
        private const val BLANK = 0L
        private const val NOISE = 0.667f
        private const val NOISE_W = 0.8f
        private const val MAX_CHARS = 180

        /** Cuts text into phrases at punctuation, each with the pause that follows it. */
        fun segments(text: String): List<Segment> {
            val out = ArrayList<Segment>()
            val current = StringBuilder()
            fun flush(pauseMs: Int) {
                val phrase = current.toString().trim()
                current.setLength(0)
                if (phrase.any { it.isLetterOrDigit() || it in 'ऀ'..'ॿ' }) out.add(Segment(phrase, pauseMs))
                else if (out.isNotEmpty() && pauseMs > out.last().pauseMs) out[out.size - 1] = Segment(out.last().text, pauseMs)
            }
            for (ch in text) {
                when (ch) {
                    '\n' -> flush(700)
                    '.', '?', '!', '।', '…' -> flush(420)
                    ',', ';', ':', '—', '–' -> flush(180)
                    else -> {
                        current.append(ch)
                        if (current.length >= MAX_CHARS && ch == ' ') flush(60)
                    }
                }
            }
            flush(420)
            return out
        }

        private fun trim(x: FloatArray): FloatArray {
            val limit = 0.01f
            var a = 0
            while (a < x.size && abs(x[a]) < limit) a++
            var b = x.size
            while (b > a && abs(x[b - 1]) < limit) b--
            // Keep a few milliseconds either side so consonants are not clipped.
            a = maxOf(0, a - 320)
            b = minOf(x.size, b + 480)
            return if (a >= b) FloatArray(0) else x.copyOfRange(a, b)
        }
    }
}
