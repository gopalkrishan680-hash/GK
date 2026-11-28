package com.gk.procam

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Verifies the multi-frame pipeline on synthetic hand-held bursts:
 * noisy, shifted frames + an object that moves between frames.
 */
class MergePipelineTest {
    private val w = 640
    private val h = 480

    private fun hash(ix: Int, iy: Int): Double {
        var n = ix * 374761393 + iy * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)) and 0xFFFF) / 65535.0
    }

    /** Smooth, non-periodic value noise. */
    private fun valueNoise(x: Double, y: Double): Double {
        val ix = Math.floor(x).toInt(); val iy = Math.floor(y).toInt()
        val fx = x - ix; val fy = y - iy
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = hash(ix, iy) + (hash(ix + 1, iy) - hash(ix, iy)) * sx
        val b = hash(ix, iy + 1) + (hash(ix + 1, iy + 1) - hash(ix, iy + 1)) * sx
        return a + (b - a) * sy
    }

    private fun scene(x: Int, y: Int): Int {
        // Natural-looking texture at several scales + fine stripes + a gradient.
        val v = 60 + 90 * valueNoise(x / 37.0, y / 37.0) + 50 * valueNoise(x / 9.0, y / 9.0) +
            20 * sin(x * 0.21) * sin(y * 0.13) + 0.05 * x
        return v.toInt().coerceIn(0, 255)
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun frame(dx: Int, dy: Int, sigma: Double, rnd: Random, objX: Int? = null): IntArray =
        IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            var v = scene(x + dx, y + dy).toDouble()
            if (objX != null && x in objX until objX + 60 && y in 200 until 260) v = 250.0
            v += rnd.nextGaussian() * sigma
            gray(v.toInt().coerceIn(0, 255))
        }

    private fun rmse(px: IntArray, margin: Int, objMask: (Int, Int) -> Boolean = { _, _ -> false }): Double {
        var s = 0.0; var n = 0
        for (y in margin until h - margin) for (x in margin until w - margin) {
            if (objMask(x, y)) continue
            val d = ((px[y * w + x] shr 8) and 0xFF) - scene(x, y)
            s += d * d; n++
        }
        return sqrt(s / n)
    }

    @Test
    fun mergeReducesNoiseAndAligns() {
        val rnd = Random(42)
        val shifts = listOf(0 to 0, 7 to -4, -12 to 9, 18 to 3, -5 to -15, 3 to 11)
        val frames = shifts.map { (dx, dy) -> frame(dx, dy, 14.0, rnd) }
        val ref = frames[0].copyOf()
        val before = rmse(ref, 40)
        Processing.debugLog = { println(it) }
        Processing.merge(ref, w, h, frames.size - 1, iso = 1600) { i, dst ->
            frames[i + 1].copyInto(dst); true
        }
        val after = rmse(ref, 40)
        println("noise RMSE before=%.2f after=%.2f (%.2fx better)".format(before, after, before / after))
        assertTrue("merge should cut noise at least 1.8x, got ${before / after}", before / after > 1.8)
    }

    @Test
    fun movingObjectDoesNotGhost() {
        val rnd = Random(7)
        // Object at x=100 in the reference, elsewhere in later frames.
        val frames = listOf(
            frame(0, 0, 10.0, rnd, objX = 100),
            frame(4, -3, 10.0, rnd, objX = 250),
            frame(-6, 5, 10.0, rnd, objX = 380),
            frame(2, 6, 10.0, rnd, objX = 480),
        )
        val ref = frames[0].copyOf()
        Processing.merge(ref, w, h, frames.size - 1, iso = 800) { i, dst -> frames[i + 1].copyInto(dst); true }
        // Where the object appeared only in other frames, the result must still be background.
        var ghost = 0.0; var n = 0
        for (y in 205 until 255) for (x in 260 until 540) {
            val v = (ref[y * w + x] shr 8) and 0xFF
            ghost += kotlin.math.abs(v - scene(x, y)); n++
        }
        val meanErr = ghost / n
        println("mean error in ghost-prone area = %.2f".format(meanErr))
        assertTrue("ghosting detected: $meanErr", meanErr < 12.0)
        // And the real object in the reference must stay bright.
        val objV = (ref[230 * w + 130] shr 8) and 0xFF
        assertTrue("reference object washed out: $objV", objV > 215)
    }

    @Test
    fun nightToneBrightensDarkScene() {
        val px = IntArray(w * h) { gray(25 + (it % w) / 20) }
        val meanBefore = px.map { (it shr 8) and 0xFF }.average()
        Processing.finish(px, w, h, MergeKind.NIGHT)
        val meanAfter = px.map { (it shr 8) and 0xFF }.average()
        println("night mean %.1f -> %.1f".format(meanBefore, meanAfter))
        assertTrue(meanAfter > meanBefore * 2)
        assertTrue(px.all { ((it shr 8) and 0xFF) <= 255 })
    }
}
