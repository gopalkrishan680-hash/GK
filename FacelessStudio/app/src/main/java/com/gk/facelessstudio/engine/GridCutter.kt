package com.gk.facelessstudio.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cuts a storyboard grid image into its panels. Finds the real divider lines (white, black or none)
 * near the expected positions, trims outer borders, and returns panels in reading order.
 */
object GridCutter {

    fun cut(src: Bitmap, cols: Int, rows: Int): List<Bitmap> {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val colScore = FloatArray(w) { x -> lineScore(px, w, h, x, vertical = true) }
        val rowScore = FloatArray(h) { y -> lineScore(px, w, h, y, vertical = false) }

        val xs = bounds(colScore, w, cols)
        val ys = bounds(rowScore, h, rows)

        val out = ArrayList<Bitmap>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val x0 = xs[c].second; val x1 = xs[c + 1].first
            val y0 = ys[r].second; val y1 = ys[r + 1].first
            // small inset removes anti-aliased line remains
            val ix = ((x1 - x0) * 0.012f).toInt(); val iy = ((y1 - y0) * 0.012f).toInt()
            val l = (x0 + ix).coerceIn(0, w - 2); val t = (y0 + iy).coerceIn(0, h - 2)
            val rw = (x1 - x0 - 2 * ix).coerceIn(1, w - l); val rh = (y1 - y0 - 2 * iy).coerceIn(1, h - t)
            out += Bitmap.createBitmap(src, l, t, rw, rh)
        }
        return out
    }

    /**
     * How much a full column/row looks like a divider: nearly uniform and very bright or very dark.
     * Sampled every few pixels for speed.
     */
    private fun lineScore(px: IntArray, w: Int, h: Int, pos: Int, vertical: Boolean): Float {
        val n = if (vertical) h else w
        val step = max(1, n / 300)
        var sum = 0f; var sumSq = 0f; var cnt = 0
        var i = 0
        while (i < n) {
            val c = if (vertical) px[i * w + pos] else px[pos * w + i]
            val l = (Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f)
            sum += l; sumSq += l * l; cnt++
            i += step
        }
        val mean = sum / cnt
        val sd = kotlin.math.sqrt(max(0f, sumSq / cnt - mean * mean))
        val extreme = max(mean - 215f, 40f - mean).coerceAtLeast(0f) / 40f   // >0 only for near white / near black
        val uniform = (1f - sd / 18f).coerceIn(0f, 1f)
        return min(1f, extreme) * uniform
    }

    /** Returns cols+1 boundaries as (lineStart, lineEnd) pairs; panel k spans [b[k].second, b[k+1].first). */
    private fun bounds(score: FloatArray, len: Int, parts: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        // outer edge: skip a border/frame if present (up to 5%)
        var a = 0
        while (a < len * 0.05f && score[a] > 0.6f) a++
        out += Pair(0, a)
        for (k in 1 until parts) {
            val expected = len * k / parts
            val win = (len * 0.06f).toInt()
            var best = expected; var bestS = 0f
            for (x in max(1, expected - win) until min(len - 1, expected + win)) {
                val s = score[x] - abs(x - expected) / (win * 8f)
                if (s > bestS) { bestS = s; best = x }
            }
            if (bestS < 0.5f) { out += Pair(expected, expected); continue }   // seamless grid: equal split
            var s0 = best; var e0 = best
            while (s0 > 0 && score[s0 - 1] > 0.5f) s0--
            while (e0 < len - 1 && score[e0 + 1] > 0.5f) e0++
            // a wide "line" means plain background (e.g. white doodle paper), not a divider
            if (e0 - s0 > len * 0.03f) out += Pair(expected, expected) else out += Pair(s0, e0 + 1)
        }
        var b = len
        while (b > len * 0.95f && score[b - 1] > 0.6f) b--
        out += Pair(b, len)
        return out
    }
}
