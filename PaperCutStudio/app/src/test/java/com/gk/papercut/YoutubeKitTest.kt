package com.gk.papercut

import com.gk.papercut.data.Project
import com.gk.papercut.data.ScriptPlanner
import com.gk.papercut.data.YoutubeKitBuilder
import com.gk.papercut.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeKitTest {
    @Test fun hashtagsStartSmall() {
        assertEquals("#mahabharat", YoutubeKitBuilder.hashtag("#Mahabharat"))
        assertEquals("#bhishmaPitamah", YoutubeKitBuilder.hashtag("Bhishma Pitamah"))
        assertEquals("#महाभारत", YoutubeKitBuilder.hashtag("#महाभारत"))
        assertEquals("#shorts", YoutubeKitBuilder.hashtag("#SHORTS".lowercase()))
    }

    private fun project(): Project {
        val script = (1..8).joinToString("\n\n") { "Yeh scene number $it hai aur isme bhishma ki kahani aage badhti hai, sab log dekhte hain." }
        val p = Project(id = "y", name = "Bhishm", script = script)
        val scenes = SyncEngine.syncByReadingSpeed(ScriptPlanner.plan(p)).mapIndexed { i, s ->
            s.copy(start = i * 6.0, end = (i + 1) * 6.0) // 8 scenes x 6 s = 48 s
        }
        return p.copy(scenes = scenes, audioDuration = 48.0)
    }

    @Test fun chaptersFollowYoutubeRules() {
        val ch = YoutubeKitBuilder.chapters(project(), listOf("Intro", "The Vow", "The Silence", "The Answer"))
        println(ch)
        val lines = ch.lines()
        assertTrue(lines.first().startsWith("0:00 Intro"))
        assertTrue(lines.size >= 3)
        val times = lines.map { it.substringBefore(' ').split(':').let { (m, s) -> m.toInt() * 60 + s.toInt() } }
        times.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 10) }
        assertTrue(48 - times.last() >= 10)
    }

    @Test fun parsesChatGptAnswer() {
        val answer = """
            **TITLES**
            1. Why Was Bhishma Silent? 😱
            2. The Silence That Destroyed Hastinapur
            3. भीष्म चुप क्यों थे?

            **DESCRIPTION**
            Why did the greatest warrior stay silent when Draupadi was insulted?
            In this paper cut-out story we explore the vow that bound him.
            #Mahabharat

            **CHAPTERS**
            0:00 – The Insult
            0:12 – The Vow
            0:24 – The Answer

            **HASHTAGS**
            #Mahabharat #BhishmaPitamah #Draupadi #PaperCutAnimation #Shorts

            **TAGS**
            mahabharat, bhishma, draupadi cheer haran, hindi story

            **THUMBNAIL PROMPT**
            Paper cut-out Bhishma with a shocked face, big empty space for title.
        """.trimIndent()
        val k = YoutubeKitBuilder.parseChatbotAnswer(answer, project())
        println(k)
        assertEquals(3, k.titles.size)
        assertEquals("Why Was Bhishma Silent? 😱", k.titles[0])
        assertTrue(k.description.startsWith("Why did the greatest warrior"))
        assertTrue(!k.description.contains("#"))
        assertEquals(listOf("#mahabharat", "#bhishmaPitamah", "#draupadi", "#paperCutAnimation", "#shorts"), k.hashtags)
        assertEquals(4, k.tags.size)
        assertTrue(k.chapters.startsWith("0:00 The Insult"))
        assertTrue(k.thumbnailPrompt.startsWith("Paper cut-out Bhishma"))
        println(YoutubeKitBuilder.fullDescription(k))
    }
}
