package com.gk.smartcompressor.compress

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.gk.smartcompressor.core.Job
import com.gk.smartcompressor.core.Level
import com.gk.smartcompressor.core.PdfMode
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * PDF compression (offline):
 *  - SMART: andar ki images ko resize + JPEG recompress, text/vector waise ka waisa (selectable).
 *  - SCAN : har page ko image bana ke naya PDF – sabse chhota, lekin text select nahi hoga.
 * Target % diya ho to Smart mode me levels badhate jaate hain jab tak target na mile.
 */
object PdfCompressor : Compressor {

    private data class Profile(val maxDim: Int, val quality: Float, val dpi: Int)

    private fun profile(level: Level) = when (level) {
        Level.HIGH_QUALITY -> Profile(2200, 0.80f, 150)
        Level.BALANCED -> Profile(1600, 0.65f, 120)
        Level.MAX_SAVING -> Profile(1100, 0.50f, 96)
    }

    override suspend fun compress(ctx: Context, job: Job, workDir: File, progress: Progress): CompressResult =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            val src = copyToFile(ctx, job.uri, File(workDir, "in_${job.id}.pdf"))
            try {
                val target = job.targetBytes()
                val out = File(workDir, "out_${job.id}.pdf")
                if (job.options.pdfMode == PdfMode.SCAN) {
                    rasterize(src, out, profile(job.options.level), progress)
                } else if (target == null) {
                    smart(src, out, profile(job.options.level), progress)
                } else {
                    val ladder = listOf(
                        Profile(2200, 0.80f, 0), Profile(1600, 0.65f, 0), Profile(1300, 0.55f, 0),
                        Profile(1100, 0.45f, 0), Profile(900, 0.38f, 0),
                    )
                    for ((i, p) in ladder.withIndex()) {
                        smart(src, out, p) { f, n -> progress((i + f) / ladder.size, "Try ${i + 1}/${ladder.size} • $n") }
                        if (out.length() <= target) break
                    }
                }
                CompressResult(out, "pdf", "application/pdf")
            } finally {
                src.delete()
            }
        }

    private suspend fun smart(src: File, out: File, p: Profile, progress: Progress) {
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val cache = IdentityHashMap<COSBase, PDImageXObject>()
            val visited = IdentityHashMap<COSBase, Boolean>()
            val total = doc.numberOfPages
            doc.pages.forEachIndexed { i, page ->
                coroutineContext.ensureActive()
                page.resources?.let { processResources(doc, it, p, cache, visited) }
                progress((i + 1f) / total, "Page ${i + 1}/$total")
            }
            // Faltu metadata hatao
            doc.documentCatalog.metadata = null
            doc.documentCatalog.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
            doc.save(out)
        }
    }

    private fun processResources(
        doc: PDDocument, res: PDResources, p: Profile,
        cache: IdentityHashMap<COSBase, PDImageXObject>, visited: IdentityHashMap<COSBase, Boolean>,
    ) {
        if (visited.put(res.cosObject, true) != null) return
        for (name in res.xObjectNames.toList()) {
            val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            when (xo) {
                is PDImageXObject -> {
                    val key = xo.cosObject
                    val replacement = cache.getOrPut(key) { recompress(doc, xo, p) ?: xo }
                    if (replacement !== xo) res.put(name, replacement)
                }
                is PDFormXObject -> xo.resources?.let { processResources(doc, it, p, cache, visited) }
            }
        }
    }

    private fun recompress(doc: PDDocument, img: PDImageXObject, p: Profile): PDImageXObject? = runCatching {
        // Masks / black-white scans / transparency wali images ko chhedna safe nahi
        if (img.isStencil || img.bitsPerComponent == 1) return null
        val cos = img.cosObject
        if (cos.containsKey(COSName.SMASK) || cos.containsKey(COSName.MASK)) return null
        val w = img.width; val h = img.height
        if (w.toLong() * h < 40_000 || w.toLong() * h > 60_000_000) return null
        val origLen = cos.length
        var bmp: Bitmap = img.image ?: return null
        val longSide = max(bmp.width, bmp.height)
        if (longSide > p.maxDim) {
            val s = p.maxDim.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        val jpeg = JPEGFactory.createFromImage(doc, bmp, p.quality)
        bmp.recycle()
        if (jpeg.cosObject.length < origLen * 0.9) jpeg else null
    }.getOrNull()

    private suspend fun rasterize(src: File, out: File, p: Profile, progress: Progress) {
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                for (i in 0 until renderer.pageCount) {
                    coroutineContext.ensureActive()
                    renderer.openPage(i).use { page ->
                        val scale = p.dpi / 72f
                        val bw = (page.width * scale).toInt().coerceIn(1, 4000)
                        val bh = (page.height * scale).toInt().coerceIn(1, 4000)
                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val pdPage = PDPage(PDRectangle(page.width.toFloat(), page.height.toFloat()))
                        doc.addPage(pdPage)
                        val img = JPEGFactory.createFromImage(doc, bmp, p.quality)
                        bmp.recycle()
                        PDPageContentStream(doc, pdPage).use { cs ->
                            cs.drawImage(img, 0f, 0f, page.width.toFloat(), page.height.toFloat())
                        }
                    }
                    progress((i + 1f) / renderer.pageCount, "Scan page ${i + 1}/${renderer.pageCount}")
                }
                doc.save(out)
            }
        }
    }
}
