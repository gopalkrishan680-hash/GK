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
    /** Reused for every decoder call (creating it copies 4 MB each time). */
    private var embTensor: OnnxTensor? = null
    private val emptyMask: OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.allocate(256 * 256), longArrayOf(1, 1, 256, 256))
    private val noMask: OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1))
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
                embTensor?.close()
                embTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(embedding!!), longArrayOf(1, 256, 64, 64))
            }
        }
    }

    /** Raw decoder call: returns K mask logits of size outW*outH and their predicted IoUs. */
    /** Debug timings (ns): decoder runs and copies. */
    val stats = LongArray(4)

    private fun decode(points: List<Pair<Float, Float>>, labels: List<Float>, outW: Int, outH: Int, only: Int = -1): Pair<List<FloatArray>, FloatArray> {
        val emb = embedding ?: error("Call setImage first")
        val hasBox = labels.contains(2f)
        val n = points.size + if (hasBox) 0 else 1 // SAM wants a padding point when there is no box prompt
        val coords = FloatBuffer.allocate(n * 2)
        points.forEach { (x, y) -> coords.put(x * scale); coords.put(y * scale) }
        if (!hasBox) { coords.put(0f); coords.put(0f) }
        coords.rewind()
        val lab = FloatBuffer.allocate(n); labels.forEach { lab.put(it) }; if (!hasBox) lab.put(-1f); lab.rewind()
        val perCall = mapOf(
            "point_coords" to OnnxTensor.createTensor(env, coords, longArrayOf(1, n.toLong(), 2)),
            "point_labels" to OnnxTensor.createTensor(env, lab, longArrayOf(1, n.toLong())),
            "orig_im_size" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(outH.toFloat(), outW.toFloat())), longArrayOf(2)),
        )
        val inputs = perCall + mapOf("image_embeddings" to embTensor!!, "mask_input" to emptyMask, "has_mask_input" to noMask)
        val t0 = System.nanoTime()
        try {
            dec.run(inputs).use { res ->
                stats[0] += System.nanoTime() - t0; stats[1]++
                val mt = res[0] as OnnxTensor
                val k = mt.info.shape[1].toInt()
                val buf = mt.floatBuffer
                val plane = outW * outH
                // [only] >= 0: copy just that mask (full-res masks are big).
                val wanted = if (only >= 0) listOf(only.coerceAtMost(k - 1)) else (0 until k).toList()
                val masks = wanted.map { i -> FloatArray(plane).also { buf.position(i * plane); buf.get(it) } }
                val ib = (res[1] as OnnxTensor).floatBuffer
                val ious = FloatArray(k) { ib.get(it) }
                return masks to ious
            }
        } finally {
            perCall.values.forEach { it.close() }
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
     *  4. small fragments (a hair bun, a lone hand…) are merged into the element they touch
     *  5. recompute the kept masks at full resolution
     * [fg] is a low-res foreground map ([lw] x [lh]) or null if the background is not plain.
     */
    fun autoSegment(fg: BooleanArray?, lw: Int, lh: Int, cols: Int = 8, rows: Int = 5, progress: (Float) -> Unit = {}): List<Mask> {
        val total = lw * lh
        val maxArea = (total * 0.30f).toInt()
        val minArea = (total * 0.004f).toInt()
        val sx = imgW / lw.toFloat(); val sy = imgH / lh.toFloat()
        fun isFg(x: Int, y: Int) = fg == null || fg[y.coerceIn(0, lh - 1) * lw + x.coerceIn(0, lw - 1)]

        fun candidate(px: Float, py: Float, region: BooleanArray?): Cand? {
            val (masks, ious) = decode(listOf(px * sx to py * sy), listOf(1f), lw, lh)
            var bestK = -1; var bestScore = -1f; var bestArea = 0
            for (k in masks.indices) {
                if (ious[k] < 0.72f) continue
                val m = masks[k]
                // One pass, no allocation: area, foreground overlap and region overlap.
                var area = 0; var inFg = 0; var inside = 0
                for (i in 0 until total) if (m[i] > 0f) {
                    area++
                    if (fg != null && fg[i]) inFg++
                    if (region != null && region[i]) inside++
                }
                if (area < minArea || area > maxArea) continue
                if (fg != null && inFg < area * 0.8f) continue // mostly background = not an element
                val score = if (region == null) area.toFloat() else inside.toFloat() * inside / area
                if (score > bestScore) { bestScore = score; bestK = k; bestArea = area }
            }
            if (bestK < 0) return null
            val m = masks[bestK]
            return Cand(px to py, bestK, BooleanArray(total) { m[it] > 0f }, bestArea)
        }

        val cands = mutableListOf<Cand>()
        val seen = BooleanArray(total) // pixels already inside some candidate -> no need to prompt there again
        val steps = rows * cols
        var s = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            progress(0.6f * s++ / steps)
            val x = lw * (c + 0.5f) / cols; val y = lh * (r + 0.5f) / rows
            if (!isFg(x.toInt(), y.toInt())) continue
            if (seen[y.toInt().coerceIn(0, lh - 1) * lw + x.toInt().coerceIn(0, lw - 1)]) continue
            candidate(x, y, null)?.let { cand -> cands += cand; for (i in 0 until total) if (cand.bits[i]) seen[i] = true }
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
                progress(0.6f + 0.2f * i / blobs.size.coerceAtLeast(1))
                val (ix, iy) = Blobs.interiorPoint(b, lw, lh)
                candidate(ix + 0.5f, iy + 0.5f, b)?.let { tryKeep(it) }
            }
        }

        // Merge small fragments into the neighbour they touch most.
        val lowOwner = IntArray(total) { -1 }
        kept.forEachIndexed { k, c -> for (i in 0 until total) if (c.bits[i] && lowOwner[i] < 0) lowOwner[i] = k }
        val group = IntArray(kept.size) { it }
        fun root(k: Int): Int { var r = k; while (group[r] != r) r = group[r]; return r }
        val small = (total * 0.01f).toInt() // only real fragments (bun, lone hand), not whole people/pillars
        kept.indices.sortedBy { kept[it].area }.forEach { k ->
            if (kept[k].area >= small) return@forEach
            val touch = HashMap<Int, Int>()
            val bits = kept[k].bits
            for (i in 0 until total) {
                if (!bits[i]) continue
                val x = i % lw; val y = i / lw
                for (dy in -2..2) for (dx in -2..2) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until lw || yy !in 0 until lh) continue
                    val o = lowOwner[yy * lw + xx]
                    if (o >= 0 && root(o) != root(k)) touch.merge(root(o), 1, Int::plus)
                }
            }
            touch.maxByOrNull { it.value }?.let { (other, n) -> if (n >= 6) group[root(k)] = other }
        }
        val groups = kept.indices.groupBy { root(it) }.values.toList()
        stats[2] = System.nanoTime()

        // Full resolution. Each group is asked again with a BOX around it, so SAM returns the whole character
        // (arms, legs, head) instead of a part. Then every pixel belongs to exactly ONE group — the one SAM is
        // most sure about — so a hand is never cut into two layers or glued to the wrong character.
        val n = imgW * imgH
        val best = FloatArray(n) { -20f }
        val owner = IntArray(n) { -1 }
        // What the scan itself found keeps priority: a refined box mask may ADD unclaimed limbs, but may not
        // take a part (e.g. the king's sandal) that the scan already gave to another character.
        val scanOwner = IntArray(total) { -1 }
        groups.forEachIndexed { gi, members -> members.forEach { k -> val kb = kept[k].bits; for (i in 0 until total) if (kb[i] && scanOwner[i] < 0) scanOwner[i] = gi } }
        val locked = BooleanArray(n)
        fun cellOf(i: Int): Int {
            val lx = ((i % imgW) / sx).toInt().coerceIn(0, lw - 1); val ly = ((i / imgW) / sy).toInt().coerceIn(0, lh - 1)
            return ly * lw + lx
        }
        groups.forEachIndexed { gi, members ->
            progress(0.8f + 0.15f * gi / groups.size)
            val lowBits = BooleanArray(total).also { b -> members.forEach { k -> val kb = kept[k].bits; for (i in 0 until total) if (kb[i]) b[i] = true } }
            var x0 = lw; var y0 = lh; var x1 = -1; var y1 = -1
            for (i in 0 until total) if (lowBits[i]) { val x = i % lw; val y = i / lw; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
            val padX = (x1 - x0) * 0.04f + 1; val padY = (y1 - y0) * 0.04f + 1
            val pts = members.map { kept[it].point.first * sx to kept[it].point.second * sy }
            val box = listOf((x0 - padX).coerceAtLeast(0f) * sx to (y0 - padY).coerceAtLeast(0f) * sy,
                ((x1 + 1 + padX) * sx).coerceAtMost(imgW.toFloat()) to ((y1 + 1 + padY) * sy).coerceAtMost(imgH.toFloat()))
            val (masks, ious) = decode(pts + box, List(pts.size) { 1f } + listOf(2f, 3f), imgW, imgH)
            // Pick the mask that agrees best with what the scan found (IoU on the low-res grid).
            fun lowIou(m: FloatArray): Float {
                var inter = 0; var uni = 0
                for (ly in 0 until lh) {
                    val y = ((ly + 0.5f) * sy).toInt().coerceIn(0, imgH - 1)
                    for (lx in 0 until lw) {
                        val x = ((lx + 0.5f) * sx).toInt().coerceIn(0, imgW - 1)
                        val a = m[y * imgW + x] > 0f; val b = lowBits[ly * lw + lx]
                        if (a && b) inter++; if (a || b) uni++
                    }
                }
                return if (uni == 0) 0f else inter.toFloat() / uni
            }
            val scored = masks.indices.map { k -> k to lowIou(masks[k]) }
            val (bk, biou) = scored.maxBy { (k, iou) -> iou + 0.1f * ious[k] }
            val m = if (biou >= 0.5f) masks[bk] else {
                // The box answer drifted to something else: fall back to the scan's own masks.
                FloatArray(n) { -20f }.also { acc -> members.forEach { k ->
                    val c = kept[k]
                    val one = decode(listOf(c.point.first * sx to c.point.second * sy), listOf(1f), imgW, imgH, only = c.index).first[0]
                    for (i in 0 until n) if (one[i] > acc[i]) acc[i] = one[i]
                } }
            }
            for (i in 0 until n) {
                val v = m[i]
                if (v <= 0f) continue
                val pri = scanOwner[cellOf(i)]
                when {
                    pri == gi -> { if (!locked[i] || v > best[i]) { best[i] = maxOf(v, 0.5f); owner[i] = gi; locked[i] = true } }
                    pri >= 0 -> {} // the scan gave this spot to another character
                    !locked[i] && v > best[i] -> { best[i] = v; owner[i] = gi }
                }
            }
        }

        // Plain backgrounds: foreground pixels nobody claimed (a missed hand, a hem) that touch a character
        // join that character, so they don't stay behind on the (blurred) background.
        if (fg != null) {
            val maxD = maxOf(6, minOf(imgW, imgH) / 60)
            val dist = ShortArray(n) { Short.MAX_VALUE }
            val queue = IntArray(n); var qh = 0; var qt = 0
            for (i in 0 until n) if (owner[i] >= 0 && best[i] > 0f) { dist[i] = 0; queue[qt++] = i }
            fun fgAt(i: Int): Boolean {
                val lx = ((i % imgW) / sx).toInt().coerceIn(0, lw - 1); val ly = ((i / imgW) / sy).toInt().coerceIn(0, lh - 1)
                return fg[ly * lw + lx]
            }
            while (qh < qt) {
                val i = queue[qh++]; val d = dist[i] + 1
                if (d > maxD) continue
                val x = i % imgW
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < imgW - 1) i + 1 else -1, i - imgW, i + imgW)) {
                    if (j < 0 || j >= n || dist[j] <= d || (owner[j] >= 0 && best[j] > 0f) || !fgAt(j)) continue
                    dist[j] = d.toShort(); owner[j] = owner[i]; best[j] = 2f; queue[qt++] = j
                }
            }
        }

        // One mask per group from the ownership map, with island cleanup on the cheap low-res grid.
        return groups.indices.map { gi ->
            progress(0.95f + 0.05f * gi / groups.size)
            val lowOwned = BooleanArray(total) { li ->
                val y = (((li / lw) + 0.5f) * sy).toInt().coerceIn(0, imgH - 1); val x = (((li % lw) + 0.5f) * sx).toInt().coerceIn(0, imgW - 1)
                owner[y * imgW + x] == gi && best[y * imgW + x] > 0f
            }
            val (labels, sizes) = Blobs.label(lowOwned, lw, lh)
            val biggest = sizes.maxOrNull() ?: 0
            val keepLow = BooleanArray(total) { labels[it] > 0 && sizes[labels[it] - 1] >= biggest * 0.15f }
            val out = Mask(imgW, imgH)
            for (y in 0 until imgH) {
                val ly = (y / sy).toInt().coerceIn(0, lh - 1)
                for (x in 0 until imgW) {
                    val i = y * imgW + x
                    if (owner[i] != gi) continue
                    val v = best[i]
                    if (v <= -4f) continue
                    val lx = (x / sx).toInt().coerceIn(0, lw - 1)
                    var ok = keepLow[ly * lw + lx]
                    if (!ok) ok = (lx > 0 && keepLow[ly * lw + lx - 1]) || (lx < lw - 1 && keepLow[ly * lw + lx + 1]) ||
                        (ly > 0 && keepLow[(ly - 1) * lw + lx]) || (ly < lh - 1 && keepLow[(ly + 1) * lw + lx])
                    if (ok) out.a[i] = (0.5f + v * 0.12f).coerceIn(0f, 1f) // fast sigmoid-like edge
                }
            }
            out
        }.filter { it.area() > 0 }
    }

    override fun close() { embTensor?.close(); emptyMask.close(); noMask.close(); enc.close(); dec.close(); opts.close() }
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
