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
 * MobileSAM (Segment Anything) on ONNX Runtime.
 * Encoder input: HWC float 0..255, longest side 1024. Decoder: SAM prompt points in that 1024 space.
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
                val t = res[0] as OnnxTensor
                val b = t.floatBuffer
                embedding = FloatArray(b.remaining()).also { b.get(it) }
            }
        }
    }

    /** Points are in scene-image pixels; labels 1 = object, 0 = background. Returns mask + predicted IoU. */
    fun segment(points: List<Pair<Float, Float>>, labels: List<Float>): Pair<Mask, Float> {
        val emb = embedding ?: error("Call setImage first")
        val n = points.size + 1 // SAM expects a padding point when no box is given
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
            "orig_im_size" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(imgH.toFloat(), imgW.toFloat())), longArrayOf(2)),
        )
        try {
            dec.run(inputs).use { res ->
                val logits = (res[0] as OnnxTensor).floatBuffer
                val iou = (res[1] as OnnxTensor).floatBuffer.get(0)
                val m = Mask(imgW, imgH)
                for (i in 0 until imgW * imgH) m.a[i] = (1.0 / (1.0 + exp(-logits.get(i).toDouble()))).toFloat()
                return m to iou
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** Automatic mode: prompts a grid of points and keeps distinct, object-sized masks. */
    fun autoSegment(cols: Int = 5, rows: Int = 4, progress: (Float) -> Unit = {}): List<Mask> {
        val kept = mutableListOf<Pair<Mask, Float>>()
        val total = imgW.toFloat() * imgH
        var k = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            progress(k++ / (rows * cols).toFloat())
            val x = imgW * (c + 0.5f) / cols; val y = imgH * (r + 0.5f) / rows
            if (kept.any { it.first.a[y.toInt() * imgW + x.toInt()] > 0.5f }) continue
            val (m, iou) = segment(listOf(x to y), listOf(1f))
            if (iou < 0.75f) continue
            val area = m.area() / total
            if (area < 0.004f || area > 0.35f) continue
            val bb = m.bounds() ?: continue
            if (bb[2] - bb[0] > imgW * 0.9f || bb[3] - bb[1] > imgH * 0.9f) continue
            val dup = kept.indexOfFirst { it.first.iou(m) > 0.6f }
            if (dup >= 0) { if (iou > kept[dup].second) kept[dup] = m to iou } else kept += m to iou
        }
        return kept.map { it.first }
    }

    override fun close() { enc.close(); dec.close(); opts.close() }
}
