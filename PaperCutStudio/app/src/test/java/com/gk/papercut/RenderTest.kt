package com.gk.papercut

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.gk.papercut.data.EnterAnim
import com.gk.papercut.data.IdleAnim
import com.gk.papercut.data.Layer
import com.gk.papercut.data.Project
import com.gk.papercut.data.ProjectRepository
import com.gk.papercut.data.Scene
import com.gk.papercut.data.ScriptPlanner
import com.gk.papercut.data.Transition
import com.gk.papercut.ml.Cutter
import com.gk.papercut.ml.Inpainter
import com.gk.papercut.ml.Mask
import com.gk.papercut.render.FrameRenderer
import com.gk.papercut.sync.SyncEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders real frames with Android's graphics stack so the look can be inspected as PNGs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RenderTest {

    private val outDir = File(System.getProperty("render.out") ?: "build/render-test").apply { mkdirs() }

    private fun shape(w: Int, h: Int, color: Int, draw: (Canvas, Paint) -> Unit): Pair<Bitmap, Mask> {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        draw(Canvas(b), p)
        return b to Mask.fromAlpha(w, h, b, 0, 0)
    }

    @Test fun separateAndRenderScene() {
        val ctx = RuntimeEnvironment.getApplication()
        val repo = ProjectRepository(ctx)
        val w = 1280; val h = 720

        // A fake "AI image": sky, hills and three objects.
        val scene = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(scene)
        c.drawColor(Color.rgb(150, 200, 230))
        val hill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 160, 90) }
        c.drawOval(-200f, 480f, 900f, 1000f, hill)
        hill.color = Color.rgb(60, 130, 80); c.drawOval(500f, 520f, 1600f, 1100f, hill)

        val objs = listOf(
            shape(w, h, Color.rgb(250, 200, 40)) { cv, p -> cv.drawCircle(1080f, 140f, 80f, p) },
            shape(w, h, Color.rgb(200, 70, 50)) { cv, p ->
                cv.drawRect(180f, 330f, 460f, 560f, p)
                val roof = Path().apply { moveTo(150f, 340f); lineTo(320f, 200f); lineTo(490f, 340f); close() }
                p.color = Color.rgb(120, 50, 40); cv.drawPath(roof, p)
            },
            shape(w, h, Color.rgb(240, 120, 160)) { cv, p -> cv.drawOval(700f, 360f, 820f, 600f, p); cv.drawCircle(760f, 330f, 50f, p) },
        )
        objs.forEach { (b, _) -> c.drawBitmap(b, 0f, 0f, null) }

        val union = Mask(w, h); objs.forEach { union.union(it.second) }
        val bg = Inpainter.inpaint(scene, union.dilated(10), null)
        val p0 = repo.create("render-test")
        repo.saveBitmap(p0.id, "scene.png", scene)
        repo.saveBitmap(p0.id, "bg.png", bg)
        bg.compress(Bitmap.CompressFormat.PNG, 100, File(outDir, "background_plate.png").outputStream())

        val anims = listOf(EnterAnim.DROP to IdleAnim.SPIN, EnterAnim.GROW_UP to IdleAnim.NONE, EnterAnim.SLIDE_RIGHT to IdleAnim.WALK)
        val layers = objs.mapIndexed { i, (_, m) ->
            val cut = assertNotNullAndGet(Cutter.cut(scene, m))
            val name = repo.saveBitmap(p0.id, "l$i.png", cut.bitmap)
            Layer("L$i", name, cut.x.toFloat(), cut.y.toFloat(), cut.bitmap.width.toFloat(), cut.bitmap.height.toFloat(),
                enter = anims[i].first, idle = anims[i].second, keywordIndex = i * 3)
        }.sortedByDescending { it.w * it.h }

        val text1 = "Subah sooraj nikla aur Raju ka ghar chamak utha, phir Raju bahar aaya."
        val text2 = "Doosra scene, wahi tasveer, paper flip ke saath."
        var scenes = listOf(
            Scene("A", text1, imageFile = "scene.png", backgroundFile = "bg.png", imageW = w, imageH = h, layers = layers),
            Scene("B", text2, imageFile = "scene.png", backgroundFile = "bg.png", imageW = w, imageH = h, layers = layers, transition = Transition.PAPER_FLIP),
        )
        scenes = SyncEngine.syncByReadingSpeed(scenes)
        val project = repo.save(p0.copy(script = "$text1\n\n$text2", scenes = scenes, audioDuration = scenes.last().end))
        val r = FrameRenderer(project, repo)
        val frame = Bitmap.createBitmap(r.outW, r.outH, Bitmap.Config.ARGB_8888)
        val fc = Canvas(frame)
        val times = listOf(0.1, 0.5, 1.2, 2.0, 3.5, scenes[1].start + 0.2, scenes[1].start + 0.45, scenes[1].start + 2.5)
        times.forEachIndexed { i, t ->
            r.render(fc, t)
            frame.compress(Bitmap.CompressFormat.PNG, 100, File(outDir, "frame_%02d_%.2fs.png".format(i, t)).outputStream())
        }
        println("Words: " + ScriptPlanner.words(text1).joinToString())
        println("Frames written to ${outDir.absolutePath}")
        assertTrue(File(outDir, "background_plate.png").exists())
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
