package com.gk.facelessstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.gk.facelessstudio.engine.Director
import com.gk.facelessstudio.model.Backdrop
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

/** With one picture per line, every scene must show its own picture (big, or behind the text). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScriptVisualsTest {
    private val script = """
        Kya aap jaante ho, Karna ne Duryodhan ka saath kabhi kyun nahi chhoda?
        Kunti ne use janam ke baad nadi me chhod diya tha.
        Adhirath aur Radha ne use apna beta maana.
        Rangbhoomi me Duryodhan ne use Ang desh ka raja banaya.
        Wahi din tha jab Karna aur Duryodhan ki dosti shuru hui.
        Kurukshetra me Karna ne aakhri saans tak dosti nibhayi.
        Udyog Parva me Krishna khud Karna ke paas raja banne ka offer lekar aaye.
        Unhone Karna ko ek bada raaz bataya.
        "Tum Kunti ke sabse bade bete ho."
        Paanchon Pandav tumhare charan chhuenge.
        Lekin Karna ne mana kar diya.
        Karna ke liye rishta khoon se nahi, ehsaan se bana tha.
        Poori kahani channel par.
    """.trimIndent()

    @Test fun everySceneShowsItsPicture() {
        val dir = File(System.getProperty("fs.imgs") ?: return)
        val imgs = dir.listFiles()?.sortedBy { it.name }?.map { it.absolutePath } ?: return
        var p = Project(id = "k", title = "Karna", script = script, seed = 21, characters = "Karna: warrior\nKrishna: blue skin")
        val n = Director.analysisFor(p).chunks.size
        p = Director.build(p.copy(lineImages = true, images = (0 until n).map { imgs[it % imgs.size] }), null)
        println("VISUALS " + p.scenes.joinToString(" | ") { "${it.type}/${it.bg} img=${it.imageIndex} em=${it.emphasis}" })
        p.scenes.filter { it.type != com.gk.facelessstudio.model.SceneType.OUTRO }.forEach { s ->
            assertTrue("${s.type} hides its picture", s.imageIndex >= 0 && (s.type.needsImage || s.bg == Backdrop.IMAGE ||
                s.type in setOf(com.gk.facelessstudio.model.SceneType.KEY_POINTS, com.gk.facelessstudio.model.SceneType.DATE_STAMP)))
        }
        val out = File(System.getProperty("fs.out") ?: return, "visuals").apply { mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = FrameRenderer(854, 480, p, Fonts(ctx.assets), ImageStore(p.images, 1200))
        val bmp = Bitmap.createBitmap(854, 480, Bitmap.Config.ARGB_8888)
        p.scenes.forEachIndexed { i, s ->
            r.draw(Canvas(bmp), s.startMs + (s.durationMs * 0.8f).toLong())
            File(out, "%02d_${s.type}.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
