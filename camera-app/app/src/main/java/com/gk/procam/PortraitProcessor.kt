package com.gk.procam

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ModelState { CHECKING, DOWNLOADING, READY, FAILED }

/**
 * AI portrait ("depth") effect.
 * Uses Google's on-device subject segmentation model (downloaded once through Google Play
 * services), then renders a lens-like background blur:
 *  - blur is done in linear light so bright points bloom into bokeh discs,
 *  - the blur is mask-weighted, so the subject's colours never bleed into the background,
 *  - the strength follows the chosen aperture (f/1.4 strong ... f/16 almost none).
 */
class PortraitProcessor(private val context: Context) {
    private val segmenter by lazy {
        runCatching { com.google.mlkit.common.MlKit.initialize(context.applicationContext) }
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }

    var state by mutableStateOf(ModelState.CHECKING)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    val ready get() = state == ModelState.READY

    fun ensureModel() {
        try { ensureModelInternal() } catch (e: Throwable) { android.util.Log.e("ProCam", "segmenter init failed", e); state = ModelState.FAILED }
    }

    private fun ensureModelInternal() {
        val client = ModuleInstall.getClient(context)
        state = ModelState.CHECKING
        client.areModulesAvailable(segmenter)
            .addOnSuccessListener { if (it.areModulesAvailable()) state = ModelState.READY else download() }
            .addOnFailureListener { state = ModelState.FAILED }
    }

    fun download() {
        try { downloadInternal() } catch (e: Throwable) { state = ModelState.FAILED }
    }

    private fun downloadInternal() {
        val client = ModuleInstall.getClient(context)
        state = ModelState.DOWNLOADING
        progress = 0f
        val listener = InstallStatusListener { update ->
            update.progressInfo?.let {
                if (it.totalBytesToDownload > 0) progress = it.bytesDownloaded.toFloat() / it.totalBytesToDownload
            }
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> state = ModelState.READY
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> state = ModelState.FAILED
                else -> {}
            }
        }
        val request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build()
        client.installModules(request)
            .addOnSuccessListener { if (it.areModulesAlreadyInstalled()) state = ModelState.READY }
            .addOnFailureListener { state = ModelState.FAILED }
    }

    /** Returns null when no subject was found. [src] must be upright. */
    suspend fun apply(src: Bitmap, fNumber: Float): Bitmap? {
        val w = src.width
        val h = src.height

        // 1. Segmentation at ~1280 px.
        val s = 1280f / max(w, h)
        val mw = (w * s).roundToInt()
        val mh = (h * s).roundToInt()
        val small = Bitmap.createScaledBitmap(src, mw, mh, true)
        val result = segmenter.process(InputImage.fromBitmap(small, 0)).await()
        val fb = result.foregroundConfidenceMask ?: return null
        if (small !== src) small.recycle()
        val mask = FloatArray(mw * mh)
        fb.rewind()
        fb.get(mask, 0, min(mask.size, fb.remaining()))
        if (mask.count { it > 0.5f } < mask.size / 100) return null

        // 2. Refine: tighten the transition, then feather slightly.
        for (i in mask.indices) {
            val t = ((mask[i] - 0.30f) / 0.40f).coerceIn(0f, 1f)
            mask[i] = t * t * (3f - 2f * t)
        }
        Processing.boxBlur(mask, mw, mh, 1, 2)

        // 3. Background plane at 1/4 resolution, linear light, mask-weighted.
        val ds = 4
        val bw = max(1, w / ds)
        val bh = max(1, h / ds)
        val bg = Bitmap.createScaledBitmap(src, bw, bh, true)
        val bpx = IntArray(bw * bh)
        bg.getPixels(bpx, 0, bw, 0, 0, bw, bh)
        bg.recycle()
        val lin = Processing.gammaToLinear
        val pr = FloatArray(bw * bh)
        val pg = FloatArray(bw * bh)
        val pb = FloatArray(bw * bh)
        val pw = FloatArray(bw * bh)
        Processing.runParallel(bh) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until bw) {
                val i = y * bw + x
                val m = sampleMask(mask, mw, mh, (x + 0.5f) / bw, (y + 0.5f) / bh)
                val wt = (1f - m).let { it * it }
                val p = bpx[i]
                var r = lin[(p shr 16) and 0xFF]
                var g = lin[(p shr 8) and 0xFF]
                var b = lin[p and 0xFF]
                // Bokeh: boost highlights so they bloom into bright discs.
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                if (l > 0.55f) {
                    val boost = 1f + 6f * ((l - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    r *= boost; g *= boost; b *= boost
                }
                pr[i] = r * wt; pg[i] = g * wt; pb[i] = b * wt; pw[i] = wt
            }
        }
        val radius = ((38f / fNumber) * bw / 1000f).roundToInt().coerceIn(1, 60)
        Processing.boxBlur(pr, bw, bh, radius)
        Processing.boxBlur(pg, bw, bh, radius)
        Processing.boxBlur(pb, bw, bh, radius)
        Processing.boxBlur(pw, bw, bh, radius)
        Processing.runParallel(bh) { y0, y1 ->
            for (i in y0 * bw until y1 * bw) {
                val wt = pw[i]
                if (wt > 1e-4f) {
                    val inv = 1f / wt
                    // Compress the boosted highlights back (soft clip) after blurring.
                    pr[i] = softClip(pr[i] * inv); pg[i] = softClip(pg[i] * inv); pb[i] = softClip(pb[i] * inv)
                } else {
                    val p = bpx[i]
                    pr[i] = lin[(p shr 16) and 0xFF]; pg[i] = lin[(p shr 8) and 0xFF]; pb[i] = lin[p and 0xFF]
                }
            }
        }

        // 4. Composite at full resolution.
        val out = IntArray(w * h)
        src.getPixels(out, 0, w, 0, 0, w, h)
        Processing.runParallel(h) { y0, y1 ->
            for (y in y0 until y1) {
                val v = (y + 0.5f) / h
                for (x in 0 until w) {
                    val u = (x + 0.5f) / w
                    val m = sampleMask(mask, mw, mh, u, v)
                    if (m >= 0.995f) continue
                    val i = y * w + x
                    val p = out[i]
                    val bx = u * bw - 0.5f
                    val by = v * bh - 0.5f
                    val br = bilinear(pr, bw, bh, bx, by)
                    val bgc = bilinear(pg, bw, bh, bx, by)
                    val bb = bilinear(pb, bw, bh, bx, by)
                    val fr = lin[(p shr 16) and 0xFF]
                    val fg = lin[(p shr 8) and 0xFF]
                    val fbv = lin[p and 0xFF]
                    val r = Processing.linearToByte(fr * m + br * (1f - m))
                    val g = Processing.linearToByte(fg * m + bgc * (1f - m))
                    val b = Processing.linearToByte(fbv * m + bb * (1f - m))
                    out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun softClip(v: Float): Float = if (v <= 0.8f) v else 0.8f + 0.2f * (1f - 1f / (1f + (v - 0.8f) * 5f))

    private fun sampleMask(m: FloatArray, mw: Int, mh: Int, u: Float, v: Float): Float =
        bilinear(m, mw, mh, u * mw - 0.5f, v * mh - 0.5f)

    private fun bilinear(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = x.coerceIn(0f, (w - 1).toFloat())
        val yc = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xc.toInt()
        val y0 = yc.toInt()
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val fx = xc - x0
        val fy = yc - y0
        val top = a[y0 * w + x0] * (1 - fx) + a[y0 * w + x1] * fx
        val bot = a[y1 * w + x0] * (1 - fx) + a[y1 * w + x1] * fx
        return top * (1 - fy) + bot * fy
    }
}
