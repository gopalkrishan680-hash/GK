package com.gk.papercut.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/**
 * Offline 4x AI upscaler (Real-ESRGAN "animevideov3", made for illustrations, 2.5 MB).
 * Grid panels are small (a 2x2 grid from a 1536x1024 image gives 768x512 panels); this makes them
 * sharp enough for a 1280x720 video with camera zoom. Works in overlapping 256x256 tiles.
 */
object Upscaler {
    private const val T = 256
    private const val PAD = 16

    /** Images whose longest side is below [minSide] are upscaled to about [targetSide]. */
    fun needsUpscale(b: Bitmap, minSide: Int = 1400) = maxOf(b.width, b.height) < minSide

    fun upscale(src: Bitmap, model: File, targetSide: Int = 1600, progress: (Float) -> Unit = {}): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val ow = w * 4; val oh = h * 4
        val out = IntArray(ow * oh)
        val env = OrtEnvironment.getEnvironment()
        val step = T - 2 * PAD
        val tilesX = (w + step - 1) / step; val tilesY = (h + step - 1) / step
        OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(4)
            env.createSession(model.path, opts).use { sess ->
                val input = FloatBuffer.allocate(3 * T * T)
                var n = 0
                for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
                    progress(n++ / (tilesX * tilesY).toFloat())
                    val x0 = tx * step; val y0 = ty * step
                    // Tile window with context around the core area, clamped to the picture.
                    val xs = (x0 - PAD).coerceIn(0, maxOf(0, w - T)); val ys = (y0 - PAD).coerceIn(0, maxOf(0, h - T))
                    input.clear()
                    for (c in 0 until 3) {
                        val shift = 16 - 8 * c
                        for (y in 0 until T) for (x in 0 until T) {
                            val sx = (xs + x).coerceAtMost(w - 1); val sy = (ys + y).coerceAtMost(h - 1)
                            input.put(((px[sy * w + sx] shr shift) and 255) / 255f)
                        }
                    }
                    input.rewind()
                    OnnxTensor.createTensor(env, input, longArrayOf(1, 3, T.toLong(), T.toLong())).use { t ->
                        sess.run(mapOf(sess.inputNames.first() to t)).use { res ->
                            val o = (res[0] as OnnxTensor).floatBuffer
                            val S = T * 4; val plane = S * S
                            val cx1 = minOf(x0 + step, w); val cy1 = minOf(y0 + step, h)
                            for (y in y0 * 4 until cy1 * 4) {
                                val ly = y - ys * 4
                                for (x in x0 * 4 until cx1 * 4) {
                                    val lx = x - xs * 4
                                    val i = ly * S + lx
                                    val r = (o.get(i) * 255f).toInt().coerceIn(0, 255)
                                    val g = (o.get(plane + i) * 255f).toInt().coerceIn(0, 255)
                                    val b = (o.get(2 * plane + i) * 255f).toInt().coerceIn(0, 255)
                                    out[y * ow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                        }
                    }
                }
            }
        }
        val big = Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
        val k = targetSide.toFloat() / maxOf(ow, oh)
        if (k >= 1f) return big
        return Bitmap.createScaledBitmap(big, (ow * k).toInt(), (oh * k).toInt(), true).also { big.recycle() }
    }
}
