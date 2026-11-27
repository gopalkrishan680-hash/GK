package com.gk.papercut.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/**
 * Fills the holes left behind after the cut-outs are lifted off, so the background plate is clean.
 * Uses LaMa (512x512, input 0..1, output 0..255) when downloaded, otherwise a fast push-pull fill.
 */
/** A loaded LaMa model; load once and reuse for every scene (loading takes several seconds). */
class Lama(model: File) : java.io.Closeable {
    internal val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
    internal val session: OrtSession = env.createSession(model.path, opts)
    override fun close() { session.close(); opts.close() }
}

object Inpainter {

    /** Convenience for one-off use (loads the model each time). */
    fun inpaint(src: Bitmap, holes: Mask, lama: File?): Bitmap {
        if (lama == null || !lama.exists()) return composite(src, pushPull(src, holes), holes)
        return Lama(lama).use { inpaint(src, holes, it) }
    }

    fun inpaint(src: Bitmap, holes: Mask, lama: Lama?): Bitmap {
        val filled = if (lama != null) runCatching { runLama(src, holes, lama) }.getOrElse { pushPull(src, holes) }
        else pushPull(src, holes)
        return composite(src, filled, holes)
    }

    private const val S = 512

    private fun runLama(src: Bitmap, holes: Mask, lama: Lama): Bitmap {
        val env = lama.env
        val small = Bitmap.createScaledBitmap(src, S, S, true)
        val px = IntArray(S * S); small.getPixels(px, 0, S, 0, 0, S, S)
        val img = FloatBuffer.allocate(3 * S * S)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (p in px) img.put(((p shr shift) and 255) / 255f)
        }
        img.rewind()
        val mask = FloatBuffer.allocate(S * S)
        for (y in 0 until S) for (x in 0 until S) {
            // Max-pool the full-res mask into 512x512 so thin holes are not lost.
            val x0 = x * holes.w / S; val x1 = maxOf(x0 + 1, (x + 1) * holes.w / S)
            val y0 = y * holes.h / S; val y1 = maxOf(y0 + 1, (y + 1) * holes.h / S)
            var v = 0f
            for (yy in y0 until minOf(y1, holes.h)) for (xx in x0 until minOf(x1, holes.w)) if (holes.a[yy * holes.w + xx] > v) v = holes.a[yy * holes.w + xx]
            mask.put(if (v > 0.3f) 1f else 0f)
        }
        mask.rewind()
        run {
            val sess = lama.session
            run {
                OnnxTensor.createTensor(env, img, longArrayOf(1, 3, S.toLong(), S.toLong())).use { ti ->
                    OnnxTensor.createTensor(env, mask, longArrayOf(1, 1, S.toLong(), S.toLong())).use { tm ->
                        sess.run(mapOf("image" to ti, "mask" to tm)).use { res ->
                            val o = (res[0] as OnnxTensor).floatBuffer
                            val out = IntArray(S * S)
                            for (i in 0 until S * S) {
                                val r = o.get(i).toInt().coerceIn(0, 255)
                                val g = o.get(S * S + i).toInt().coerceIn(0, 255)
                                val b = o.get(2 * S * S + i).toInt().coerceIn(0, 255)
                                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            val bmp = Bitmap.createBitmap(out, S, S, Bitmap.Config.ARGB_8888)
                            return Bitmap.createScaledBitmap(bmp, src.width, src.height, true)
                        }
                    }
                }
            }
        }
    }

    /** Pyramid push-pull fill: averages known pixels down, then pulls colour back into the holes. */
    fun pushPull(src: Bitmap, holes: Mask): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val levels = mutableListOf<Array<FloatArray>>() // r, g, b, weight
        var cw = w; var ch = h
        var cur = Array(4) { FloatArray(w * h) }
        for (i in 0 until w * h) {
            val k = if (holes.a[i] > 0.3f) 0f else 1f
            cur[0][i] = ((px[i] shr 16) and 255) * k; cur[1][i] = ((px[i] shr 8) and 255) * k
            cur[2][i] = (px[i] and 255) * k; cur[3][i] = k
        }
        val dims = mutableListOf(cw to ch)
        levels += cur
        while (cw > 1 || ch > 1) {
            val nw = maxOf(1, (cw + 1) / 2); val nh = maxOf(1, (ch + 1) / 2)
            val nxt = Array(4) { FloatArray(nw * nh) }
            for (y in 0 until ch) for (x in 0 until cw) {
                val j = (y / 2) * nw + x / 2; val i = y * cw + x
                for (c in 0 until 4) nxt[c][j] += cur[c][i]
            }
            cur = nxt; cw = nw; ch = nh; levels += cur; dims += cw to ch
        }
        // Normalise the coarsest level, then pull: fill low-weight pixels from the (bilinear) coarser level.
        levels.last().let { top -> for (i in top[3].indices) if (top[3][i] > 0f) { for (c in 0 until 3) top[c][i] /= top[3][i]; top[3][i] = 1f } }
        for (l in levels.size - 2 downTo 0) {
            val (lw, lh) = dims[l]; val (pw, ph) = dims[l + 1]
            val a = levels[l]; val p = levels[l + 1]
            for (y in 0 until lh) for (x in 0 until lw) {
                val i = y * lw + x
                val wsum = a[3][i]
                val wgt = wsum.coerceAtMost(1f)
                if (wgt < 1f) {
                    // Bilinear sample of the coarser level at this pixel's centre.
                    val fx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (pw - 1).toFloat())
                    val fy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (ph - 1).toFloat())
                    val x0 = fx.toInt(); val y0 = fy.toInt()
                    val x1 = minOf(x0 + 1, pw - 1); val y1 = minOf(y0 + 1, ph - 1)
                    val tx = fx - x0; val ty = fy - y0
                    for (c in 0 until 3) {
                        val pc = p[c]
                        val top = pc[y0 * pw + x0] * (1 - tx) + pc[y0 * pw + x1] * tx
                        val bot = pc[y1 * pw + x0] * (1 - tx) + pc[y1 * pw + x1] * tx
                        val own = if (wsum > 0f) a[c][i] / wsum else 0f
                        a[c][i] = own * wgt + (top * (1 - ty) + bot * ty) * (1 - wgt)
                    }
                } else {
                    for (c in 0 until 3) a[c][i] /= wsum
                }
                a[3][i] = 1f
            }
        }
        val base = levels[0]
        // A few smoothing passes inside the holes remove any remaining blockiness.
        val holeIdx = (0 until w * h).filter { holes.a[it] > 0.3f }.toIntArray()
        repeat(30) {
            for (i in holeIdx) {
                val x = i % w; val y = i / w
                val l = if (x > 0) i - 1 else i; val r = if (x < w - 1) i + 1 else i
                val u = if (y > 0) i - w else i; val d = if (y < h - 1) i + w else i
                for (c in 0 until 3) { val ch = base[c]; ch[i] = (ch[l] + ch[r] + ch[u] + ch[d]) * 0.25f }
            }
        }
        val out = IntArray(w * h) { i ->
            (0xFF shl 24) or (base[0][i].toInt().coerceIn(0, 255) shl 16) or
                (base[1][i].toInt().coerceIn(0, 255) shl 8) or base[2][i].toInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Only replace pixels inside the (soft) hole mask. */
    private fun composite(src: Bitmap, filled: Bitmap, holes: Mask): Bitmap {
        val w = src.width; val h = src.height
        val a = IntArray(w * h); src.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h); filled.getPixels(b, 0, w, 0, 0, w, h)
        for (i in a.indices) {
            val m = holes.a[i].coerceIn(0f, 1f)
            if (m <= 0f) continue
            fun mix(s: Int) = (((a[i] shr s) and 255) * (1 - m) + ((b[i] shr s) and 255) * m).toInt()
            a[i] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }
}
