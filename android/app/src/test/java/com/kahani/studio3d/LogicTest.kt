package com.kahani.studio3d

import com.kahani.studio3d.data.AspectRatio
import com.kahani.studio3d.data.Character
import com.kahani.studio3d.data.Project
import com.kahani.studio3d.data.SplitMode
import com.kahani.studio3d.logic.PromptGenerator
import com.kahani.studio3d.logic.Resampler
import com.kahani.studio3d.logic.SceneLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val hindi = "Ek gaon me Raju rehta tha. Wo roz school jata tha! Kya tum jaante ho? Ek din usne ek jaadui patthar dekha।\nPatthar chamak raha tha"

    @Test fun splitSentence() {
        val s = SceneLogic.split(hindi, SplitMode.SENTENCE)
        assertEquals(5, s.size)
        assertEquals("Ek gaon me Raju rehta tha.", s[0])
        assertEquals("Patthar chamak raha tha", s[4])
    }

    @Test fun splitLineAndSmart() {
        assertEquals(2, SceneLogic.split(hindi, SplitMode.LINE).size)
        val smart = SceneLogic.split(hindi, SplitMode.SMART)
        assertTrue(smart.size in 1..3)
    }

    @Test fun longSentenceIsBroken() {
        val long = (1..80).joinToString(" ") { "word$it" }
        val s = SceneLogic.split(long, SplitMode.SENTENCE)
        assertTrue(s.size >= 3)
        assertEquals(80, s.sumOf { SceneLogic.wordCount(it) })
    }

    private fun project(voiceMs: Long): Project {
        val p = Project(id = "x", title = "t", createdAt = 0, updatedAt = 0, voiceDurationMs = voiceMs)
        return p.copy(scenes = SceneLogic.buildScenes(SceneLogic.split(hindi, SplitMode.SENTENCE), emptyList()))
    }

    @Test fun autoTimeSumsToVoice() {
        val p = project(30_000)
        val timed = SceneLogic.autoTime(p)
        assertEquals(30_000, timed.sumOf { it.durationMs })
        assertTrue(timed.all { it.durationMs >= 800 })
    }

    @Test fun autoTimeSnapsToPauses() {
        val p = project(30_000)
        val plain = SceneLogic.autoTime(p)
        val firstCut = plain[0].durationMs
        val snapped = SceneLogic.autoTime(p, listOf(firstCut + 700))
        assertEquals(firstCut + 700, snapped[0].durationMs)
        assertEquals(30_000, snapped.sumOf { it.durationMs })
    }

    @Test fun lockedSceneKeepsDuration() {
        val p = project(30_000)
        val scenes = p.scenes.toMutableList()
        scenes[1] = scenes[1].copy(durationMs = 9_000, locked = true)
        val timed = SceneLogic.autoTime(p.copy(scenes = scenes))
        assertEquals(9_000, timed[1].durationMs)
        assertEquals(30_000, timed.sumOf { it.durationMs })
    }

    @Test fun naturalSort() {
        val names = listOf("10.png", "2.png", "1.png", "scene 3.jpg", "Image_11.png", "image_9.png")
        val sorted = names.sortedWith { a, b -> StudioViewModel.naturalCompare(a, b) }
        assertEquals(listOf("1.png", "2.png", "10.png", "image_9.png", "Image_11.png", "scene 3.jpg"), sorted)
    }

    @Test fun offlinePromptHasCharacterAndStyle() {
        val p = project(10_000).copy(characters = listOf(Character("Raju", "8 year old boy, curly hair, yellow t-shirt")))
        val prompt = PromptGenerator.offline(p, 0, p.scenes[0])
        assertTrue(prompt.contains("curly hair"))
        assertTrue(prompt.contains("Pixar"))
        val prompt2 = PromptGenerator.offline(p, 1, p.scenes[1])
        assertTrue(!prompt2.contains("curly hair")) // Raju not named in scene 2
    }

    @Test fun aspectSizesEven() {
        AspectRatio.entries.forEach { a ->
            listOf(480, 720, 1080).forEach { q ->
                val (w, h) = a.size(q)
                assertEquals(0, w % 2); assertEquals(0, h % 2)
                assertEquals(q, minOf(w, h))
            }
        }
        assertEquals(1080 to 1920, AspectRatio.PORTRAIT.size(1080))
    }

    @Test fun resamplerLength() {
        val r = Resampler(48000, 44100)
        var out = 0
        repeat(100) {
            val l = FloatArray(480) { i -> kotlin.math.sin(i / 10.0).toFloat() }
            r.process(l, l.copyOf()) { a, _ -> out += a.size }
        }
        // 48000 input frames -> ~44100 output frames
        assertTrue("got $out", kotlin.math.abs(out - 44100) <= 2)
    }
}
