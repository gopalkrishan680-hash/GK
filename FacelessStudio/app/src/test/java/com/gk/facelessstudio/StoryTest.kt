package com.gk.facelessstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.gk.facelessstudio.engine.Director
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
class StoryTest {
    private val script = """
        Kya aap jaante ho, Karna ne Duryodhan ka saath kabhi kyun nahi chhoda?
        Udyog Parva me Krishna khud Karna ke paas raja banne ka offer lekar aaye.
        Unhone Karna ko ek bada raaz bataya.
        "Tum Kunti ke sabse bade bete ho."
        Paanchon Pandav aur Krishna tumhare charan chhuenge.
        Tum raja banoge.
        Socho, jise sab suta-putra kehte the, use poora rajya mil raha tha.
        Lekin Karna ne mana kar diya.
        Kunti ne use janam ke baad chhod diya tha.
        Adhirath aur Radha ne use apna beta maana.
        Aur Duryodhan ne use izzat di, jab sab use apmaan kar rahe the.
        Karna ke liye rishta khoon se nahi, ehsaan se bana tha.
        Poori kahani channel par.
    """.trimIndent()

    @Test fun storyRender() {
        val imgs = File(System.getProperty("fs.imgs") ?: return).listFiles()?.sortedBy { it.name }?.map { it.absolutePath } ?: return
        val out = File(System.getProperty("fs.out") ?: return, "story").apply { mkdirs() }
        var p = Project(id = "s", title = "Karna ka faisla", script = script, storyMode = true, themeId = "crimson", seed = 11,
            characters = "Karna: young warrior, golden armour\nKrishna: blue skin\nDuryodhan: purple robes")
        val n = Director.analysisFor(p).chunks.size
        // one picture per line like the grid workflow; leave one slot empty to test the fallback
        p = p.copy(lineImages = true, images = (0 until n).map { if (it == 5) "" else imgs[it % imgs.size] })
        p = Director.build(p, null)
        println("STORY: " + p.scenes.joinToString(" | ") { "${it.type}/${it.transition}:'${it.title}' em=${it.emphasis}" })
        val bad = setOf(SceneType.PHONE, SceneType.KEY_POINTS, SceneType.CHECKLIST, SceneType.MINDMAP, SceneType.IMAGE_BANNER, SceneType.SPOTLIGHT)
        assertTrue(p.scenes.none { it.type in bad })
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = FrameRenderer(854, 480, p, Fonts(ctx.assets), ImageStore(p.images, 1200))
        val bmp = Bitmap.createBitmap(854, 480, Bitmap.Config.ARGB_8888)
        p.scenes.forEachIndexed { i, s ->
            r.draw(Canvas(bmp), s.startMs + (s.durationMs * 0.75f).toLong())
            File(out, "s%02d_${s.type}.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
