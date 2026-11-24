package com.gk.facelessstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.gk.facelessstudio.engine.Director
import com.gk.facelessstudio.model.Aspect
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.SceneType
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
class CleanTest {
    private val script = """
        Kya aap jaante ho, Karna ne Duryodhan ka saath kabhi kyun nahi chhoda?
        Kunti ne Karna ko janam ke baad nadi me chhod diya tha.
        Adhirath aur Radha ne use apna beta maana.
        Rangbhoomi me Duryodhan ne use Ang desh ka raja banaya.
        Aaj 85% log Karna ko sabse bada daani maante hain.
        Lekin Karna ne mana kar diya.
    """.trimIndent()

    @Test fun cleanBoards() = run(Aspect.PORTRAIT, 405, 720, "clean")
    @Test fun cleanBoardsLandscape() = run(Aspect.LANDSCAPE, 640, 360, "clean_land")

    private fun run(aspect: Aspect, w: Int, h: Int, dir: String) {
        val imgs = File(System.getProperty("fs.imgs") ?: return).listFiles()?.sortedBy { it.name }?.map { it.absolutePath } ?: return
        var p = Project(id = "c", script = script, seed = 2, doodle = true, clean = true, aspect = aspect)
        val n = Director.analysisFor(p).chunks.size
        p = Director.build(p.copy(lineImages = true, images = (0 until n).map { imgs[it % imgs.size] }), null)
        println("CLEAN " + p.scenes.joinToString { "${it.type}:${it.lines.size}l/${it.durationMs}ms/${it.transition}" })
        assertTrue(p.scenes.all { it.type == SceneType.DOODLE_STACK })
        assertTrue(p.scenes.dropLast(1).all { it.durationMs >= 4500 })
        assertTrue(p.scenes.all { it.lines.size <= 2 })
        val out = File(System.getProperty("fs.out") ?: return, dir).apply { mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = FrameRenderer(w, h, p, Fonts(ctx.assets), ImageStore(p.images, 900))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fps = 15
        for (f in 0 until (p.durationMs * fps / 1000).toInt()) {
            r.draw(Canvas(bmp), f * 1000L / fps)
            File(out, "f%04d.jpg".format(f)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
    }
}
