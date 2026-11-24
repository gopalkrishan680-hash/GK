package com.gk.facelessstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.gk.facelessstudio.engine.Director
import com.gk.facelessstudio.model.Backdrop
import com.gk.facelessstudio.model.CamMove
import com.gk.facelessstudio.model.Deco
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.Scene
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.model.TextFx
import com.gk.facelessstudio.model.Transition
import com.gk.facelessstudio.render.Fonts
import com.gk.facelessstudio.render.FrameRenderer
import com.gk.facelessstudio.render.ImageStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GalleryTest {
    private val texts = mapOf(
        SceneType.GAUGE to "Aaj 72% log mobile par news padhte hain",
        SceneType.STEPS to "Success ke steps: plan, mehnat, sabr aur consistency",
        SceneType.STICKY_NOTES to "Yaad rakho: focus, discipline aur health",
        SceneType.CHAT_CARD to "Einstein said \"Imagination is more important than knowledge\"",
        SceneType.SEARCH_BAR to "Kya insaan Mars par reh sakta hai?",
        SceneType.WORD_CLOUD to "Internet ne education, business, entertainment aur communication sab badal diya",
    )

    @Test fun gallery() {
        val out = File(System.getProperty("fs.out") ?: return, "gallery").apply { mkdirs() }
        val imgs = File(System.getProperty("fs.imgs") ?: return).listFiles()?.sortedBy { it.name }?.map { it.absolutePath } ?: return
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val base = Project(id = "g", title = "Rahasya", script = "x", images = imgs, seed = 3)
        val types = listOf(SceneType.TEXT_MASK, SceneType.FILMSTRIP, SceneType.MAGNIFY, SceneType.NEWS_FLASH, SceneType.CHAT_CARD, SceneType.SEARCH_BAR,
            SceneType.CIRCLE_REVEAL, SceneType.SPLIT_DIAGONAL, SceneType.WORD_CLOUD, SceneType.GAUGE, SceneType.STEPS, SceneType.STICKY_NOTES)
        val fxs = TextFx.entries; val bgs = Backdrop.entries; val cams = CamMove.entries; val decos = Deco.entries
        types.forEachIndexed { i, t ->
            val text = texts[t] ?: "Mars par paani ke saboot mile hain jo zindagi ki ummeed jagaate hain"
            val (lines, title) = Director.fill(t, text, listOf(text), false, base, emptyList(), -1)
            val sc = Scene(text = text, startMs = 0, endMs = 4000, type = t, transition = Transition.CUT, imageIndex = i % imgs.size, variant = 1000 + i,
                lines = lines, title = title, emphasis = com.gk.facelessstudio.engine.ScriptAnalyzer.keywords(text, 2),
                fx = fxs[i % fxs.size], bg = bgs[(i * 5) % bgs.size], cam = cams[i % cams.size], deco = decos[(i * 3) % decos.size])
            val p = base.copy(scenes = listOf(sc), durationMs = 4000)
            val r = FrameRenderer(854, 480, p, Fonts(ctx.assets), ImageStore(imgs, 1200))
            val bmp = Bitmap.createBitmap(854, 480, Bitmap.Config.ARGB_8888)
            r.draw(Canvas(bmp), 3300)
            File(out, "%02d_${t.name}.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // backdrops and decorations on a kinetic line
        bgs.forEachIndexed { i, b ->
            val sc = Scene(text = "Duniya ka sabse bada rahasya", startMs = 0, endMs = 3000, type = SceneType.KINETIC, transition = Transition.CUT, imageIndex = 0,
                variant = 7, emphasis = listOf("rahasya"), fx = fxs[i % fxs.size], bg = b, deco = decos[i % decos.size])
            val p = base.copy(scenes = listOf(sc), durationMs = 3000)
            val r = FrameRenderer(854, 480, p, Fonts(ctx.assets), ImageStore(imgs, 1200))
            val bmp = Bitmap.createBitmap(854, 480, Bitmap.Config.ARGB_8888)
            r.draw(Canvas(bmp), 1400)
            File(out, "bg_%02d_${b.name}.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
