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

    /** Removes stray islands: keeps connected parts at least [minFrac] of the biggest part. */
    fun keepMainParts(minFrac: Float = 0.15f): Mask {
        val bits = BooleanArray(w * h) { a[it] > 0.5f }
        val (labels, sizes) = Blobs.label(bits, w, h)
        if (sizes.size <= 1) return this
        val biggest = sizes.max()
        val keep = BooleanArray(w * h) { labels[it] > 0 && sizes[labels[it] - 1] >= biggest * minFrac }
        // Keep the soft edge around kept parts (pixels within 2 px of a kept pixel).
        val out = Mask(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (a[i] <= 0f) continue
            var near = keep[i]
            if (!near) loop@ for (dy in -2..2) for (dx in -2..2) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && keep[yy * w + xx]) { near = true; break@loop }
            }
            if (near) out.a[i] = a[i]
        }
        return out
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

/**
 * Many AI "paper cut-out" images sit on a plain white (or flat) background. Detecting that gives a
 * perfect foreground map for free and a perfect background plate (just the colour).
 */
object PlainBackground {
    private const val TOL = 40

    private fun near(a: Int, b: Int) =
        kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
            kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
            kotlin.math.abs((a and 255) - (b and 255)) < TOL

    /** The background colour, or null if the border is not mostly one flat colour. */
    fun detect(bmp: Bitmap): Int? {
        val w = bmp.width; val h = bmp.height
        val samples = ArrayList<Int>()
        for (x in 0 until w step 4) { samples += bmp.getPixel(x, 0); samples += bmp.getPixel(x, h - 1) }
        for (y in 0 until h step 4) { samples += bmp.getPixel(0, y); samples += bmp.getPixel(w - 1, y) }
        fun med(shift: Int) = samples.map { (it shr shift) and 255 }.sorted()[samples.size / 2]
        val c = Color.rgb(med(16), med(8), med(0))
        if (samples.count { near(it, c) } < samples.size * 0.6f) return null
        // The border colour must also be a real backdrop (>= 12 % of the picture, connected to the edge).
        // A thin white line left over from a grid gutter is only ~2 %, so a painted scene is not "plain".
        val s = 200f / maxOf(w, h)
        val lw = maxOf(8, (w * s).toInt()); val lh = maxOf(8, (h * s).toInt())
        val fg = foreground(bmp, c, lw, lh)
        val bgFrac = fg.count { !it } / fg.size.toFloat()
        return if (bgFrac >= 0.12f) c else null
    }

    /** Foreground map at [w]x[h]: everything except background-coloured pixels connected to the border. */
    fun foreground(bmp: Bitmap, color: Int, w: Int, h: Int): BooleanArray {
        val small = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        val bg = BooleanArray(w * h)
        val stack = IntArray(w * h); var sp = 0
        fun push(i: Int) { if (!bg[i] && near(px[i], color)) { bg[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]; val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        return BooleanArray(w * h) { !bg[it] }
    }
}
