package com.gk.papercut.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * MobileSAM (Segment Anything) on ONNX Runtime with the multi-mask decoder.
 * Encoder input: HWC float 0..255, longest side 1024. Prompt points live in that 1024 space;
 * the decoder can return masks at any output size, which lets the auto mode work at low resolution.
 */
class MobileSam(encoder: File, decoder: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
    private val enc = env.createSession(encoder.path, opts)
    private val dec = env.createSession(decoder.path, opts)

    private var embedding: FloatArray? = null
    private var scale = 1f
    private var imgW = 0
    private var imgH = 0

    fun setImage(bmp: Bitmap) {
        imgW = bmp.width; imgH = bmp.height
        scale = 1024f / maxOf(imgW, imgH)
        val rw = (imgW * scale).roundToInt(); val rh = (imgH * scale).roundToInt()
        val small = Bitmap.createScaledBitmap(bmp, rw, rh, true)
        val px = IntArray(rw * rh)
        small.getPixels(px, 0, rw, 0, 0, rw, rh)
        val fb = FloatBuffer.allocate(rw * rh * 3)
        for (p in px) { fb.put(((p shr 16) and 255).toFloat()); fb.put(((p shr 8) and 255).toFloat()); fb.put((p and 255).toFloat()) }
        fb.rewind()
        OnnxTensor.createTensor(env, fb, longArrayOf(rh.toLong(), rw.toLong(), 3)).use { input ->
            enc.run(mapOf(enc.inputNames.first() to input)).use { res ->
                val b = (res[0] as OnnxTensor).floatBuffer
                embedding = FloatArray(b.remaining()).also { b.get(it) }
            }
        }
    }

    /** Raw decoder call: returns K mask logits of size outW*outH and their predicted IoUs. */
    private fun decode(points: List<Pair<Float, Float>>, labels: List<Float>, outW: Int, outH: Int): Pair<List<FloatArray>, FloatArray> {
        val emb = embedding ?: error("Call setImage first")
        val n = points.size + 1 // padding point (no box prompt)
        val coords = FloatBuffer.allocate(n * 2)
        points.forEach { (x, y) -> coords.put(x * scale); coords.put(y * scale) }
        coords.put(0f); coords.put(0f); coords.rewind()
        val lab = FloatBuffer.allocate(n); labels.forEach { lab.put(it) }; lab.put(-1f); lab.rewind()
        val inputs = mapOf(
            "image_embeddings" to OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 256, 64, 64)),
            "point_coords" to OnnxTensor.createTensor(env, coords, longArrayOf(1, n.toLong(), 2)),
            "point_labels" to OnnxTensor.createTensor(env, lab, longArrayOf(1, n.toLong())),
            "mask_input" to OnnxTensor.createTensor(env, FloatBuffer.allocate(256 * 256), longArrayOf(1, 1, 256, 256)),
            "has_mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)),
            "orig_im_size" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(outH.toFloat(), outW.toFloat())), longArrayOf(2)),
        )
        try {
            dec.run(inputs).use { res ->
                val mt = res[0] as OnnxTensor
                val k = mt.info.shape[1].toInt()
                val buf = mt.floatBuffer
                val plane = outW * outH
                val masks = List(k) { i -> FloatArray(plane).also { buf.position(i * plane); buf.get(it) } }
                val ib = (res[1] as OnnxTensor).floatBuffer
                val ious = FloatArray(k) { ib.get(it) }
                return masks to ious
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun toMask(logits: FloatArray, w: Int, h: Int) = Mask(w, h, FloatArray(w * h) { (1.0 / (1.0 + exp(-logits[it].toDouble()))).toFloat() })

    /**
     * Tap-to-cut: returns the largest confident mask under the point, i.e. the whole object
     * (person, house…) rather than a small part (face, crown…).
     */
    fun segment(x: Float, y: Float): Mask? {
        val (masks, ious) = decode(listOf(x to y), listOf(1f), imgW, imgH)
        val total = imgW.toFloat() * imgH
        val best = masks.indices
            .filter { ious[it] > 0.7f }
            .map { it to masks[it].count { v -> v > 0f } }
            .filter { it.second in (total * 0.002f).toInt()..(total * 0.7f).toInt() }
            .maxByOrNull { it.second }?.first
            ?: ious.indices.maxByOrNull { ious[it] } ?: return null
        return toMask(masks[best], imgW, imgH).keepMainParts()
    }

    private class Cand(val point: Pair<Float, Float>, val index: Int, val bits: BooleanArray, val area: Int)

    /**
     * Automatic mode for crowded pictures:
     *  1. prompt a grid of points on the foreground, keep the largest confident mask per point
     *  2. non-maximum suppression (largest first)
     *  3. second pass on foreground regions nobody claimed yet
     *  4. recompute the kept masks at full resolution
     * [fg] is a low-res foreground map ([lw] x [lh]) or null if the background is not plain.
     */
    fun autoSegment(fg: BooleanArray?, lw: Int, lh: Int, cols: Int = 10, rows: Int = 6, progress: (Float) -> Unit = {}): List<Mask> {
        val total = lw * lh
        val maxArea = (total * 0.30f).toInt()
        val minArea = (total * 0.004f).toInt()
        val sx = imgW / lw.toFloat(); val sy = imgH / lh.toFloat()
        fun isFg(x: Int, y: Int) = fg == null || fg[y.coerceIn(0, lh - 1) * lw + x.coerceIn(0, lw - 1)]

        fun candidate(px: Float, py: Float, region: BooleanArray?): Cand? {
            val (masks, ious) = decode(listOf(px * sx to py * sy), listOf(1f), lw, lh)
            var best: Cand? = null
            var bestScore = -1f
            for (k in masks.indices) {
                if (ious[k] < 0.72f) continue
                val bits = BooleanArray(total) { masks[k][it] > 0f }
                val area = bits.count { it }
                if (area < minArea || area > maxArea) continue
                if (fg != null) {
                    var inFg = 0
                    for (i in 0 until total) if (bits[i] && fg[i]) inFg++
                    if (inFg < area * 0.8f) continue // mostly background = not an element
                }
                val score = if (region == null) area.toFloat() else {
                    var inside = 0; for (i in 0 until total) if (bits[i] && region[i]) inside++
                    inside.toFloat() * inside / area
                }
                if (score > bestScore) { bestScore = score; best = Cand(px to py, k, bits, area) }
            }
            return best
        }

        val cands = mutableListOf<Cand>()
        val steps = rows * cols
        var s = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            progress(0.7f * s++ / steps)
            val x = lw * (c + 0.5f) / cols; val y = lh * (r + 0.5f) / rows
            if (!isFg(x.toInt(), y.toInt())) continue
            candidate(x, y, null)?.let { cands += it }
        }
        val kept = mutableListOf<Cand>()
        val covered = BooleanArray(total)
        fun tryKeep(c: Cand): Boolean {
            var overlap = 0
            for (i in 0 until total) if (c.bits[i] && covered[i]) overlap++
            if (overlap > c.area * 0.5f) return false
            kept += c; for (i in 0 until total) if (c.bits[i]) covered[i] = true
            return true
        }
        cands.sortedByDescending { it.area }.forEach { tryKeep(it) }

        // Second pass: unclaimed foreground blobs (e.g. a person between two others).
        if (fg != null) {
            val rest = BooleanArray(total) { fg[it] && !covered[it] }
            val blobs = Blobs.find(rest, lw, lh, minArea)
            blobs.forEachIndexed { i, b ->
                progress(0.7f + 0.2f * i / blobs.size.coerceAtLeast(1))
                val (ix, iy) = Blobs.interiorPoint(b, lw, lh)
                candidate(ix + 0.5f, iy + 0.5f, b)?.let { tryKeep(it) }
            }
        }

        // Full-resolution soft masks for the kept candidates.
        return kept.mapIndexed { i, c ->
            progress(0.9f + 0.1f * i / kept.size)
            val (masks, _) = decode(listOf(c.point.first * sx to c.point.second * sy), listOf(1f), imgW, imgH)
            toMask(masks[c.index.coerceAtMost(masks.lastIndex)], imgW, imgH).keepMainParts()
        }
    }

    override fun close() { enc.close(); dec.close(); opts.close() }
}

/** Small helpers on low-res boolean maps. */
object Blobs {
    /** 4-neighbour labelling: label per pixel (0 = empty, k = part k) and the size of each part. */
    fun label(map: BooleanArray, w: Int, h: Int): Pair<IntArray, List<Int>> {
        val label = IntArray(w * h)
        val sizes = ArrayList<Int>()
        val stack = IntArray(w * h)
        for (start in map.indices) {
            if (!map[start] || label[start] != 0) continue
            val id = sizes.size + 1
            var sp = 0; stack[sp++] = start; label[start] = id
            var n = 0
            while (sp > 0) {
                val i = stack[--sp]; n++
                val x = i % w; val y = i / w
                if (x > 0 && map[i - 1] && label[i - 1] == 0) { label[i - 1] = id; stack[sp++] = i - 1 }
                if (x < w - 1 && map[i + 1] && label[i + 1] == 0) { label[i + 1] = id; stack[sp++] = i + 1 }
                if (y > 0 && map[i - w] && label[i - w] == 0) { label[i - w] = id; stack[sp++] = i - w }
                if (y < h - 1 && map[i + w] && label[i + w] == 0) { label[i + w] = id; stack[sp++] = i + w }
            }
            sizes += n
        }
        return label to sizes
    }

    /** Connected components (4-neighbour) with at least [minArea] pixels. */
    fun find(map: BooleanArray, w: Int, h: Int, minArea: Int): List<BooleanArray> {
        val (labels, sizes) = label(map, w, h)
        return sizes.indices.filter { sizes[it] >= minArea }.map { k -> BooleanArray(w * h) { labels[it] == k + 1 } }
    }

    /** The pixel deepest inside the blob (two-pass chamfer distance). */
    fun interiorPoint(b: BooleanArray, w: Int, h: Int): Pair<Int, Int> {
        val big = 1_000_000
        val d = IntArray(w * h) { if (b[it]) big else 0 }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; if (d[i] == 0) continue
            var v = d[i]
            if (x > 0) v = minOf(v, d[i - 1] + 1) else v = minOf(v, 1)
            if (y > 0) v = minOf(v, d[i - w] + 1) else v = minOf(v, 1)
            d[i] = v
        }
        var best = 0; var bi = b.indexOfFirst { it }.coerceAtLeast(0)
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x; if (d[i] == 0) continue
            var v = d[i]
            v = if (x < w - 1) minOf(v, d[i + 1] + 1) else minOf(v, 1)
            v = if (y < h - 1) minOf(v, d[i + w] + 1) else minOf(v, 1)
            d[i] = v
            if (v > best) { best = v; bi = i }
        }
        return (bi % w) to (bi / w)
    }
}
