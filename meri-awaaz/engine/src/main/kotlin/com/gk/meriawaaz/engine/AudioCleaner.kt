package com.gk.meriawaaz.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place radix-2 complex FFT. */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    init {
        require(n >= 2 && n and (n - 1) == 0)
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = if (inverse) sinT[k * step] else -sinT[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val s = 1f / n
            for (i in 0 until n) {
                re[i] *= s; im[i] *= s
            }
        }
    }
}

/** What [AudioCleaner] found out about a recording. */
class CleanReport(
    /** Seconds that actually contain speech. */
    val speechSeconds: Float,
    /** Loud parts versus background, in dB (higher is cleaner; capped at 60). */
    val snrDb: Float,
    /** Whether a background-noise profile was found and removed. */
    val denoised: Boolean,
)

/**
 * Cleans a speech recording in bounded memory: removes rumble below ~70 Hz, removes steady
 * background noise (hiss, fan, hum) by spectral subtraction using a noise profile learnt from
 * the quietest moments, and brings the loudness to a standard level with a soft limiter.
 */
object AudioCleaner {
    private const val N = 1024
    private const val HOP = 256
    private const val BINS = N / 2 + 1
    private const val TARGET_RMS = 0.12f
    private const val FLOOR = 0.1f // noise is turned down by at most 20 dB
    private const val OVERSUBTRACT = 3f
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }

    /** Returns null if [cancelled] stopped the run. */
    fun clean(
        src: PcmSource,
        sink: (FloatArray, Int, Int) -> Unit,
        denoise: Boolean = true,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): CleanReport? {
        val total = src.length
        val hops = (total / HOP).toInt()
        if (hops < 8) {
            val all = FloatArray(total.toInt()).also { src.read(0, it, 0, it.size) }
            sink(all, 0, all.size)
            return CleanReport(0f, 0f, false)
        }

        // Pass 1: speech-band loudness of every 256-sample block.
        val rms = FloatArray(hops)
        val block = FloatArray(HOP * 64)
        val hpA = (1.0 / (1.0 + 2 * PI * 100.0 / MODEL_RATE)).toFloat()
        var hpX = 0f
        var hp1 = 0f
        var hp2 = 0f
        var h = 0
        while (h < hops) {
            if (cancelled()) return null
            val n = minOf(64, hops - h)
            src.read(h.toLong() * HOP, block, 0, n * HOP)
            for (b in 0 until n) {
                var e = 0.0
                for (i in b * HOP until (b + 1) * HOP) {
                    // Two one-pole high-passes (~100 Hz), so rumble does not count as loudness.
                    val x = block[i]
                    val y1 = hpA * (hp1 + x - hpX)
                    hpX = x
                    val y2 = hpA * (hp2 + y1 - hp1)
                    hp1 = y1
                    hp2 = y2
                    e += y2 * y2
                }
                rms[h + b] = sqrt(e / HOP).toFloat()
            }
            h += n
            progress(0.2f * h / hops)
        }
        val sorted = rms.sortedArray()
        val loud = sorted[(hops * 0.95f).toInt().coerceAtMost(hops - 1)]
        val quiet = sorted[(hops * 0.10f).toInt()]
        // Speech = clearly above the background, whichever of the two measures is stricter.
        val speechLevel = maxOf(loud * 0.05f, minOf(quiet * 3f, loud * 0.3f), 1e-4f)
        val speechBlocks = rms.count { it >= speechLevel }
        val snr = (20 * log10(maxOf(loud, 1e-6f) / maxOf(quiet, 1e-6f))).coerceIn(0f, 60f)
        val report = CleanReport(speechBlocks.toFloat() * HOP / MODEL_RATE, snr, false)
        if (loud < 1e-4f) { // nothing but silence
            copy(src, sink)
            return report
        }

        val fft = Fft(N)
        val re = FloatArray(N)
        val im = FloatArray(N)

        // Pass 2: noise profile from the quietest blocks, if the recording has real pauses.
        var noise: FloatArray? = null
        if (denoise && quiet < loud * 0.1f && quiet > 1e-6f) {
            val limit = quiet * 1.5f
            val candidates = (0 until hops - 4).filter { t -> (0 until 4).all { rms[t + it] <= limit } }
            if (candidates.size >= 8) {
                val power = FloatArray(BINS)
                val stride = maxOf(1, candidates.size / 1500)
                var used = 0
                for (c in candidates.indices step stride) {
                    src.read(candidates[c].toLong() * HOP, re, 0, N)
                    for (i in 0 until N) {
                        re[i] *= window[i]; im[i] = 0f
                    }
                    fft.transform(re, im, false)
                    for (k in 0 until BINS) power[k] += re[k] * re[k] + im[k] * im[k]
                    used++
                }
                for (k in 0 until BINS) power[k] /= used
                noise = power
            }
        }

        // Pass 3: filter with overlap-add and write out at the standard loudness.
        val gainOut = (TARGET_RMS / loud).coerceIn(0.25f, 8f)
        val input = FloatArray(N)
        val ola = FloatArray(N)
        val gain = FloatArray(BINS) { 1f }
        val raw = FloatArray(BINS)
        val outBlock = FloatArray(HOP)
        val cutBin = (70f * N / MODEL_RATE).toInt() // bins below ~70 Hz are rumble
        var readPos = 0L
        var written = 0L
        var skip = N - HOP // latency of the overlap-add
        while (written < total) {
            if (cancelled()) return null
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
                var g = 1f
                if (noise != null) {
                    val p = re[k] * re[k] + im[k] * im[k]
                    g = if (p > 1e-20f) maxOf(FLOOR, 1f - OVERSUBTRACT * noise[k] / p) else FLOOR
                }
                raw[k] = g
            }
            for (k in 0 until BINS) {
                // Smooth across frequency and let gains fall slowly: avoids "musical" noise.
                val a = raw[maxOf(0, k - 1)]
                val c = raw[minOf(BINS - 1, k + 1)]
                var g = maxOf((a + raw[k] + c) / 3f, gain[k] * 0.7f)
                gain[k] = g
                if (k <= cutBin) g = 0f else if (k == cutBin + 1) g *= 0.5f
                re[k] *= g; im[k] *= g
                if (k in 1 until N / 2) {
                    re[N - k] *= g; im[N - k] *= g
                }
            }
            fft.transform(re, im, true)
            for (i in 0 until N) ola[i] += re[i] * window[i] * (gainOut / 1.5f)

            var off = 0
            var count = HOP
            if (skip > 0) {
                val d = minOf(skip, HOP)
                skip -= d; off = d; count -= d
            }
            count = minOf(count.toLong(), total - written).toInt()
            if (count > 0) {
                for (i in 0 until count) outBlock[i] = softLimit(ola[off + i])
                sink(outBlock, 0, count)
                written += count
            }
            System.arraycopy(ola, HOP, ola, 0, N - HOP)
            java.util.Arrays.fill(ola, N - HOP, N, 0f)
            if (written and 0xFFFF < HOP) progress(0.2f + 0.8f * written / total)
        }
        return CleanReport(report.speechSeconds, report.snrDb, noise != null)
    }

    /** Leaves normal levels untouched and bends peaks above 0.8 smoothly towards 1. */
    fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * kotlin.math.tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -y else y
    }

    private fun copy(src: PcmSource, sink: (FloatArray, Int, Int) -> Unit) {
        val buf = FloatArray(8192)
        var pos = 0L
        while (pos < src.length) {
            val n = minOf(buf.size.toLong(), src.length - pos).toInt()
            src.read(pos, buf, 0, n)
            sink(buf, 0, n)
            pos += n
        }
    }
}
