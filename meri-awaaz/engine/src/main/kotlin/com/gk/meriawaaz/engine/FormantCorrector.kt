package com.gk.meriawaaz.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln

/**
 * Undoes the formant shift of a resampling pitch change.
 *
 * Resampling by [ratio] moves the whole spectrum, so vowels sound "bigger" (ratio < 1) or
 * "smaller" (ratio > 1) as well as lower or higher. This streaming filter measures the
 * spectral envelope of each frame (cepstral smoothing) and re-shapes the frame so that the
 * envelope sits where it was before the pitch change; the harmonics keep their new pitch.
 */
class FormantCorrector(private val ratio: Float, private val sink: (FloatArray, Int) -> Unit) {
    private val fft = Fft(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
    private val input = FloatArray(N)
    private val ola = FloatArray(N)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val cre = FloatArray(N)
    private val cim = FloatArray(N)
    private val env = FloatArray(BINS)
    private val out = FloatArray(HOP)
    private var fill = N - HOP // samples already in `input` (it starts with silence)
    private var skip = N - HOP // overlap-add latency
    private var received = 0L
    private var written = 0L

    fun process(buf: FloatArray, n: Int) {
        var p = 0
        received += n
        while (p < n) {
            val m = minOf(n - p, N - fill)
            System.arraycopy(buf, p, input, fill, m)
            fill += m
            p += m
            if (fill == N) frame()
        }
    }

    fun finish() {
        while (written < received) {
            java.util.Arrays.fill(input, fill, N, 0f)
            fill = N
            frame()
        }
    }

    private fun frame() {
        for (i in 0 until N) {
            re[i] = input[i] * window[i]; im[i] = 0f
        }
        fft.transform(re, im, false)

        // Envelope = low-quefrency part of the log spectrum.
        for (k in 0 until BINS) {
            val v = 0.5f * ln(re[k] * re[k] + im[k] * im[k] + 1e-12f)
            cre[k] = v
            if (k in 1 until N / 2) cre[N - k] = v
        }
        java.util.Arrays.fill(cim, 0f)
        fft.transform(cre, cim, true)
        for (q in 0 until N) {
            val d = minOf(q, N - q)
            if (d > LIFTER) cre[q] = 0f else if (d == LIFTER) cre[q] *= 0.5f
            cim[q] = 0f
        }
        fft.transform(cre, cim, false)
        for (k in 0 until BINS) env[k] = cre[k]

        for (k in 0 until BINS) {
            // The envelope that belongs at bin k now sits at bin k * ratio.
            val pos = k * ratio
            val g = if (pos >= BINS - 1) 1f else {
                val i = pos.toInt()
                val wanted = env[i] + (env[i + 1] - env[i]) * (pos - i)
                exp((wanted - env[k]).coerceIn(-MAX_LOG_GAIN, MAX_LOG_GAIN))
            }
            re[k] *= g; im[k] *= g
            if (k in 1 until N / 2) {
                re[N - k] *= g; im[N - k] *= g
            }
        }
        fft.transform(re, im, true)
        for (i in 0 until N) ola[i] += re[i] * window[i] / 1.5f

        var off = 0
        var count = HOP
        if (skip > 0) {
            val d = minOf(skip, HOP)
            skip -= d; off = d; count -= d
        }
        count = minOf(count.toLong(), received - written).toInt()
        if (count > 0) {
            System.arraycopy(ola, off, out, 0, count)
            sink(out, count)
            written += count
        }
        System.arraycopy(ola, HOP, ola, 0, N - HOP)
        java.util.Arrays.fill(ola, N - HOP, N, 0f)
        System.arraycopy(input, HOP, input, 0, N - HOP)
        fill = N - HOP
    }

    private companion object {
        const val N = 1024
        const val HOP = 256
        const val BINS = N / 2 + 1
        const val LIFTER = 36 // samples of quefrency kept: below the pitch period of any voice
        const val MAX_LOG_GAIN = 1.73f // 15 dB
    }
}
