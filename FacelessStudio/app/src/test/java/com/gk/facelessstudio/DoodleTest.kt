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
        val out = File(System.getProperty("fs.out") ?: return, "doodle").apply { mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = FrameRenderer(854, 480, p, Fonts(ctx.assets), ImageStore(p.images, 1200))
        val bmp = Bitmap.createBitmap(854, 480, Bitmap.Config.ARGB_8888)
        p.scenes.forEachIndexed { i, s ->
            for ((tag, f) in listOf("a" to 0.18f, "b" to 0.42f, "c" to 0.95f)) {
                r.draw(Canvas(bmp), s.startMs + (s.durationMs * f).toLong())
                File(out, "%02d${tag}_${s.type}.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
