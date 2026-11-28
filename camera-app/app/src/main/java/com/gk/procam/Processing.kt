package com.gk.procam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A captured frame kept as compressed JPEG so a long burst stays light on memory. */
class Frame(val jpeg: ByteArray, val rotation: Int, val crop: Rect, val width: Int, val height: Int)

fun ImageProxy.toFrame(): Frame {
    val buf = planes[0].buffer
    buf.rewind()
    val bytes = ByteArray(buf.remaining())
    buf.get(bytes)
    return Frame(bytes, imageInfo.rotationDegrees, Rect(cropRect), width, height)
}

enum class MergeKind { FUSION, NIGHT, PORTRAIT }

/**
 * ProCam computational photography pipeline.
 *
 * 1. Burst of frames, the first one is the reference.
 * 2. Coarse-to-fine alignment: a global shift on a 1/16 and 1/4 luma pyramid, then per-tile
 *    (64 px) refinement so hand shake and slight rotation are corrected locally.
 * 3. Robust merge: every pixel of every frame is weighted by how well it matches the
 *    reference, so moving objects never ghost while static areas average out noise.
 * 4. Tone mapping (exposure gain + highlight roll-off + shadow lift), saturation and
 *    an unsharp mask to bring back micro detail.
 */
object Processing {
    private val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    private val pool = Executors.newFixedThreadPool(cores)

    private fun parallel(n: Int, block: (Int, Int) -> Unit) {
        val chunk = (n + cores - 1) / cores
        (0 until cores).mapNotNull { t ->
            val s = t * chunk
            val e = min(n, s + chunk)
            if (s < e) pool.submit { block(s, e) } else null
        }.forEach { it.get() }
    }

    fun decode(f: Frame): Bitmap {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var b = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size, opts)
            ?: error("Could not decode frame")
        val c = f.crop
        if (b.width == f.width && b.height == f.height && !c.isEmpty &&
            (c.width() != b.width || c.height() != b.height) &&
            c.left >= 0 && c.top >= 0 && c.right <= b.width && c.bottom <= b.height
        ) {
            val cropped = Bitmap.createBitmap(b, c.left, c.top, c.width(), c.height())
            if (cropped !== b) b.recycle()
            b = cropped
        }
        return b
    }

    fun orient(src: Bitmap, rotation: Int, mirror: Boolean): Bitmap {
        if (rotation == 0 && !mirror) return src
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Full pipeline. Returns an upright bitmap (rotation + optional mirror applied). */
    fun process(frames: List<Frame>, kind: MergeKind, iso: Int, mirror: Boolean): Bitmap {
        val ref = decode(frames[0])
        val w = ref.width
        val h = ref.height
        val px = IntArray(w * h)
        ref.getPixels(px, 0, w, 0, 0, w, h)
        ref.recycle()

        if (frames.size > 1) {
            val others = frames.drop(1)
            merge(px, w, h, others.size, iso) { idx, dst ->
                val bmp = runCatching { decode(others[idx]) }.getOrNull() ?: return@merge false
                val ok = bmp.width == w && bmp.height == h
                if (ok) bmp.getPixels(dst, 0, w, 0, 0, w, h)
                bmp.recycle()
                ok
            }
        }
        val out = finish(px, w, h, kind)
        val bmp = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return orient(bmp, frames[0].rotation, mirror)
    }

    // ---------------------------------------------------------------- alignment + merge

    @Volatile var debugLog: ((String) -> Unit)? = null

    private fun green(p: Int) = (p shr 8) and 0xFF

    /** Weighted RGB difference, 0..1020 (green counts double). */
    private fun pixelDiff(p: Int, q: Int): Int =
        abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) * 2 +
            abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))

    /** Box-downsampled green channel. */
    private fun downsample(px: IntArray, w: Int, h: Int, f: Int): IntArray {
        val dw = w / f
        val dh = h / f
        val out = IntArray(dw * dh)
        val area = f * f
        parallel(dh) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until dw) {
                var s = 0
                val by = y * f
                val bx = x * f
                for (yy in 0 until f) {
                    val row = (by + yy) * w + bx
                    for (xx in 0 until f) s += green(px[row + xx])
                }
                out[y * dw + x] = s / area
            }
        }
        return out
    }

    private fun bestShift(
        a: IntArray, b: IntArray, w: Int, h: Int,
        cx: Int, cy: Int, r: Int, step: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
        sampleB: (Int, Int) -> Int = { x, y -> b[y * w + x] },
        sampleA: (Int, Int) -> Int = { x, y -> a[y * w + x] },
        penalty: Int = 2,
    ): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in cy - r..cy + r) for (dx in cx - r..cx + r) {
            var sad = 0L
            var n = 0
            var y = y0
            while (y < y1) {
                val sy = y + dy
                if (sy in 0 until h) {
                    var x = x0
                    while (x < x1) {
                        val sx = x + dx
                        if (sx in 0 until w) {
                            sad += abs(sampleA(x, y) - sampleB(sx, sy))
                            n++
                        }
                        x += step
                    }
                }
                y += step
            }
            if (n == 0) continue
            val norm = sad * 1024 / n
            // Prefer the smallest motion when matches are close: stops flat areas from wandering
            // and repeating patterns (tiles, windows, fences) from locking onto the wrong period.
            val dist = abs(dx - cx) + abs(dy - cy)
            val score = norm + norm * dist * penalty / 100 + dist * 4
            if (score < best) { best = score; bx = dx; by = dy }
        }
        return bx to by
    }

    /** Per-tile refinement on the green channel (hot loop, no lambdas). Returns packed (dx,dy). */
    private fun tileShift(
        ref: IntArray, frm: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
    ): Int {
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in cy - r..cy + r) for (dx in cx - r..cx + r) {
            val ys = maxOf(y0, -dy)
            val ye = minOf(y1, h - dy)
            val xs = maxOf(x0, -dx)
            val xe = minOf(x1, w - dx)
            if (ys >= ye || xs >= xe) continue
            var sad = 0L
            var n = 0
            var y = ys
            while (y < ye) {
                val ra = y * w
                val rb = (y + dy) * w + dx
                var x = xs
                while (x < xe) {
                    sad += abs(((ref[ra + x] shr 8) and 0xFF) - ((frm[rb + x] shr 8) and 0xFF))
                    n++
                    x += 4
                }
                y += 4
            }
            val norm = sad * 1024 / n
            val dist = abs(dx - cx) + abs(dy - cy)
            val score = norm + norm * dist * 2 / 100 + dist * 4
            if (score < best) { best = score; bx = dx; by = dy }
        }
        return ((bx + 512) shl 16) or (by + 512)
    }

    /**
     * Merges [count] extra frames into [refPx] (in place). [load] fills the given buffer with
     * frame `i` (same size as the reference) and returns false to skip it.
     */
    fun merge(refPx: IntArray, w: Int, h: Int, count: Int, iso: Int, load: (Int, IntArray) -> Boolean) {
        val n = w * h
        val accR = ShortArray(n)
        val accG = ShortArray(n)
        val accB = ShortArray(n)
        val wsum = ByteArray(n)
        val refWeight = 8
        parallel(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val p = refPx[i]
                accR[i] = (((p shr 16) and 0xFF) * refWeight).toShort()
                accG[i] = (((p shr 8) and 0xFF) * refWeight).toShort()
                accB[i] = ((p and 0xFF) * refWeight).toShort()
                wsum[i] = refWeight.toByte()
            }
        }
        val ref16 = downsample(refPx, w, h, 16)
        val ref4 = downsample(refPx, w, h, 4)
        val w16 = w / 16; val h16 = h / 16
        val w4 = w / 4; val h4 = h / 4

        // Floor for the rejection threshold (on a 4x scaled channel difference).
        val isoFloor = (3f + iso / 300f).coerceIn(4f, 20f) * 4f

        val framePx = IntArray(n)
        for (fi in 0 until count) {
            if (!load(fi, framePx)) continue

            val f16 = downsample(framePx, w, h, 16)
            val (gx16, gy16) = bestShift(ref16, f16, w16, h16, 0, 0, 5, 1, 6, 6, w16 - 6, h16 - 6, penalty = 6)
            val f4 = downsample(framePx, w, h, 4)
            val (gx4, gy4) = bestShift(ref4, f4, w4, h4, gx16 * 4, gy16 * 4, 4, 2, 16, 16, w4 - 16, h4 - 16)
            val gx = gx4 * 4
            val gy = gy4 * 4

            // Measure this frame's real noise: median difference against the reference after
            // global alignment. Static areas dominate, so the median tracks sensor noise while
            // moving objects sit far above it and get rejected.
            val hist = IntArray(1021)
            var samples = 0
            var yy = 16
            while (yy < h - 16) {
                val sy = yy + gy
                if (sy in 0 until h) {
                    var xx = 16 + (yy % 7)
                    while (xx < w - 16) {
                        val sx = xx + gx
                        if (sx in 0 until w) {
                            hist[pixelDiff(framePx[sy * w + sx], refPx[yy * w + xx])]++
                            samples++
                        }
                        xx += 7
                    }
                }
                yy += 5
            }
            var acc = 0
            var median = 0
            while (median < 1020 && acc < samples / 2) { acc += hist[median]; median++ }
            val tLow = maxOf(isoFloor, median * 2.2f)
            val tHigh = tLow * 2.5f
            debugLog?.invoke("frame $fi global=($gx,$gy) l16=($gx16,$gy16) median=$median tLow=$tLow")

            val tile = 64
            val tilesX = (w + tile - 1) / tile
            val tilesY = (h + tile - 1) / tile
            parallel(tilesY) { ty0, ty1 ->
                for (ty in ty0 until ty1) for (tx in 0 until tilesX) {
                    val x0 = tx * tile
                    val y0 = ty * tile
                    val x1 = min(w, x0 + tile)
                    val y1 = min(h, y0 + tile)
                    val packed = tileShift(refPx, framePx, w, h, gx, gy, 3, x0, y0, x1, y1)
                    val dx = (packed shr 16) - 512
                    val dy = (packed and 0xFFFF) - 512
                    for (y in y0 until y1) {
                        val sy = y + dy
                        if (sy < 0 || sy >= h) continue
                        for (x in x0 until x1) {
                            val sx = x + dx
                            if (sx < 0 || sx >= w) continue
                            val i = y * w + x
                            val p = framePx[sy * w + sx]
                            val q = refPx[i]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            val d = pixelDiff(p, q)
                            val wt = when {
                                d <= tLow -> 8
                                d >= tHigh -> 0
                                else -> (8f * (tHigh - d) / (tHigh - tLow)).toInt()
                            }
                            if (wt == 0) continue
                            accR[i] = (accR[i] + r * wt).toShort()
                            accG[i] = (accG[i] + g * wt).toShort()
                            accB[i] = (accB[i] + b * wt).toShort()
                            wsum[i] = (wsum[i] + wt).toByte()
                        }
                    }
                }
            }
        }
        parallel(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val ws = wsum[i].toInt()
                val half = ws / 2
                val r = ((accR[i].toInt() and 0xFFFF) + half) / ws
                val g = ((accG[i].toInt() and 0xFFFF) + half) / ws
                val b = ((accB[i].toInt() and 0xFFFF) + half) / ws
                refPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    // ---------------------------------------------------------------- tone + detail

    fun finish(px: IntArray, w: Int, h: Int, kind: MergeKind): IntArray {
        // Mean scene luminance (sampled).
        var sum = 0L
        var cnt = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            sum += (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
            cnt++
            i += 97
        }
        val mean = (sum.toFloat() / max(1, cnt)) / 255f

        val gain: Float
        val shadow: Float
        val sat: Float
        val sharpen: Float
        when (kind) {
            MergeKind.NIGHT -> {
                gain = if (mean < 0.32f) (0.42f / max(mean, 0.02f)).coerceIn(1f, 4.5f) else 1f
                shadow = 0.18f; sat = 1.10f; sharpen = 0.45f
            }
            MergeKind.FUSION -> { gain = 1f; shadow = 0.22f; sat = 1.04f; sharpen = 0.55f }
            MergeKind.PORTRAIT -> { gain = 1f; shadow = 0.15f; sat = 1.03f; sharpen = 0.35f }
        }

        // Luma tone curve LUT (1024 entries): gain with extended-Reinhard roll-off + shadow lift.
        val lut = FloatArray(1024) { idx ->
            val l = idx / 1023f
            val v = l * gain
            var o = if (gain > 1.001f) v * (1f + v / (gain * gain)) / (1f + v) else v
            o += shadow * o * (1f - o) * (1f - o)
            o.coerceIn(0f, 1f)
        }

        val toned = IntArray(px.size)
        parallel(h) { y0, y1 ->
            for (j in y0 * w until y1 * w) {
                val p = px[j]
                var r = ((p shr 16) and 0xFF) / 255f
                var g = ((p shr 8) and 0xFF) / 255f
                var b = (p and 0xFF) / 255f
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val lo = lut[(l * 1023f).toInt().coerceIn(0, 1023)]
                val s = if (l > 1e-4f) lo / l else 1f
                r *= s; g *= s; b *= s
                r = lo + (r - lo) * sat
                g = lo + (g - lo) * sat
                b = lo + (b - lo) * sat
                toned[j] = (0xFF shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
            }
        }

        // Unsharp mask, 3x3 box.
        val amt = sharpen
        parallel(h) { y0, y1 ->
            for (y in y0 until y1) {
                if (y == 0 || y == h - 1) {
                    System.arraycopy(toned, y * w, px, y * w, w); continue
                }
                px[y * w] = toned[y * w]
                px[y * w + w - 1] = toned[y * w + w - 1]
                for (x in 1 until w - 1) {
                    val c = y * w + x
                    var sr = 0; var sg = 0; var sb = 0
                    for (oy in -1..1) {
                        val row = c + oy * w
                        for (ox in -1..1) {
                            val q = toned[row + ox]
                            sr += (q shr 16) and 0xFF; sg += (q shr 8) and 0xFF; sb += q and 0xFF
                        }
                    }
                    val p = toned[c]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val nr = r + amt * (r - sr / 9f)
                    val ng = g + amt * (g - sg / 9f)
                    val nb = b + amt * (b - sb / 9f)
                    px[c] = (0xFF shl 24) or (ci(nr) shl 16) or (ci(ng) shl 8) or ci(nb)
                }
            }
        }
        return px
    }

    private fun c8(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    private fun ci(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

    // ---------------------------------------------------------------- shared helpers

    /** Separable sliding-window box blur, in place, on a float plane. */
    fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int, passes: Int = 3) {
        if (r < 1) return
        val tmp = FloatArray(src.size)
        repeat(passes) {
            parallel(h) { y0, y1 ->
                for (y in y0 until y1) {
                    val row = y * w
                    var acc = 0f
                    for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
                    val inv = 1f / (2 * r + 1)
                    for (x in 0 until w) {
                        tmp[row + x] = acc * inv
                        acc += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
                    }
                }
            }
            parallel(w) { x0, x1 ->
                for (x in x0 until x1) {
                    var acc = 0f
                    for (k in -r..r) acc += tmp[k.coerceIn(0, h - 1) * w + x]
                    val inv = 1f / (2 * r + 1)
                    for (y in 0 until h) {
                        src[y * w + x] = acc * inv
                        acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
                    }
                }
            }
        }
    }

    fun runParallel(n: Int, block: (Int, Int) -> Unit) = parallel(n, block)

    val gammaToLinear = FloatArray(256) { (it / 255f).pow(2.2f) }
    private val linearLut = IntArray(4097) { ((it / 4096f).pow(1f / 2.2f) * 255f).roundToInt() }
    fun linearToByte(v: Float): Int = linearLut[(v.coerceIn(0f, 1f) * 4096f).toInt()]
}
