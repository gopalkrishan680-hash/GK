package com.gk.facelessstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.gk.facelessstudio.engine.Director
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.render.Fonts
import com.gk.facelessstudio.render.FrameRenderer
import com.gk.facelessstudio.render.ImageStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Whiteboards must stay white (regression: boards turned black with white-background pictures). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BoardColorTest {
    @Test fun boardsStayWhite() {
        val imgs = File(System.getProperty("fs.imgs") ?: return).listFiles()?.sortedBy { it.name }?.map { it.absolutePath } ?: return
        val script = (1..24).joinToString(". ") { "Karna ne line number $it me Duryodhan ka saath diya" } + "."
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (seed in 1L..8L) {
            var p = Project(id = "b$seed", script = script, seed = seed, doodle = true, clean = seed % 2 == 0L)
            val n = Director.analysisFor(p).chunks.size
            p = Director.build(p.copy(lineImages = true, images = (0 until n).map { imgs[it % imgs.size] }), null)
            val r = FrameRenderer(640, 360, p, Fonts(ctx.assets), ImageStore(p.images, 900))
            val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            p.scenes.forEachIndexed { i, s ->
                for (f in listOf(0.3f, 0.7f, 0.97f)) {
                    r.draw(Canvas(bmp), s.startMs + (s.durationMs * f).toLong())
                    // no pixel of the board may become transparent (shows as black in the video)
                    var minAlpha = 255
                    for (gx in 0 until 10) for (gy in 0 until 10) minAlpha = minOf(minAlpha, bmp.getPixel(gx * 63 + 5, gy * 35 + 5) ushr 24)
                    val c = bmp.getPixel(8, 180)      // left edge of the board, away from pictures
                    val lum = if (minAlpha < 255) 0 else ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3
                    if (lum < 150) {
                        println("DARK seed=$seed scene=$i ${s.type} look=${s.look} tr=${s.transition} f=$f lum=$lum")
                        File(System.getProperty("fs.out") ?: ".", "dark_${seed}_$i.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    assertTrue("board went dark: seed=$seed scene=$i ${s.type} look=${s.look}", lum >= 150)
                }
            }
        }
    }
}
