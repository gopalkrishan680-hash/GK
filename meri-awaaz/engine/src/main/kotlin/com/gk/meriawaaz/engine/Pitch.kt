package com.gk.meriawaaz.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

object Pitch {
    private const val RATE = MODEL_RATE / 2 // analysis runs on 2x decimated audio
    private const val WIN = 512
    private const val MIN_LAG = RATE / 400
    private const val MAX_LAG = RATE / 60

    /**
     * Typical speaking pitch in Hz (median over voiced frames of the first [maxSeconds]),
     * or 0 if no voiced speech was found.
     */
    fun medianF0(src: PcmSource, maxSeconds: Int = 120): Float {
        val n = minOf(src.length, maxSeconds.toLong() * MODEL_RATE).toInt() and 1.inv()
        val full = FloatArray(n).also { src.read(0, it, 0, n) }
        val x = FloatArray(n / 2) { (full[2 * it] + full[2 * it + 1]) * 0.5f }
        val frame = WIN + MAX_LAG
        val pitches = ArrayList<Float>()
        val levels = ArrayList<Float>()
        var start = 0
        while (start + frame <= x.size) {
            var e0 = 0.0
            for (i in start until start + WIN) e0 += x[i] * x[i]
            if (e0 > 1e-7 * WIN) {
                var best = 0f
                var bestLag = 0
                val score = FloatArray(MAX_LAG + 1)
                // Running energy of the lagged window.
                var el = 0.0
                for (i in start + MIN_LAG until start + MIN_LAG + WIN) el += x[i] * x[i]
                for (lag in MIN_LAG..MAX_LAG) {
                    var c = 0.0
                    var i = start
                    while (i < start + WIN) {
                        c += x[i] * x[i + lag]
                        i++
                    }
                    val r = (c / (sqrt(e0 * el) + 1e-12)).toFloat()
                    score[lag] = r
                    if (r > best) {
                        best = r; bestLag = lag
                    }
                    val drop = x[start + lag]
                    val add = x[start + lag + WIN]
                    el += add * add - drop * drop
                }
                if (best >= 0.6f) {
                    // Prefer the shortest lag that is nearly as good: guards against octave-down errors.
                    var lag = MIN_LAG
                    while (lag < bestLag && !(score[lag] >= 0.9f * best && score[lag] >= score[lag - 1] && score[lag] >= score[lag + 1])) lag++
                    pitches.add(RATE.toFloat() / lag)
                    levels.add(sqrt(e0 / WIN).toFloat())
                }
            }
            start += WIN / 2
        }
        if (pitches.size < 5) return 0f
        val strong = levels.sorted()[(levels.size * 0.95f).toInt().coerceAtMost(levels.size - 1)] * 0.2f
        val kept = pitches.filterIndexed { i, _ -> levels[i] >= strong }.sorted()
        return if (kept.isEmpty()) 0f else kept[kept.size / 2]
    }

    /**
     * Raises or lowers the pitch of speech by [ratio] (2 = one octave up) without changing its
     * duration: WSOLA time-stretch followed by resampling. Returns false if cancelled.
     */
    fun shift(
        src: PcmSource,
        ratio: Float,
        sink: (FloatArray, Int, Int) -> Unit,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Boolean {
        val total = src.length
        var written = 0L
        val emit = { b: FloatArray, n: Int ->
            val m = minOf(n.toLong(), total - written).toInt()
            if (m > 0) sink(b, 0, m)
            written += m
        }
        val inRate = (MODEL_RATE * ratio).roundToInt()
        val resampler = StreamResampler(inRate, MODEL_RATE, emit)
        val stretch = inRate.toDouble() / MODEL_RATE
        if (!stretch(src, stretch, { b, n -> resampler.process(b, n) }, progress, cancelled)) return false
        resampler.finish()
        val pad = FloatArray(4096)
        while (written < total) emit(pad, pad.size)
        return true
    }

    private const val W = 1024
    private const val HS = W / 2
    private const val SEARCH = 224
    private val hann = FloatArray(W) { (0.5 - 0.5 * cos(2 * PI * it / W)).toFloat() }

    /** WSOLA: makes speech [factor] times longer at the same pitch. */
    fun stretch(
        src: PcmSource,
        factor: Double,
        sink: (FloatArray, Int) -> Unit,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Boolean {
        val total = src.length
        val outTotal = (total * factor).roundToLong()
        val ha = HS / factor
        val region = FloatArray(W + 2 * SEARCH)
        val natural = FloatArray(HS)
        val ola = FloatArray(W)
        var prev = 0L // input position of the previously used frame
        var k = 0L
        var emitted = 0L
        while (emitted < outTotal) {
            if (cancelled()) return false
            val ideal = (k * ha).roundToLong()
            readPadded(src, ideal - SEARCH, region, region.size)
            var best = SEARCH
            if (k > 0) {
                // Pick the offset whose start best continues what the previous frame led into.
                readPadded(src, prev + HS, natural, HS)
                var bestScore = -Float.MAX_VALUE
                var d = 0
                while (d <= 2 * SEARCH) {
                    var c = 0f
                    var i = 0
                    while (i < HS) {
                        c += region[d + i] * natural[i]
                        i += 2
                    }
                    if (c > bestScore) {
                        bestScore = c; best = d
                    }
                    d += 2
                }
            }
            prev = ideal - SEARCH + best
            for (i in 0 until W) ola[i] += region[best + i] * hann[i]
            val n = minOf(HS.toLong(), outTotal - emitted).toInt()
            sink(ola, n)
            emitted += n
            System.arraycopy(ola, HS, ola, 0, HS)
            java.util.Arrays.fill(ola, HS, W, 0f)
            k++
            if (k and 63L == 0L) progress(emitted.toFloat() / outTotal)
        }
        return true
    }

    private fun readPadded(src: PcmSource, pos: Long, dst: FloatArray, n: Int) {
        val from = maxOf(0L, pos)
        val to = minOf(src.length, pos + n)
        java.util.Arrays.fill(dst, 0, n, 0f)
        if (to > from) src.read(from, dst, (from - pos).toInt(), (to - from).toInt())
    }
}
