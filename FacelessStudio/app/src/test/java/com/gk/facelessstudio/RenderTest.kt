package com.gk.facelessstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.gk.facelessstudio.engine.Director
import com.gk.facelessstudio.engine.ScriptAnalyzer
import com.gk.facelessstudio.model.Aspect
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.render.Fonts
import com.gk.facelessstudio.render.FrameRenderer
import com.gk.facelessstudio.render.ImageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class RenderTest {

    private val script = """
        # Intro
        Aaj hum dark web ki hidden duniya explore karenge.
        Ye internet ka wo hissa hai jo Google par nahi dikhta.
        # Kipling Method
        What exactly is the dark web? When did it emerge? Where was it created? Who uses the dark web? Why does it exist?
        Is video ko 723,335 logon ne dekha hai.
        3 Dec 1984 ki raat 12:00 AM par sab badal gaya.
        Quora vs Reddit
        Log smoke karte hain: peer pressure, stress relief aur advertising.
        Research is fun
        Aaj 65% log news sirf social media se padhte hain.
        India me 140 crore log hain jabki China me 141 crore.
        Internet 1983 me bana, Google 1998 me aaya aur YouTube 2005 me launch hua.
        Article padho kyunki usse more knowledge milti hai.
        Steve Jobs ne kaha tha "Stay hungry, stay foolish" aur ye line famous ho gayi.
        [note] Intro ke baad main content aata hai aur phir conclusion.
        [phone] Mobile par research karna bahut aasan ho gaya hai aaj kal.
        [headline] Medicine industry ka sach bahut dardnaak hai.
        [photos] Ye kuch purani photos hain jo history batati hain.
        Har ek video me curiosity sabse important cheez hai jo viewers ko rok kar rakhti hai.
    """.trimIndent()

    private fun project(aspect: Aspect): Project {
        val imgs = (System.getProperty("fs.imgs") ?: "").let { d -> File(d).listFiles()?.sortedBy { it.name }?.map { it.absolutePath } } ?: emptyList()
        val base = Project(id = "test", title = "Research Guide", script = script, images = imgs, aspect = aspect, handle = "@gkfacts", seed = 42, storyMode = false)
        return Director.build(base, null)
    }

    @Test
    fun analyzerFindsStructures() {
        val a = ScriptAnalyzer.analyze(script, 12)
        assertEquals(listOf("Intro", "Kipling Method"), a.chapters)
        assertNotNull(ScriptAnalyzer.detectStat("Is video ko 723,335 logon ne dekha"))
        assertEquals("3 DEC 1984", ScriptAnalyzer.detectDate("3 Dec 1984 ki raat 12:00 AM")!![0])
        assertNotNull(ScriptAnalyzer.detectVersus("Quora vs Reddit"))
        assertNotNull(ScriptAnalyzer.detectList("Log smoke karte hain: peer pressure, stress relief aur advertising."))
        val p = project(Aspect.LANDSCAPE)
        val types = p.scenes.map { it.type }.toSet()
        println("TYPES: " + p.scenes.joinToString { it.type.name })
        assertTrue(SceneType.QUESTION_LIST in types)
        assertTrue(SceneType.BIG_STAT in types)
        assertTrue(SceneType.VERSUS in types)
        assertTrue(SceneType.MINDMAP in types)
        assertTrue(SceneType.DONUT in types)
        assertTrue(SceneType.BAR_CHART in types)
        assertTrue(SceneType.TIMELINE in types)
        println("PROMPT: " + ScriptAnalyzer.imagePrompt("Ye internet ka wo hissa hai jo Google par nahi dikhta.", "Cinematic realistic photo", "16:9", "Dark Web", "Intro"))
        // scenes are contiguous
        p.scenes.zipWithNext().forEach { (x, y) -> assertEquals(x.endMs, y.startMs) }
    }

    @Test
    fun renderStills() {
        val out = File(System.getProperty("fs.out") ?: return).apply { mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fonts = Fonts(ctx.assets)
        for (aspect in listOf(Aspect.LANDSCAPE, Aspect.PORTRAIT)) {
            val p = project(aspect)
            val (w, h) = if (aspect == Aspect.LANDSCAPE) 854 to 480 else 405 to 720
            val r = FrameRenderer(w, h, p, fonts, ImageStore(p.images, 1200))
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            p.scenes.forEachIndexed { i, s ->
                for ((tag, f) in listOf("a" to 0.3f, "b" to 0.8f)) {
                    val t = s.startMs + (s.durationMs * f).toLong()
                    r.draw(Canvas(bmp), t)
                    File(out, "${aspect.name.lowercase()}_%02d${tag}_${s.type.name}.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                if (i > 0) { // mid transition
                    r.draw(Canvas(bmp), s.startMs + 20)
                    File(out, "${aspect.name.lowercase()}_%02dt_${s.transition.name}.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    /** Renders a whole low-res video as frames so it can be stitched with ffmpeg for review. */
    @Test
    fun renderSequence() {
        val dir = System.getProperty("fs.out")?.let { File(it, "seq") } ?: return
        if (System.getenv("FS_SEQ") == null) return
        dir.mkdirs()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = project(Aspect.LANDSCAPE)
        val r = FrameRenderer(854, 480, p, Fonts(ctx.assets), ImageStore(p.images, 1200))
        val bmp = Bitmap.createBitmap(854, 480, Bitmap.Config.ARGB_8888)
        val fps = 30
        val n = (p.durationMs * fps / 1000).toInt()
        val t0 = System.currentTimeMillis()
        for (f in 0 until n) {
            r.draw(Canvas(bmp), f * 1000L / fps)
            File(dir, "f%05d.jpg".format(f)).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        println("SEQ frames=$n ms/frame=${(System.currentTimeMillis() - t0) / n}")
        val pcm = com.gk.facelessstudio.engine.Mixer.mix(p, null, null, p.durationMs)
        java.io.DataOutputStream(File(dir, "audio.wav").outputStream().buffered()).use { o ->
            fun le32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v shr 24 and 255) }
            fun le16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
            o.writeBytes("RIFF"); le32(36 + pcm.size * 2); o.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1)
            le32(44100); le32(88200); le16(2); le16(16); o.writeBytes("data"); le32(pcm.size * 2)
            for (v in pcm) le16(v.toInt())
        }
        File(dir, "timeline.txt").writeText(p.scenes.joinToString("\n") { "${it.startMs}\t${it.endMs}\t${it.type}\t${it.transition}\t${it.text}" })
    }
}
