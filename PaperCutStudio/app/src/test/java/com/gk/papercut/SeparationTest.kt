package com.gk.papercut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.gk.papercut.ml.Blobs
import com.gk.papercut.ml.Cutter
import com.gk.papercut.ml.LayerSeparator
import com.gk.papercut.ml.MobileSam
import com.gk.papercut.ml.PlainBackground
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/** Runs the real MobileSAM pipeline on a real image (needs -Dseg.image and -Dseg.models). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SeparationTest {
    @Test fun separateCrowdedScene() {
        val imgPath = System.getProperty("seg.image") ?: ""
        val models = File(System.getProperty("seg.models") ?: "")
        assumeTrue(File(imgPath).exists() && File(models, "sam_mask_decoder_multi.onnx").exists())
        val out = File(System.getProperty("render.out")!!).apply { mkdirs() }
        var img = BitmapFactory.decodeFile(imgPath).copy(Bitmap.Config.ARGB_8888, false)
        if (maxOf(img.width, img.height) > 1600) {
            val s = 1600f / maxOf(img.width, img.height)
            img = Bitmap.createScaledBitmap(img, (img.width * s).toInt(), (img.height * s).toInt(), true)
        }
        val w = img.width; val h = img.height
        val plain = PlainBackground.detect(img)
        println("plain background: ${plain?.let { Integer.toHexString(it) }}")
        val s = 480f / maxOf(w, h)
        val lw = (w * s).roundToInt(); val lh = (h * s).roundToInt()
        val fg = plain?.let { PlainBackground.foreground(img, it, lw, lh) }
        val t0 = System.currentTimeMillis()
        val masks = MobileSam(File(models, "mobile_sam_image_encoder.onnx"), File(models, "sam_mask_decoder_multi.onnx")).use { sam ->
            sam.setImage(img)
            sam.autoSegment(fg, lw, lh)
        }
        println("masks: ${masks.size} in ${System.currentTimeMillis() - t0} ms")

        // Contact sheet of all cut-outs + visualization.
        val cuts = masks.mapNotNull { Cutter.cut(img, it) }
        val vis = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(vis); c.drawColor(Color.rgb(40, 40, 40))
        val p = Paint().apply { alpha = 255 }
        cuts.sortedByDescending { it.bitmap.width * it.bitmap.height }.forEachIndexed { i, cut ->
            c.drawBitmap(cut.bitmap, cut.x.toFloat() + (i % 3 - 1) * 6, cut.y.toFloat() + (i % 2) * 6, p)
        }
        vis.compress(Bitmap.CompressFormat.PNG, 100, File(out, "sep_cutouts.png").outputStream())
        val sheetCols = 5
        val cell = 300
        val sheet = Bitmap.createBitmap(cell * sheetCols, cell * ((cuts.size + sheetCols - 1) / sheetCols).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet); sc.drawColor(Color.rgb(200, 220, 200))
        cuts.forEachIndexed { i, cut ->
            val k = minOf(cell * 0.9f / cut.bitmap.width, cell * 0.9f / cut.bitmap.height)
            sc.save(); sc.translate((i % sheetCols) * cell + 15f, (i / sheetCols) * cell + 15f); sc.scale(k, k)
            sc.drawBitmap(cut.bitmap, 0f, 0f, null); sc.restore()
        }
        sheet.compress(Bitmap.CompressFormat.PNG, 100, File(out, "sep_sheet.png").outputStream())
        // Full scene: background plate + animated layers, rendered like the app does.
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val repo = com.gk.papercut.data.ProjectRepository(ctx)
        val sep = LayerSeparator(ctx, com.gk.papercut.ml.ModelManager(ctx))
        val bg = sep.background(img, masks)
        bg.compress(Bitmap.CompressFormat.PNG, 100, File(out, "sep_background.png").outputStream())
        val p0 = repo.create("court")
        repo.saveBitmap(p0.id, "img.png", img); repo.saveBitmap(p0.id, "bg.png", bg)
        val anims = com.gk.papercut.data.EnterAnim.entries.filter { it != com.gk.papercut.data.EnterAnim.NONE }
        val heroIds = cuts.filter { it.y > 4 }.sortedByDescending { it.bitmap.width * it.bitmap.height }.take(3).toSet()
        val layers = cuts.mapIndexed { i, cut ->
            val setPiece = cut.y <= 4 || (cut.y + cut.bitmap.height >= h - 4 && cut.bitmap.width > w * 0.3f)
            com.gk.papercut.data.Layer("L$i", repo.saveBitmap(p0.id, "l$i.png", cut.bitmap), cut.x.toFloat(), cut.y.toFloat(),
                cut.bitmap.width.toFloat(), cut.bitmap.height.toFloat(),
                enter = if (setPiece || cut !in heroIds) com.gk.papercut.data.EnterAnim.NONE else anims[i % anims.size],
                idle = if (setPiece) com.gk.papercut.data.IdleAnim.NONE else com.gk.papercut.data.IdleAnim.BOB,
                role = if (setPiece) "set" else if (cut in heroIds) "hero" else "crowd")
        }.sortedByDescending { it.w * it.h }
        val text = "Raja ne gusse se dekha, aur sainik rani ko darbar mein kheench laaye. Sab darbari chup baithe rahe."
        val scenes = com.gk.papercut.sync.SyncEngine.syncByReadingSpeed(listOf(
            com.gk.papercut.data.Scene("A", text, imageFile = "img.png", backgroundFile = "bg.png", imageW = w, imageH = h, layers = layers)))
        val project = repo.save(p0.copy(scenes = scenes, audioDuration = scenes.last().end))
        val r = com.gk.papercut.render.FrameRenderer(project, repo)
        val frame = Bitmap.createBitmap(r.outW, r.outH, Bitmap.Config.ARGB_8888)
        // Camera smoothness: no single-frame jump when a new shot starts (captions/stop-motion off here).
        val smooth = com.gk.papercut.render.FrameRenderer(project.copy(captions = false, stopMotion = false), repo)
        val shotFrames = (3.0 * 30).toInt()..(scenes[0].duration * 30).toInt().coerceAtMost(210)
        var prevPx: IntArray? = null
        val diffs = ArrayList<Double>()
        val f2 = Bitmap.createBitmap(smooth.outW, smooth.outH, Bitmap.Config.ARGB_8888)
        val small = IntArray(160 * 90)
        for (fi in shotFrames) {
            smooth.render(Canvas(f2), fi / 30.0)
            Bitmap.createScaledBitmap(f2, 160, 90, true).getPixels(small, 0, 160, 0, 0, 160, 90)
            prevPx?.let { pp -> diffs += small.indices.sumOf { kotlin.math.abs((small[it] and 255) - (pp[it] and 255)).toDouble() } / small.size }
            prevPx = small.copyOf()
        }
        val sorted = diffs.sorted(); val median = sorted[sorted.size / 2]
        println("camera diffs: max=%.2f median=%.2f".format(sorted.last(), median))
        diffs.forEachIndexed { i, d -> if (i > 0 && i < diffs.size - 1) org.junit.Assert.assertTrue("jump of %.1f at frame %d".format(d, shotFrames.first + i + 1), d < 3 * maxOf(diffs[i - 1], diffs[i + 1]) + 2.0) }
        for (t in listOf(0.2, 0.6, 1.0, 1.4, 1.9, 2.4)) {
            r.render(Canvas(frame), t)
            frame.compress(Bitmap.CompressFormat.PNG, 100, File(out, "sep_frame_%.1f.png".format(t)).outputStream())
        }
    }
}
