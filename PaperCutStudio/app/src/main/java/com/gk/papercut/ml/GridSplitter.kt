package com.gk.papercut.ml

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cuts a grid image into its panels. AI images rarely have perfectly even panels, so instead of
 * slicing at exact fractions it looks for the real gutters (plain lines) near each expected cut,
 * trims leftover gutter from every panel and flags empty panels.
 */
object GridSplitter {

    class Cell(val bitmap: Bitmap?, val row: Int, val col: Int) {
        val empty get() = bitmap == null
    }

    fun split(src: Bitmap, cols: Int, rows: Int): List<Cell> {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val bg = PlainBackground.detect(src) ?: Color.WHITE
        val isBg = BooleanArray(w * h) { near(px[it], bg) }

        val colScore = FloatArray(w) { x -> var n = 0; var y = 0; var t = 0; while (y < h) { if (isBg[y * w + x]) n++; t++; y += 2 }; n / t.toFloat() }
        val rowScore = FloatArray(h) { y -> var n = 0; var x = 0; var t = 0; while (x < w) { if (isBg[y * w + x]) n++; t++; x += 2 }; n / t.toFloat() }
        val xs = bands(colScore, cols)
        val ys = bands(rowScore, rows)

        val out = ArrayList<Cell>()
        for (r in 0 until rows) for (c in 0 until cols) {
            var l = xs[c].first; var rt = xs[c].second; var t = ys[r].first; var b = ys[r].second
            // Trim gutter remains on each side (lines that are almost all background), max 4 %.
            val maxTrimX = (rt - l) * 0.04f; val maxTrimY = (b - t) * 0.04f
            fun lineBg(x0: Int, y0: Int, x1: Int, y1: Int): Float {
                var n = 0; var tot = 0
                for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) { tot++; if (isBg[y * w + x]) n++ }
                return if (tot == 0) 1f else n / tot.toFloat()
            }
            while (l < rt - 2 && l - xs[c].first < maxTrimX && lineBg(l, t, l + 1, b) > 0.985f) l++
            while (rt > l + 2 && xs[c].second - rt < maxTrimX && lineBg(rt - 1, t, rt, b) > 0.985f) rt--
            while (t < b - 2 && t - ys[r].first < maxTrimY && lineBg(l, t, rt, t + 1) > 0.985f) t++
            while (b > t + 2 && ys[r].second - b < maxTrimY && lineBg(l, b - 1, rt, b) > 0.985f) b--
            val cw = rt - l; val ch = b - t
            val empty = cw < 16 || ch < 16 || lineBg(l, t, rt, b) > 0.97f || flat(px, w, l, t, rt, b)
            out += Cell(if (empty) null else Bitmap.createBitmap(src, l, t, cw, ch), r, c)
        }
        return out
    }

    /** Start/end of each of [n] panels along one axis, cut at the strongest gutter near each expected line. */
    private fun bands(score: FloatArray, n: Int): List<Pair<Int, Int>> {
        val len = score.size
        if (n <= 1) return listOf(0 to len)
        // 5-px smoothing
        val s = FloatArray(len) { i -> var a = 0f; var k = 0; for (j in max(0, i - 2)..min(len - 1, i + 2)) { a += score[j]; k++ }; a / k }
        val cuts = (1 until n).map { k ->
            val e = k * len / n
            val win = len / (n * 3)
            var best = e; var bv = -1f
            for (x in max(1, e - win)..min(len - 2, e + win)) {
                // Prefer high gutter score, then closeness to the expected line.
                val v = s[x] - abs(x - e) / (len * 4f)
                if (v > bv) { bv = v; best = x }
            }
            var a = best; var b = best
            if (s[best] > 0.85f) {
                val lim = len / (n * 4)
                while (a > 0 && best - a < lim && s[a - 1] > 0.85f) a--
                while (b < len - 1 && b - best < lim && s[b + 1] > 0.85f) b++
            }
            a to b + 1
        }
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        for ((a, b) in cuts) { out += start to a; start = b }
        out += start to len
        return out
    }

    private fun near(a: Int, b: Int) =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255)) < 45

    /** Almost no detail at all (an "empty" panel with some tint). */
    private fun flat(px: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int): Boolean {
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in t until b step 4) for (x in l until r step 4) {
            val p = px[y * w + x]
            val v = ((p shr 16) and 255) * 0.3 + ((p shr 8) and 255) * 0.59 + (p and 255) * 0.11
            sum += v; sum2 += v * v; n++
        }
        if (n == 0) return true
        val mean = sum / n
        return sum2 / n - mean * mean < 25.0 // std-dev < 5 grey levels
    }
}
