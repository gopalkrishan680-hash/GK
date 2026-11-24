package com.gk.facelessstudio.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cuts a storyboard grid image into its panels, in reading order.
 * Rows are found first (full-width divider lines); then every row gets its OWN column dividers, because AI
 * image tools sometimes draw e.g. 3 panels on top and 2 wide ones below. When no divider is visible
 * (seamless grid) it falls back to an equal split with the expected rows/columns.
 */
object GridCutter {

    fun cut(src: Bitmap, cols: Int, rows: Int): List<Bitmap> {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // rows: horizontal dividers across the full width
        val rowScore = FloatArray(h) { y -> score(px, w, y, 0, w, horizontal = true) }
        val rowBands = bands(rowScore, h, rows)

        val out = ArrayList<Bitmap>()
        for (r in 0 until rowBands.size - 1) {
            val y0 = rowBands[r].second; val y1 = rowBands[r + 1].first
            if (y1 - y0 < h * 0.08f) continue
            // columns inside this row only
            val colScore = FloatArray(w) { x -> score(px, w, x, y0, y1, horizontal = false) }
            val colBands = bands(colScore, w, cols)
            for (c in 0 until colBands.size - 1) {
                val x0 = colBands[c].second; val x1 = colBands[c + 1].first
                if (x1 - x0 < w * 0.06f) continue
                // small inset removes anti-aliased line remains
                val ix = ((x1 - x0) * 0.012f).toInt(); val iy = ((y1 - y0) * 0.012f).toInt()
                val l = (x0 + ix).coerceIn(0, w - 2); val t = (y0 + iy).coerceIn(0, h - 2)
                val rw = (x1 - x0 - 2 * ix).coerceIn(1, w - l); val rh = (y1 - y0 - 2 * iy).coerceIn(1, h - t)
                out += Bitmap.createBitmap(src, l, t, rw, rh)
            }
        }
        return out
    }

    /**
     * How much one line (a row of pixels when [horizontal], else a column limited to [from, to)) looks like a divider:
     * nearly uniform and very bright or very dark.
     */
    private fun score(px: IntArray, w: Int, pos: Int, from: Int, to: Int, horizontal: Boolean): Float {
        val n = to - from
        if (n <= 0) return 0f
        val step = max(1, n / 300)
        var sum = 0f; var sumSq = 0f; var cnt = 0
        var i = from
        while (i < to) {
            val c = if (horizontal) px[pos * w + i] else px[i * w + pos]
            val l = Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f
            sum += l; sumSq += l * l; cnt++
            i += step
        }
        val mean = sum / cnt
        val sd = kotlin.math.sqrt(max(0f, sumSq / cnt - mean * mean))
        val extreme = max(mean - 215f, 40f - mean).coerceAtLeast(0f) / 40f   // >0 only for near white / near black
        val uniform = (1f - sd / 18f).coerceIn(0f, 1f)
        return min(1f, extreme) * uniform
    }

    /**
     * Returns boundaries as (lineStart, lineEnd) pairs, starting with the outer edge and ending with the far edge;
     * panel k spans [b[k].second, b[k+1].first). Dividers are detected anywhere (any count); if none are visible,
     * the line is split into [expected] equal parts.
     */
    private fun bands(score: FloatArray, len: Int, expected: Int): List<Pair<Int, Int>> {
        // outer frame (up to 5% on each side)
        var a = 0
        while (a < len * 0.05f && score[a] > 0.6f) a++
        var b = len
        while (b > len * 0.95f && score[b - 1] > 0.6f) b--

        val found = ArrayList<Pair<Int, Int>>()
        var i = (a + len * 0.06f).toInt()
        val end = (b - len * 0.06f).toInt()
        while (i < end) {
            if (score[i] > 0.6f) {
                var j = i
                while (j < len - 1 && score[j + 1] > 0.5f) j++
                var s0 = i
                while (s0 > 0 && score[s0 - 1] > 0.5f) s0--
                // a wide "line" is plain background (e.g. white doodle paper), not a divider
                if (j - s0 <= len * 0.03f) found += Pair(s0, j + 1)
                i = j + 1
            } else i++
        }
        var merged = mergeClose(found, len)
        if (merged.size > expected - 1) {
            // more "lines" than panels allow: something inside a picture looked like a divider.
            // Keep, for each expected position, the nearest one.
            val keep = (1 until expected).map { k ->
                val e = a + (b - a) * k / expected
                merged.minByOrNull { abs((it.first + it.second) / 2 - e) }!!
            }.distinct().sortedBy { it.first }
            merged = keep
        }
        val inner = if (merged.isNotEmpty()) merged
        else (1 until expected).map { k -> val e = a + (b - a) * k / expected; Pair(e, e) }   // seamless grid
        return listOf(Pair(0, a)) + inner + listOf(Pair(b, len))
    }

    /** Joins dividers found a few pixels apart (double lines, shadows). */
    private fun mergeClose(list: List<Pair<Int, Int>>, len: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (p in list) {
            val last = out.lastOrNull()
            if (last != null && p.first - last.second < len * 0.02f) out[out.lastIndex] = Pair(last.first, p.second) else out += p
        }
        return out
    }
}
