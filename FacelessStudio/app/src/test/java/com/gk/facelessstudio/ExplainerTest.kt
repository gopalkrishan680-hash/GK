package com.gk.facelessstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.gk.facelessstudio.engine.Director
import com.gk.facelessstudio.engine.GridWorkflow
import com.gk.facelessstudio.engine.Plan
import com.gk.facelessstudio.model.Aspect
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.render.Fonts
import com.gk.facelessstudio.render.FrameRenderer
import com.gk.facelessstudio.render.ImageStore
import org.junit.Assert.assertEquals
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
class ExplainerTest {
    private val script = """
        Hamare sharir me ek bahut hi bahadur cell rehta hai.
        Jab virus aata hai, ye cell use pakad leta hai.
        Vaccine bhi isi tarah kaam karti hai.
        Vaccine body ko virus ka ek kamzor tukda dikhati hai.
        Phir doosre cells is pehchaan ko yaad rakhte hain.
        Ise immune memory kehte hain.
        Agli baar jab asli virus aata hai, body turant ladti hai.
    """.trimIndent()

    private val reply = """
        Sure! Here is the plan.
        ELEMENTS
        **E1**: macrophage | big round friendly cyan immune cell with a smiling face
        E2: virus | small orange spiky virus
        E3: vaccine | small glass vaccine vial with blue liquid
        E4: B cell | blue oval cell with a happy face
        E5: helper cell | green round cell with a happy face
        E6: angry virus | orange virus with an angry face
        LINES
        1: E1 @cell; bg pink
        2: E2 @virus; E2 > E1 @pakad; bg blue
        3: E3 @Vaccine; arrow E3 > E1
        4: text "weak" @kamzor; arrow E2 > E1
        5: new; E4 @cells; E5; bg dark
        6: text "memory" @memory; E5 > E4
        7: E6 @virus; arrow E5 > E6 @ladti; bg yellow
    """.trimIndent()

    @Test fun parsesPlan() {
        val p = Plan.parse(reply)
        assertEquals(6, p.elements.size)
        assertEquals("macrophage", p.elements[0].name)
        assertEquals(Plan.Kind.MOVE, p.lines[1]!![1].kind)
        assertEquals("pakad", p.lines[1]!![1].cue)
        assertEquals(Plan.Kind.ARROW, p.lines[2]!![1].kind)
        assertEquals("weak", p.lines[3]!![0].text)
        assertEquals(Plan.Kind.NEW, p.lines[4]!![0].kind)
        assertEquals(Plan.Kind.BG, p.lines[6]!![2].kind)
        assertEquals(Plan.Kind.BG, p.lines[0]!![1].kind)
        // merging keeps both parts
        val merged = Plan.parse(Plan.merge(reply, "ELEMENTS\nE7: sun | yellow sun\nLINES\n8: E7"))
        assertEquals(7, merged.elements.size); assertEquals(8, merged.lines.size)
        val proj = Project(id = "x", script = script, doodle = true, clean = true, elements = true, plan = Plan.merge("", reply))
        assertEquals(6, GridWorkflow.pieces(proj).size)
        assertTrue(GridWorkflow.planPrompt(proj, GridWorkflow.pieces(proj.copy(elements = false)), listOf(0, 1)).contains("ELEMENTS"))
        assertTrue(GridWorkflow.gridPrompt(proj, GridWorkflow.grids(6, 9)[0], GridWorkflow.pieces(proj)).contains("ELEMENT RULE"))
    }

    @Test fun landscape() = render(Aspect.LANDSCAPE, 640, 360, "explainer")
    @Test fun portrait() = render(Aspect.PORTRAIT, 360, 640, "explainer_tall")

    private fun render(aspect: Aspect, w: Int, h: Int, dir: String) {
        val imgs = File(System.getProperty("fs.imgs") ?: return).listFiles()?.sortedBy { it.name }?.map { it.absolutePath } ?: return
        var p = Project(id = "e", script = script, seed = 3, doodle = true, clean = true, elements = true, aspect = aspect, plan = Plan.merge("", reply))
        p = Director.build(p.copy(lineImages = true, images = (0 until 6).map { imgs[it % imgs.size] }), null)
        assertEquals(Director.analysisFor(p).chunks.map { it.text }.toString(), 7, p.scenes.size)
        val out = File(System.getProperty("fs.out") ?: return, dir).apply { mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = FrameRenderer(w, h, p, Fonts(ctx.assets), ImageStore(p.images, 900))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fps = (System.getProperty("FS_FPS") ?: "12").toInt()
        for (f in 0 until (p.durationMs * fps / 1000).toInt()) {
            r.draw(Canvas(bmp), f * 1000L / fps)
            File(out, "f%04d.jpg".format(f)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
    }
}
