package com.gk.facelessstudio

import com.gk.facelessstudio.engine.Director
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VarietyTest {
    private val sentences = listOf(
        "Duniya me har din naye rahasya khulte hain", "Science ne bahut tarakki ki hai", "Lekin kuch sawal abhi bhi baaki hain",
        "Insaan ka dimaag ek supercomputer jaisa hai", "Hum roz hazaron faisle lete hain", "Kuch faisle sahi hote hain kuch galat",
        "Neend hamare liye bahut zaroori hai", "Pani ke bina hum teen din bhi nahi reh sakte", "Suraj ki roshni dharti tak aath minute me pahunchti hai",
        "Chand dheere dheere dharti se door ja raha hai", "Samundar ka sirf thoda hissa hi explore hua hai", "Jungle me anokhe jaanwar rehte hain",
        "Pahad hazaron saal me bante hain", "Registan me bhi zindagi hoti hai", "Barish ka pani nadiyon me jata hai",
        "Nadiyan samundar me mil jaati hain", "Hawa me oxygen hoti hai", "Ped hume saans lene me madad karte hain",
        "Insaan ne aag ki khoj ki", "Phir pahiya bana", "Uske baad machine aayi", "Aur ab internet ka zamana hai",
        "Mobile ne duniya badal di", "Har kisi ke haath me computer hai", "Lekin kya hum khush hain",
        "Khushi paise se nahi milti", "Rishte sabse keemti hote hain", "Dosti zindagi ko aasan banati hai",
        "Parivaar hamesha saath deta hai", "Yahi zindagi ka sach hai",
    )

    @Test fun noRepeatsInOneVideo() {
        val script = sentences.joinToString(". ") + "."
        val p = Director.build(Project(id = "v", title = "Rahasya", script = script, seed = 5, images = listOf("a", "b", "c")), null)
        val scenes = p.scenes.filter { it.type != SceneType.OUTRO }
        println("SCENES ${scenes.size}: " + scenes.joinToString(" ") { it.type.name })
        // 1) every full animation combination is unique
        assertEquals(scenes.size, scenes.map { it.combo }.toSet().size)
        // 2) no layout more than twice
        val counts = scenes.groupingBy { it.type }.eachCount()
        println("COUNTS $counts")
        assertTrue("a layout repeated more than twice: $counts", counts.values.all { it <= 2 })
        // 3) the same layout never within 3 scenes
        scenes.windowed(4).forEach { w -> assertTrue("too close: ${w.map { it.type }}", w.drop(1).none { it.type == w[0].type }) }
        // 4) entrances / backdrops / cameras / decorations are spread out
        println("FX ${scenes.groupingBy { it.fx }.eachCount()}")
        println("BG ${scenes.groupingBy { it.bg }.eachCount()}")
        println("TR ${scenes.groupingBy { it.transition }.eachCount()}")
        assertTrue(scenes.map { it.fx }.toSet().size >= 10)
        assertTrue(scenes.map { it.transition }.toSet().size >= 10)
    }

    @Test fun longVideoStaysEven() {
        val script = (sentences + sentences.map { "Aur $it" } + sentences.map { "Socho $it" }).joinToString(". ") + "."
        val p = Director.build(Project(id = "v2", script = script, seed = 9, images = listOf("a", "b", "c")), null)
        val scenes = p.scenes
        assertEquals(scenes.size, scenes.map { it.combo }.toSet().size)
        val counts = scenes.groupingBy { it.type }.eachCount()
        println("LONG ${scenes.size} scenes -> max per layout ${counts.values.max()} : $counts")
        assertTrue(counts.values.max() - counts.values.min() <= 3)
        scenes.windowed(4).forEach { w -> assertTrue(w.drop(1).none { it.type == w[0].type }) }
    }
}
