package com.gk.papercut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gk.papercut.ml.Cutter
import com.gk.papercut.ml.Inpainter
import com.gk.papercut.ml.Mask
import com.gk.papercut.ml.MobileSam
import com.gk.papercut.ml.PlainBackground
import com.gk.papercut.ml.GridSplitter
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/** Where does the time go? Prints a timing for each step of the pipeline. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SpeedTest {
    private inline fun <T> time(label: String, block: () -> T): T {
        val t0 = System.nanoTime(); val r = block()
        println("TIME %-28s %6d ms".format(label, (System.nanoTime() - t0) / 1_000_000)); return r
    }

    @Test fun pipelineTimings() {
        val models = File(System.getProperty("seg.models") ?: "")
        val imgF = File(System.getProperty("seg.image") ?: "")
        assumeTrue(imgF.exists() && File(models, "sam_mask_decoder_multi.onnx").exists())
        val src = BitmapFactory.decodeFile(imgF.path).copy(Bitmap.Config.ARGB_8888, false)
        // A 2x2 grid of the picture at ChatGPT size.
        val grid = Bitmap.createBitmap(1536, 1024, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(grid).apply {
            drawColor(android.graphics.Color.WHITE)
            for (i in 0 until 4) drawBitmap(src, null, android.graphics.Rect((i % 2) * 772, (i / 2) * 516, (i % 2) * 772 + 764, (i / 2) * 516 + 508), null)
        }
        val cells = time("grid split 2x2") { GridSplitter.split(grid, 2, 2) }
        val img = cells[0].bitmap!!
        val w = img.width; val h = img.height
        val plain = time("plain detect") { PlainBackground.detect(img) }
        val s = 480f / maxOf(w, h); val lw = (w * s).roundToInt(); val lh = (h * s).roundToInt()
        val fg = time("foreground map") { plain?.let { PlainBackground.foreground(img, it, lw, lh) } }
        val sam = time("SAM load sessions") { MobileSam(File(models, "mobile_sam_image_encoder.onnx"), File(models, "sam_mask_decoder_multi.onnx")) }
        time("SAM encoder") { sam.setImage(img) }
        val t0 = System.nanoTime()
        val masks = time("SAM auto (grid+full res)") { sam.autoSegment(fg, lw, lh) }
        println("TIME   decoder runs: %d calls, %d ms; low-res stage until groups: %d ms".format(sam.stats[1], sam.stats[0] / 1_000_000, (sam.stats[2] - t0) / 1_000_000))
        val cuts = time("cut ${masks.size} layers") { masks.mapNotNull { Cutter.cut(img, it) } }
        val union = Mask(w, h); masks.forEach { union.union(it) }
        val holes = time("dilate holes") { union.dilated(maxOf(4, minOf(w, h) / 80)) }
        time("push-pull fill") { Inpainter.pushPull(img, holes) }
        val lama = File(models, "lama_fp32.onnx")
        if (lama.exists()) time("LaMa (load+run)") { Inpainter.inpaint(img, holes, lama) }
        sam.close()
        println("cells ${cells.size}, layers ${cuts.size}")

        // Batch like the app's background processor: models loaded once for all 4 panels.
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val mdir = File(ctx.filesDir, "models").apply { mkdirs() }
        listOf("mobile_sam_image_encoder.onnx", "sam_mask_decoder_multi.onnx", "lama_fp32.onnx").forEach { n ->
            val f = File(models, n); if (f.exists()) f.copyTo(File(mdir, n), overwrite = true)
        }
        val sep = com.gk.papercut.ml.LayerSeparator(ctx, com.gk.papercut.ml.ModelManager(ctx))
        val b0 = System.nanoTime()
        val results = cells.mapNotNull { it.bitmap }.map { panel ->
            kotlinx.coroutines.runBlocking { sep.separate(panel, useSam = true, progress = {}, useLama = false) } to panel
        }
        val tSep = (System.nanoTime() - b0) / 1_000_000
        sep.releaseSam()
        val b1 = System.nanoTime()
        results.filter { it.first.needsLama }.forEach { (r, panel) ->
            val ms = r.cutOuts.map { c -> Mask.fromAlpha(panel.width, panel.height, c.bitmap, c.x, c.y) }
            sep.background(panel, ms)
        }
        val tLama = (System.nanoTime() - b1) / 1_000_000
        sep.release()
        println("TIME BATCH 4 scenes: separate %d ms (%.1f s/scene), LaMa phase %d ms for %d scenes".format(
            tSep, tSep / 4000.0, tLama, results.count { it.first.needsLama }))
    }
}
