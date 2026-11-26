package com.gk.papercut.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

/**
 * Splits a scene image into cut-outs + a clean background.
 *  1. ML Kit Subject Segmentation (fast, on-device) finds the main subjects.
 *  2. MobileSAM (if downloaded) scans the picture for any extra objects ML Kit missed.
 *  3. LaMa (if downloaded) paints the background where the cut-outs were.
 */
class LayerSeparator(private val models: ModelManager) {

    class Result(val cutOuts: List<CutOut>, val background: Bitmap)

    suspend fun mlKitSubjects(img: Bitmap): List<CutOut> {
        val opts = SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build()
            )
            .build()
        val client = SubjectSegmentation.getClient(opts)
        try {
            val res = client.process(InputImage.fromBitmap(img, 0)).await()
            return res.subjects.mapNotNull { s ->
                val b = s.bitmap ?: return@mapNotNull null
                CutOut(b, s.startX, s.startY)
            }
        } finally {
            client.close()
        }
    }

    suspend fun separate(img: Bitmap, useSam: Boolean, progress: (String) -> Unit): Result {
        val w = img.width; val h = img.height
        val masks = mutableListOf<Mask>()
        val cuts = mutableListOf<CutOut>()

        progress("Finding subjects (ML Kit)…")
        val mlkit = runCatching { mlKitSubjects(img) }.getOrElse {
            progress("ML Kit unavailable (${it.message}); using MobileSAM only"); emptyList()
        }
        for (c in mlkit) {
            val m = Mask.fromAlpha(w, h, c.bitmap, c.x, c.y)
            if (m.area() < 400) continue
            masks += m; cuts += c
        }

        if (useSam && models.samReady) {
            progress("Scanning for more elements (MobileSAM)…")
            MobileSam(models.file(OfflineModel.SAM_ENCODER), models.file(OfflineModel.SAM_DECODER)).use { sam ->
                sam.setImage(img)
                val extra = sam.autoSegment { p -> progress("MobileSAM scan ${(p * 100).toInt()}%") }
                for (m in extra) {
                    // Skip anything already covered by ML Kit.
                    if (masks.any { it.iou(m) > 0.4f || covered(m, it) }) continue
                    Cutter.cut(img, m)?.let { masks += m; cuts += it }
                }
            }
        }

        progress(if (models.lamaReady) "Repainting background (LaMa)…" else "Filling background…")
        val background = background(img, masks)
        return Result(cuts, background)
    }

    fun background(img: Bitmap, masks: List<Mask>): Bitmap {
        if (masks.isEmpty()) return img
        val union = Mask(img.width, img.height)
        masks.forEach { union.union(it) }
        val holes = union.dilated(maxOf(4, minOf(img.width, img.height) / 80))
        return Inpainter.inpaint(img, holes, if (models.lamaReady) models.file(OfflineModel.LAMA) else null)
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
