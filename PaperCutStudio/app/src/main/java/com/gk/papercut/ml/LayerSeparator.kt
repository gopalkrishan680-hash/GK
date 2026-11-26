package com.gk.papercut.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

/**
 * Splits a scene image into cut-outs + a clean background.
 *  - MobileSAM (if downloaded) scans the whole picture: best for crowded scenes where things touch.
 *  - ML Kit Subject Segmentation adds any main subject SAM missed (or does the job alone without SAM).
 *  - Plain backgrounds (white etc.) are detected: free foreground map + perfect background plate.
 *  - Otherwise the holes are repainted with LaMa (if downloaded) or a quick fill.
 */
class LayerSeparator(private val context: Context, private val models: ModelManager) {

    class Result(val cutOuts: List<CutOut>, val background: Bitmap)

    private fun segmenter(): SubjectSegmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build())
            .build()
    )

    /** ML Kit's model is delivered by Google Play services; make sure it is installed before use. */
    private suspend fun ensureMlKitModule(client: SubjectSegmenter, progress: (String) -> Unit) {
        val installer = ModuleInstall.getClient(context)
        if (installer.areModulesAvailable(client).await().areModulesAvailable()) return
        progress("Downloading ML Kit model (one time, needs internet)…")
        installer.installModules(ModuleInstallRequest.newBuilder().addApi(client).build()).await()
        repeat(90) {
            if (installer.areModulesAvailable(client).await().areModulesAvailable()) return
            delay(1000)
        }
        error("ML Kit model is still downloading – try again in a minute")
    }

    private suspend fun mlKitSubjects(img: Bitmap, progress: (String) -> Unit): List<CutOut> {
        val client = segmenter()
        try {
            ensureMlKitModule(client, progress)
            val res = client.process(InputImage.fromBitmap(img, 0)).await()
            return res.subjects.mapNotNull { s -> s.bitmap?.let { CutOut(it, s.startX, s.startY) } }
        } finally {
            client.close()
        }
    }

    suspend fun separate(img: Bitmap, useSam: Boolean, progress: (String) -> Unit): Result {
        val w = img.width; val h = img.height
        val masks = mutableListOf<Mask>()
        val cuts = mutableListOf<CutOut>()
        val plain = PlainBackground.detect(img)
        fun add(m: Mask): Boolean {
            if (masks.any { it.iou(m) > 0.4f || covered(m, it) }) return false
            val c = Cutter.cut(img, m) ?: return false
            masks += m; cuts += c; return true
        }

        if (useSam && models.samReady) {
            progress("Scanning every element (MobileSAM)…")
            val s = 480f / maxOf(w, h)
            val lw = (w * s).roundToInt(); val lh = (h * s).roundToInt()
            val fg = plain?.let { PlainBackground.foreground(img, it, lw, lh) }
            MobileSam(models.file(OfflineModel.SAM_ENCODER), models.file(OfflineModel.SAM_DECODER)).use { sam ->
                sam.setImage(img)
                sam.autoSegment(fg, lw, lh) { p -> progress("MobileSAM scan ${(p * 100).toInt()}%") }.forEach { add(it) }
            }
        }

        progress("Finding main subjects (ML Kit)…")
        runCatching { mlKitSubjects(img, progress) }
            .onFailure { progress("ML Kit unavailable: ${it.message}") }
            .getOrNull()?.forEach { c ->
                val m = Mask.fromAlpha(w, h, c.bitmap, c.x, c.y)
                // When SAM already split the scene, ignore ML Kit's "everything together" blob.
                if (cuts.isNotEmpty() && m.area() > w * h * 0.35f) return@forEach
                add(m)
            }

        if (cuts.isEmpty() && plain != null) {
            progress("Cutting shapes from the plain background…")
            val fg = PlainBackground.foreground(img, plain, w, h)
            Blobs.find(fg, w, h, (w * h * 0.003f).toInt()).forEach { b ->
                add(Mask(w, h, FloatArray(w * h) { if (b[it]) 1f else 0f }))
            }
        }

        progress(
            when {
                plain != null -> "Building background…"
                models.lamaReady -> "Repainting background (LaMa)…"
                else -> "Filling background…"
            }
        )
        return Result(cuts, background(img, masks))
    }

    fun background(img: Bitmap, masks: List<Mask>): Bitmap {
        if (masks.isEmpty()) return img
        val union = Mask(img.width, img.height)
        masks.forEach { union.union(it) }
        val holes = union.dilated(maxOf(4, minOf(img.width, img.height) / 80))
        val plain = PlainBackground.detect(img)
        val holeFrac = holes.area(0.3f) / (img.width.toFloat() * img.height)
        // Plain backdrop -> its colour. A crowd covering much of the picture -> clean paper "stage" in the
        // picture's own paper colour (no AI can invent that much hidden background; it only makes smudges).
        val fillColor = plain ?: if (holeFrac > 0.35f) paperColor(img, holes) else null
        if (fillColor != null) {
            val px = IntArray(img.width * img.height); img.getPixels(px, 0, img.width, 0, 0, img.width, img.height)
            val r0 = (fillColor shr 16) and 255; val g0 = (fillColor shr 8) and 255; val b0 = fillColor and 255
            for (i in px.indices) {
                val m = holes.a[i].coerceIn(0f, 1f)
                if (m <= 0f) continue
                val p = px[i]
                fun mix(c: Int, f: Int) = (c * (1 - m) + f * m).toInt()
                px[i] = (0xFF shl 24) or (mix((p shr 16) and 255, r0) shl 16) or (mix((p shr 8) and 255, g0) shl 8) or mix(p and 255, b0)
            }
            return Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
        }
        return Inpainter.inpaint(img, holes, if (models.lamaReady) models.file(OfflineModel.LAMA) else null)
    }

    /** The picture's own paper colour: median of light, low-saturation pixels outside the holes; else cream. */
    private fun paperColor(img: Bitmap, holes: Mask): Int {
        val w = img.width; val h = img.height
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        var i = 0
        while (i < w * h) {
            if (holes.a[i] < 0.1f) {
                val p = img.getPixel(i % w, i / w)
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
                if (mx > 200 && mx - mn < 40) { rs += r; gs += g; bs += b }
            }
            i += 7
        }
        if (rs.size < (w * h / 7) * 0.03f) return android.graphics.Color.rgb(246, 238, 220)
        rs.sort(); gs.sort(); bs.sort()
        return android.graphics.Color.rgb(rs[rs.size / 2], gs[gs.size / 2], bs[bs.size / 2])
    }

    /** True if most of [m] lies inside [other]. */
    private fun covered(m: Mask, other: Mask): Boolean {
        var inside = 0; var total = 0
        var i = 0
        while (i < m.a.size) {
            if (m.a[i] > 0.5f) { total++; if (other.a[i] > 0.5f) inside++ }
            i += 3
        }
        return total > 0 && inside > total * 0.6f
    }
}
