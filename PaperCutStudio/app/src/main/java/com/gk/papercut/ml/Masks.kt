package com.gk.papercut.ml

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** A soft mask (0..1 per pixel) the size of the scene image. */
class Mask(val w: Int, val h: Int, val a: FloatArray = FloatArray(w * h)) {

    fun area(thr: Float = 0.5f) = a.count { it > thr }

    /** Bounding box (l, t, r, b) of pixels above [thr], or null if empty. */
    fun bounds(thr: Float = 0.5f): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) if (a[row + x] > thr) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    fun union(o: Mask) { for (i in a.indices) a[i] = max(a[i], o.a[i]) }

    fun iou(o: Mask, step: Int = 4): Float {
        var inter = 0; var uni = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val i = y * w + x
                val p = a[i] > 0.5f; val q = o.a[i] > 0.5f
                if (p && q) inter++
                if (p || q) uni++
                x += step
            }
            y += step
        }
        return if (uni == 0) 0f else inter.toFloat() / uni
    }

    /** Binary dilation by [r] pixels (separable square max filter). */
    fun dilated(r: Int): Mask {
        val bin = BooleanArray(w * h) { a[it] > 0.3f }
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            // Two passes: distance to the nearest set pixel on the left, then on the right.
            var run = -1_000_000
            for (x in 0 until w) { if (bin[y * w + x]) run = x; if (x - run <= r) tmp[y * w + x] = true }
            run = 1_000_000
            for (x in w - 1 downTo 0) { if (bin[y * w + x]) run = x; if (run - x <= r) tmp[y * w + x] = true }
        }
        val out = Mask(w, h)
        for (x in 0 until w) {
            var run = -1_000_000
            for (y in 0 until h) { if (tmp[y * w + x]) run = y; if (y - run <= r) out.a[y * w + x] = 1f }
            run = 1_000_000
            for (y in h - 1 downTo 0) { if (tmp[y * w + x]) run = y; if (run - y <= r) out.a[y * w + x] = 1f }
        }
        return out
    }

    companion object {
        /** Builds a mask from a cut-out bitmap's alpha placed at (ox, oy). */
        fun fromAlpha(w: Int, h: Int, cut: Bitmap, ox: Int, oy: Int): Mask {
            val m = Mask(w, h)
            val px = IntArray(cut.width * cut.height)
            cut.getPixels(px, 0, cut.width, 0, 0, cut.width, cut.height)
            for (y in 0 until cut.height) {
                val ty = y + oy
                if (ty !in 0 until h) continue
                for (x in 0 until cut.width) {
                    val tx = x + ox
                    if (tx !in 0 until w) continue
                    m.a[ty * w + tx] = Color.alpha(px[y * cut.width + x]) / 255f
                }
            }
            return m
        }
    }
}

/** A cut-out: transparent bitmap + its top-left position inside the scene image. */
class CutOut(val bitmap: Bitmap, val x: Int, val y: Int, val score: Float = 1f)

object Cutter {
    /** Cuts [src] with [mask]; returns null if the mask is too small. */
    fun cut(src: Bitmap, mask: Mask, minPixels: Int = 400): CutOut? {
        val bb = mask.bounds() ?: return null
        val pad = 2
        val l = max(0, bb[0] - pad); val t = max(0, bb[1] - pad)
        val r = min(src.width, bb[2] + pad); val b = min(src.height, bb[3] + pad)
        val cw = r - l; val ch = b - t
        if (cw * ch < minPixels) return null
        val px = IntArray(cw * ch)
        src.getPixels(px, 0, cw, l, t, cw, ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val m = mask.a[(y + t) * mask.w + (x + l)]
            // Slight contrast on the edge keeps paper edges crisp but anti-aliased.
            val alpha = ((m - 0.35f) / 0.3f).coerceIn(0f, 1f)
            val i = y * cw + x
            px[i] = (px[i] and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
        }
        return CutOut(Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888), l, t)
    }
}
