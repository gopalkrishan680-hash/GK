package com.gk.meriawaaz.engine

/**
 * Runs a length-preserving audio model over arbitrarily long audio in bounded memory.
 *
 * Each step feeds [core] new samples plus [ctx] samples of context on both sides, drops the
 * context from the result, and cross-fades [xf] samples either side of every seam.
 * Model inputs are zero-padded to a multiple of [hop] and to at least [minInput] samples.
 */
class Chunker(
    private val core: Int,
    private val ctx: Int,
    private val xf: Int,
    private val hop: Int,
    private val minInput: Int,
) {
    init {
        require(ctx >= 2 * xf && core >= 2 * xf && core > ctx)
    }

    /** Returns false if [cancelled] stopped the run early. */
    fun run(
        src: PcmSource,
        model: (FloatArray) -> FloatArray,
        sink: (FloatArray, Int, Int) -> Unit,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Boolean {
        val total = src.length
        var a = 0L
        var pending: FloatArray? = null // faded-out tail of the previous chunk: [a-xf, a+xf)
        while (a < total) {
            if (cancelled()) return false
            var b = minOf(total, a + core)
            if (total - b < ctx) b = total // fold a short remainder into this chunk
            val last = b == total
            val i0 = maxOf(0L, a - ctx)
            val i1 = minOf(total, b + ctx)
            val n = (i1 - i0).toInt()
            val input = FloatArray(maxOf(minInput, (n + hop - 1) / hop * hop))
            src.read(i0, input, 0, n)
            val out = model(input)
            check(out.size >= n) { "model returned ${out.size} samples for $n" }
            val off = (a - i0).toInt()
            val end = (b - i0).toInt()
            var s = off
            pending?.let { p ->
                for (j in 0 until 2 * xf) p[j] += out[off - xf + j] * ((j + 0.5f) / (2 * xf))
                sink(p, 0, 2 * xf)
                s = off + xf
            }
            if (last) {
                sink(out, s, end - s)
            } else {
                sink(out, s, end - xf - s)
                pending = FloatArray(2 * xf) { j -> out[end - xf + j] * (1f - (j + 0.5f) / (2 * xf)) }
            }
            a = b
            progress(a.toFloat() / total)
        }
        return true
    }
}
