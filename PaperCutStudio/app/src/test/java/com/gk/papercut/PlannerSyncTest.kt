package com.gk.papercut

import com.gk.papercut.data.EnterAnim
import com.gk.papercut.data.Project
import com.gk.papercut.data.ScriptPlanner
import com.gk.papercut.sync.AudioTools
import com.gk.papercut.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerSyncTest {

    private val script = """
        Ek chhote se gaon mein Raju naam ka ladka rehta tha. Uske papa ek chhoti si dukaan chalate the.
        Har subah Raju cycle se school jaata tha. Raste mein bade ped aur nadi aati thi.
        Ek din baarish mein uski cycle gir gayi! Raju bahut dukhi hua. Phir uske dost ne madad ki aur dono khush ho gaye.
    """.trimIndent()

    @Test fun splitsIntoReasonableScenes() {
        val scenes = ScriptPlanner.splitScenes(script)
        scenes.forEach { println("SCENE: $it") }
        assertTrue(scenes.size in 3..6)
        scenes.forEach { assertTrue(ScriptPlanner.words(it).size <= 42) }
        assertEquals(ScriptPlanner.words(script).size, scenes.sumOf { ScriptPlanner.words(it).size })
    }

    @Test fun respectsBlankLineParagraphs() {
        val s = ScriptPlanner.splitScenes("Pehla scene yahan hai.\n\nDoosra scene yahan.\n\nTeesra.")
        assertEquals(3, s.size)
    }

    @Test fun findsHinglishElementsAndMotion() {
        val hints = ScriptPlanner.findElements("Ek din baarish mein uski cycle gir gayi!")
        println(hints)
        assertTrue(hints.any { it.name == "a bicycle" })
        assertTrue(hints.any { it.name == "rain drops" && it.enter == EnterAnim.DROP })
        val p = ScriptPlanner.plan(Project(id = "t", name = "t", script = script, characterNote = "Raju: boy in red t-shirt"))
        println(p.first().prompt)
        assertTrue(p.first().prompt.contains("paper cut-out", ignoreCase = true))
        assertTrue(p.first().prompt.contains("Raju: boy in red t-shirt"))
    }

    /** Synthetic voice: speech blocks separated by pauses that should become the scene cuts. */
    @Test fun snapsSceneCutsToPauses() {
        val scenes = ScriptPlanner.plan(Project(id = "t", name = "t", script = "Aaj hum ek kahani sunenge.\n\nRaju school gaya aur bahut padha.\n\nShaam ko woh ghar wapas aaya."))
        val hop = 0.02
        // speech 0.3-2.3, pause, 2.9-5.6, pause, 6.3-8.5
        val segs = listOf(0.3 to 2.3, 2.9 to 5.6, 6.3 to 8.5)
        val n = (9.0 / hop).toInt()
        val rms = FloatArray(n) { i -> val t = i * hop; if (segs.any { t >= it.first && t < it.second }) 0.2f + (i % 7) * 0.01f else 0.003f }
        val synced = SyncEngine.syncWithEnvelope(scenes, AudioTools.Envelope(rms, hop, 9.0))
        synced.forEach { println("%.2f-%.2f %s | first word %.2f".format(it.start, it.end, it.text, it.words.first().start)) }
        assertEquals(0.0, synced[0].start, 1e-9)
        assertEquals(2.9, synced[1].start, 0.25)
        assertEquals(6.3, synced[2].start, 0.25)
        assertEquals(9.0, synced[2].end, 1e-9)
        assertEquals(0.3, synced[0].words.first().start, 0.1)
    }

    @Test fun srtSync() {
        val scenes = ScriptPlanner.plan(Project(id = "t", name = "t", script = "Hello world this is one.\n\nAnd this is two now."))
        val srt = "1\n00:00:01,000 --> 00:00:03,000\nHello world this is one.\n\n2\n00:00:04,500 --> 00:00:06,000\nAnd this is two now.\n"
        val s = SyncEngine.syncWithSrt(scenes, srt, 7.0)
        assertEquals(4.5, s[1].words.first().start, 0.01)
        assertEquals(4.38, s[1].start, 0.01)
        assertEquals(7.0, s[1].end, 0.01)
    }
}
