package com.gk.meriawaaz.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming windowed-sinc sample-rate converter for mono audio.
 * Feed blocks with [process], then call [finish] once; resampled blocks go to the sink.
 */
class StreamResampler(inRate: Int, outRate: Int, private val sink: (FloatArray, Int) -> Unit) {
    private val passthrough = inRate == outRate
    private val step = inRate.toDouble() / outRate
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.96
    private val half = ceil(ZEROS / cutoff).toInt()
    private val table = FloatArray(half * RES + 2)

    private var buf = FloatArray(1 shl 15)
    private var bufStart = 0L // absolute input index of buf[0]
    private var bufLen = 0
    private var received = 0L
    private var nextOut = 0L
    private val outBuf = FloatArray(8192)
    private var outLen = 0

    init {
        for (i in table.indices) {
            val x = i.toDouble() / RES
            val u = min(1.0, x / half)
            val window = 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)
            val a = PI * cutoff * x
            val sinc = if (a < 1e-9) 1.0 else sin(a) / a
            table[i] = (cutoff * sinc * window).toFloat()
        }
    }

    fun process(input: FloatArray, n: Int) {
        if (n <= 0) return
        if (passthrough) {
            sink(input, n)
            return
        }
        if (bufLen + n > buf.size) {
            // Drop inputs no future output can reach, then grow if still needed.
            val keepFrom = floor(nextOut * step).toLong() - half + 1
            val drop = (keepFrom - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
            if (drop > 0) {
                System.arraycopy(buf, drop, buf, 0, bufLen - drop)
                bufStart += drop
                bufLen -= drop
            }
            if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        }
        System.arraycopy(input, 0, buf, bufLen, n)
        bufLen += n
        received += n
        emit(false)
    }

    fun finish() {
        if (!passthrough) emit(true)
        flush()
    }

    private fun emit(end: Boolean) {
        while (true) {
            val center = nextOut * step
            val i0 = floor(center).toLong()
            if (end) {
                if (center >= received) break
            } else if (i0 + half >= received) break
            val frac = center - i0
            var acc = 0f
            val lo = maxOf(i0 - half + 1, 0L, bufStart)
            val hi = min(i0 + half, received - 1)
            var j = lo
            while (j <= hi) {
                val x = Math.abs((j - i0) - frac) * RES
                val k = x.toInt()
                val t = (x - k).toFloat()
                acc += buf[(j - bufStart).toInt()] * (table[k] + (table[k + 1] - table[k]) * t)
                j++
            }
            outBuf[outLen++] = acc
            if (outLen == outBuf.size) flush()
            nextOut++
        }
    }

    private fun flush() {
        if (outLen > 0) sink(outBuf, outLen)
        outLen = 0
    }

    private companion object {
        const val ZEROS = 16
        const val RES = 512
    }
}
