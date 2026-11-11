package com.gk.meriawaaz.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Long-term tone balance (how much bass, body, presence and brightness a voice recording
 * has) and a gentle equaliser that moves one recording's balance towards another's.
 */
object ToneBalance {
    private const val N = 1024
    private const val HOP = 256
    private const val BINS = N / 2 + 1
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }

    /** Band edges in Hz; roughly a third of an octave each. */
    val EDGES = floatArrayOf(
        80f, 150f, 250f, 400f, 600f, 850f, 1150f, 1500f, 1900f, 2400f,
        3000f, 3700f, 4600f, 5700f, 7000f, 8500f, 11025f,
    )
    val BANDS = EDGES.size - 1

    /** Level of each band in dB relative to the whole, over the speech of the first [maxSeconds]. */
    fun spectrum(src: PcmSource, maxSeconds: Int = 120): FloatArray {
        val n = minOf(src.length, maxSeconds.toLong() * MODEL_RATE).toInt()
        val x = FloatArray(n).also { src.read(0, it, 0, n) }
        val frames = if (n < N) 0 else (n - N) / N * 2 + 1 // half-overlapping frames
        val energy = FloatArray(frames) { f ->
            var e = 0f
            for (i in f * N / 2 until f * N / 2 + N) e += x[i] * x[i]
            e
        }
        val result = FloatArray(BANDS)
        if (frames == 0) return result
        val loud = energy.sortedArray()[(frames * 0.95f).toInt().coerceAtMost(frames - 1)]
        val fft = Fft(N)
        val re = FloatArray(N)
        val im = FloatArray(N)
        val power = DoubleArray(BINS)
        for (f in 0 until frames) {
            if (energy[f] < loud * 0.05f) continue
            for (i in 0 until N) {
                re[i] = x[f * N / 2 + i] * window[i]; im[i] = 0f
            }
            fft.transform(re, im, false)
            for (k in 0 until BINS) power[k] += (re[k] * re[k] + im[k] * im[k]).toDouble()
        }
        val band = DoubleArray(BANDS)
        for (k in 0 until BINS) {
            val hz = k.toFloat() * MODEL_RATE / N
            val b = (0 until BANDS).firstOrNull { hz >= EDGES[it] && hz < EDGES[it + 1] } ?: continue
            band[b] += power[k]
        }
        val total = band.sum() + 1e-20
        for (b in 0 until BANDS) result[b] = (10 * log10(band[b] / total + 1e-12)).toFloat()
        return result
    }

    /**
     * Equaliser curve (dB per band) that takes [actual] a fraction [strength] of the way to
     * [reference], never changing a band by more than [maxDb], and loudness-neutral overall.
     */
    fun gains(reference: FloatArray, actual: FloatArray, strength: Float, maxDb: Float): FloatArray {
        val g = FloatArray(BANDS) { ((reference[it] - actual[it]) * strength).coerceIn(-maxDb, maxDb) }
        // Keep the level of the speech as it is: remove the energy-weighted average change.
        var before = 0.0
        var after = 0.0
        for (b in 0 until BANDS) {
            val p = 10.0.pow(actual[b] / 10.0)
            before += p
            after += p * 10.0.pow(g[b] / 10.0)
        }
        val offset = (10 * log10(after / before)).toFloat()
        for (b in 0 until BANDS) g[b] -= offset
        return g
    }

    /** Filters [src] with the curve from [gains]. Returns the peak level of what it wrote. */
    fun apply(src: PcmSource, gainsDb: FloatArray, sink: (FloatArray, Int, Int) -> Unit): Float {
        // Per-bin gain: straight lines between band centres on a log-frequency axis.
        val centre = FloatArray(BANDS) { ln(kotlin.math.sqrt(EDGES[it] * EDGES[it + 1])) }
        val bin = FloatArray(BINS) { k ->
            val f = ln(maxOf(k.toFloat() * MODEL_RATE / N, 1f))
            val db = when {
                f <= centre[0] -> gainsDb[0]
                f >= centre[BANDS - 1] -> gainsDb[BANDS - 1]
                else -> {
                    val b = (0 until BANDS - 1).first { f < centre[it + 1] }
                    gainsDb[b] + (gainsDb[b + 1] - gainsDb[b]) * (f - centre[b]) / (centre[b + 1] - centre[b])
                }
            }
            10f.pow(db / 20f)
        }
        val total = src.length
        val fft = Fft(N)
        val re = FloatArray(N)
        val im = FloatArray(N)
        val input = FloatArray(N)
        val ola = FloatArray(N)
        var readPos = 0L
        var written = 0L
        var skip = N - HOP
        var peak = 0f
        while (written < total) {
            System.arraycopy(input, HOP, input, 0, N - HOP)
            val n = minOf(HOP.toLong(), total - readPos).coerceAtLeast(0).toInt()
            if (n > 0) src.read(readPos, input, N - HOP, n)
            java.util.Arrays.fill(input, N - HOP + n, N, 0f)
            readPos += HOP
            for (i in 0 until N) {
                re[i] = input[i] * window[i]; im[i] = 0f
            }
            fft.transform(re, im, false)
            for (k in 0 until BINS) {
                re[k] *= bin[k]; im[k] *= bin[k]
                if (k in 1 until N / 2) {
                    re[N - k] *= bin[k]; im[N - k] *= bin[k]
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
            count = minOf(count.toLong(), total - written).toInt()
            if (count > 0) {
                for (i in off until off + count) peak = maxOf(peak, abs(ola[i]))
                sink(ola, off, count)
                written += count
            }
            System.arraycopy(ola, HOP, ola, 0, N - HOP)
            java.util.Arrays.fill(ola, N - HOP, N, 0f)
        }
        return peak
    }
}
