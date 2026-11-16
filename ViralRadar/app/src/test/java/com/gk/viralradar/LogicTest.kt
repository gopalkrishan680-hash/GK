package com.gk.viralradar

import com.gk.viralradar.data.*
import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    private fun vid(views: Long, hoursOld: Double, subs: Long?, likes: Long = views / 25, comments: Long = views / 500, dur: Long = 600) =
        Video("abcdefghijk", "t", "c", "ch", System.currentTimeMillis() - (hoursOld * 3_600_000).toLong(), "", views, likes, comments, dur, emptyList(), "24", "", subs)

    @Test fun duration() {
        assertEquals(3723, YouTubeApi.parseDuration("PT1H2M3S"))
        assertEquals(45, YouTubeApi.parseDuration("PT45S"))
        assertEquals(90061, YouTubeApi.parseDuration("P1DT1H1M1S"))
    }

    @Test fun ids() {
        assertEquals("dQw4w9WgXcQ", YouTubeApi.extractId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5"))
        assertEquals("dQw4w9WgXcQ", YouTubeApi.extractId("https://youtu.be/dQw4w9WgXcQ?si=x"))
        assertEquals("dQw4w9WgXcQ", YouTubeApi.extractId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeApi.extractId("dQw4w9WgXcQ"))
        assertNull(YouTubeApi.extractId("funny prank"))
    }

    @Test fun scoring() {
        val viral = ViralScore.score(vid(2_000_000, 10.0, 50_000))   // 200k/h, 40x outlier
        val slow = ViralScore.score(vid(3_000, 300.0, 2_000_000))     // 10/h, tiny outlier
        println("viral=${viral.score} ${viral.label} p24=${viral.predicted24h} p7=${viral.predicted7d}")
        println("slow=${slow.score} ${slow.label}")
        assertTrue(viral.score >= 75)
        assertTrue(slow.score < 40)
        assertTrue(viral.predicted7d >= viral.predicted24h && viral.predicted24h >= 2_000_000)
    }

    @Test fun tracker() {
        val h = 3_600_000L; val t0 = System.currentTimeMillis() - 3 * h
        val t = TrackedVideo("abcdefghijk", "t", "ch", "", t0 - 5 * h, 10_000, t0,
            listOf(Snapshot(t0, 10_000, 0, 0), Snapshot(t0 + h, 11_000, 0, 0), Snapshot(t0 + 2 * h, 14_000, 0, 0)))
        val s = Tracker.stats(t)
        assertEquals(3000.0, s.recentVph!!, 1.0)
        assertEquals(2.0, s.acceleration!!, 0.01)
        assertTrue(s.status.contains("Accelerating"))
    }

    @Test fun title() {
        val (score, tips) = Insights.checkTitle("5 SHOCKING Secrets Nobody Tells You About Money 💰", null)
        assertTrue(score >= 70); assertTrue(tips.isNotEmpty())
    }

    @Test fun opportunity() {
        // High views, small channels winning, few uploads -> golden
        val gap = (1..20).map { vid(300_000L + it * 10_000, 200.0, 20_000L + it * 1000) }
        val g = Opportunity.build("desi prank", gap, 800, true, 720)
        // Mediocre views, mega channels, huge supply -> saturated
        val sat = (1..20).map { vid(20_000L, 200.0, 5_000_000L) }
        val x = Opportunity.build("gta 5", sat, 1_000_000, false, 720)
        println("gap=${g.score} ${g.label}  sat=${x.score} ${x.label}")
        g.verdict.forEach(::println)
        assertTrue(g.score >= 70)
        assertTrue(x.score < 40)
        assertNotNull(g.bestSmall)
        assertEquals(0, Opportunity.build("none", emptyList(), 0, false, 720).score)
    }

    @Test fun autoTopics() {
        val now = System.currentTimeMillis()
        fun v(id: String, ch: String, title: String, tags: List<String>, subs: Long, views: Long) =
            Video(id, title, ch, "Channel $ch", now - 10 * 3_600_000L, "", views, views / 20, views / 300, 600, tags, "24", "", subs)
        val vids = listOf(
            v("a1", "c1", "Papa Beta Comedy | desi prank on papa", listOf("desi prank", "papa beta"), 20_000, 900_000),
            v("a2", "c2", "Desi prank gone wrong 😂 papa ka gussa", listOf("desi prank", "family comedy"), 45_000, 600_000),
            v("a3", "c3", "Family comedy: papa beta ki ladai", listOf("family comedy", "papa beta"), 60_000, 400_000),
            v("a4", "c4", "Channel c4 exclusive vlog", listOf("my vlog"), 5_000_000, 50_000),
            v("a5", "c5", "रसोई में नया तरीका आलू पराठा", listOf("आलू पराठा"), 30_000, 300_000),
            v("a6", "c6", "आलू पराठा बनाने का आसान तरीका", listOf("आलू पराठा", "recipe"), 80_000, 200_000),
        )
        val r = AutoScan.rankTopics(vids, listOf("ind vs aus" to 200_000L), now)
        val names = r.map { it.phrase }
        println("topics=" + names.take(10))
        assertTrue("desi prank" in names)
        assertTrue("आलू पराठा" in names)            // Hindi phrase kept whole
        assertTrue("ind vs aus" in names)           // Google Trends merged
        assertFalse(names.any { "my vlog" in it })  // single-channel tag ignored
        assertTrue(r.first { it.phrase == "desi prank" }.sources.contains(AutoScan.SRC_SMALL))
    }
}
