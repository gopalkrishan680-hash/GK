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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DoodleTest {
    private val script = """
        Kunti ne Karna ko janam ke baad nadi me chhod diya tha.
        Adhirath aur Radha ne use apna beta maana.
        Rangbhoomi me Duryodhan ne use Ang desh ka raja banaya.
        Wahi din tha jab Karna aur Duryodhan ki dosti shuru hui.
        Aaj 85% log Karna ko sabse bada daani maante hain.
        Karna ke liye rishta khoon se nahi, ehsaan se bana tha.
    """.trimIndent()

    @Test fun whiteboard() {
        val imgs = File(System.getProperty("fs.imgs") ?: return).listFiles()?.sortedBy { it.name }?.map { it.absolutePath } ?: return
        var p = Project(id = "d", title = "Karna", script = script, seed = 4, doodle = true)
        val n = Director.analysisFor(p).chunks.size
        // last line without a picture -> handwriting layout
        p = Director.build(p.copy(lineImages = true, images = (0 until n).map { if (it == n - 1) "" else imgs[it % imgs.size] }), null)
        println("DOODLE " + p.scenes.joinToString(" | ") { "${it.type}/${it.transition}" })
        assertTrue(p.scenes.all { it.type.name.startsWith("DOODLE_") })
        assertTrue("boards too short", p.scenes.dropLast(1).all { it.durationMs >= 4000 })
        val out = File(System.getProperty("fs.out") ?: return, "doodle").apply { mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = FrameRenderer(854, 480, p, Fonts(ctx.assets), ImageStore(p.images, 1200))
        val bmp = Bitmap.createBitmap(854, 480, Bitmap.Config.ARGB_8888)
        val fps = 15
        val frames = (p.durationMs * fps / 1000).toInt()
        for (f in 0 until frames) {
            r.draw(Canvas(bmp), f * 1000L / fps)
            File(out, "f%04d.jpg".format(f)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
        println("DOODLE frames=$frames boards=${p.scenes.size} " + p.scenes.joinToString { "${it.type}:${it.lines.size}l/${it.durationMs}ms" })
    }
}
