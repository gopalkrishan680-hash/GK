package com.gk.facelessstudio.render

import android.graphics.Bitmap
import android.util.LruCache
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * "When does each pixel appear" maps that make a picture look hand drawn:
 *  - [outline]: grows along the dark lines of the pencil sketch from a few starting points (like a pen tracing
 *    contours), found with a shortest-path search where dark pixels are cheap to travel through;
 *  - [colour]: one of four painting orders (patches, following the pen, outward from a point, brush strokes).
 * Values are 0..1 on a small grid ([W]×[H]) and are scaled up when drawn.
 */
class DrawOrder(val outline: FloatArray, val colour: FloatArray) {
    companion object {
        const val W = 256
        const val H = 144
        private val cache = LruCache<String, DrawOrder>(64)

        fun of(sketch: Bitmap, key: String, variant: Int): DrawOrder {
            val k = "$key#${variant and 0xFFFF}"
            cache.get(k)?.let { return it }
            val small = Bitmap.createScaledBitmap(sketch, W, H, true)
            val px = IntArray(W * H); small.getPixels(px, 0, W, 0, 0, W, H)
            if (small !== sketch) small.recycle()
            val lum = FloatArray(W * H) { (px[it] and 255) / 255f }
            val r = Random(variant)
            val outline = trace(lum, r)
            val colour = when (variant.mod(4)) {
                0 -> patches(r)
                1 -> follow(outline, r)
                2 -> radial(r)
                else -> strokes(r)
            }
            return DrawOrder(outline, colour).also { cache.put(k, it) }
        }

        // ---------------- outline: shortest paths along dark lines
        private fun trace(lum: FloatArray, r: Random): FloatArray {
            val n = W * H
            val dist = FloatArray(n) { Float.MAX_VALUE }
            val pq = PriorityQueue<Long>()
            fun push(i: Int, d: Float) { dist[i] = d; pq.add((java.lang.Float.floatToIntBits(d).toLong() shl 32) or i.toLong()) }
            // 2-3 seeds on dark pixels, biased towards the middle where the subject usually is
            val seeds = 2 + r.nextInt(2)
            repeat(seeds) {
                var best = -1; var bestScore = Float.MAX_VALUE
                repeat(400) {
                    val x = (W * (0.15f + r.nextFloat() * 0.7f)).toInt(); val y = (H * (0.15f + r.nextFloat() * 0.7f)).toInt()
                    val i = y * W + x
                    val sc = lum[i] + 0.3f * hypot((x - W / 2f) / W, (y - H / 2f) / H)
                    if (sc < bestScore) { bestScore = sc; best = i }
                }
                if (best >= 0) push(best, 0f)
            }
            val dx = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1); val dy = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
            val step = floatArrayOf(1f, 1f, 1f, 1f, 1.41f, 1.41f, 1.41f, 1.41f)
            while (pq.isNotEmpty()) {
                val e = pq.poll()!!
                val i = (e and 0xFFFFFFFFL).toInt()
                val d = java.lang.Float.intBitsToFloat((e ushr 32).toInt())
                if (d > dist[i]) continue
                val x = i % W; val y = i / W
                for (k in 0 until 8) {
                    val nx = x + dx[k]; val ny = y + dy[k]
                    if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue
                    val j = ny * W + nx
                    val l = lum[j]
                    val cost = (0.25f + l * l * l * 14f) * step[k]      // dark = pencil line = quick to draw along
                    val nd = d + cost
                    if (nd < dist[j]) push(j, nd)
                }
            }
            return normalise(dist)
        }

        private fun normalise(a: FloatArray): FloatArray {
            val sorted = a.copyOf().also { it.sort() }
            val top = max(1e-3f, sorted[(sorted.size * 0.97f).toInt().coerceAtMost(sorted.size - 1)])
            return FloatArray(a.size) { min(1f, a[it] / top) }
        }

        private fun noise(x: Float, y: Float, seed: Int): Float {
            fun h(ix: Int, iy: Int): Float { var v = ix * 374761393 + iy * 668265263 + seed * 1442695041; v = (v xor (v ushr 13)) * 1274126177; return ((v xor (v ushr 16)) and 0xFFFF) / 65535f }
            val x0 = kotlin.math.floor(x).toInt(); val y0 = kotlin.math.floor(y).toInt()
            val fx = x - x0; val fy = y - y0
            val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
            val a = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * sx
            val b = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * sx
            return a + (b - a) * sy
        }

        // ---------------- colour orders
        /** Paint patch by patch (Voronoi cells in random order), each patch growing from its centre. */
        private fun patches(r: Random): FloatArray {
            val k = 12 + r.nextInt(8)
            val px = FloatArray(k) { r.nextFloat() * W }; val py = FloatArray(k) { r.nextFloat() * H }
            val rank = (0 until k).shuffled(r)
            val seed = r.nextInt()
            return FloatArray(W * H) { i ->
                val x = (i % W).toFloat(); val y = (i / W).toFloat()
                var best = 0; var bd = Float.MAX_VALUE
                for (c in 0 until k) { val d = (x - px[c]) * (x - px[c]) + (y - py[c]) * (y - py[c]); if (d < bd) { bd = d; best = c } }
                val local = min(1f, sqrt(bd) / (W / sqrt(k.toFloat())))
                ((rank[best] + 0.85f * local + 0.1f * noise(x / 9f, y / 9f, seed)) / k).coerceIn(0f, 1f)
            }
        }

        /** Colour follows where the pen went first, with a little brush noise. */
        private fun follow(outline: FloatArray, r: Random): FloatArray {
            val seed = r.nextInt()
            return FloatArray(W * H) { i -> (outline[i] * 0.85f + 0.15f * noise((i % W) / 7f, (i / W) / 7f, seed)).coerceIn(0f, 1f) }
        }

        /** Spreads out from one point with a wobbly edge. */
        private fun radial(r: Random): FloatArray {
            val cx = W * (0.3f + r.nextFloat() * 0.4f); val cy = H * (0.3f + r.nextFloat() * 0.4f)
            val maxD = hypot(max(cx, W - cx), max(cy, H - cy))
            val seed = r.nextInt()
            return FloatArray(W * H) { i ->
                val x = i % W - cx; val y = i / W - cy
                val a = atan2(y, x)
                (hypot(x, y) / maxD + 0.07f * sin(a * 5f + seed % 7) + 0.08f * (noise(i % W / 10f, i / W / 10f, seed) - 0.5f)).coerceIn(0f, 1f)
            }
        }

        /** Broad wavy brush strokes, band by band in random order and direction. */
        private fun strokes(r: Random): FloatArray {
            val bands = 4 + r.nextInt(3)
            val order = (0 until bands).shuffled(r)
            val dir = BooleanArray(bands) { r.nextBoolean() }
            val vertical = r.nextBoolean()
            val seed = r.nextInt()
            return FloatArray(W * H) { i ->
                val x = i % W; val y = i / W
                val across = if (vertical) x / W.toFloat() else y / H.toFloat()
                val along = if (vertical) y / H.toFloat() else x / W.toFloat()
                val wave = 0.06f * sin(along * PI.toFloat() * 3f + seed % 5)
                val b = ((across + wave) * bands).toInt().coerceIn(0, bands - 1)
                val f = if (dir[b]) along else 1 - along
                ((order[b] + 0.9f * f + 0.1f * noise(x / 6f, y / 6f, seed)) / bands).coerceIn(0f, 1f)
            }
        }
    }
}
