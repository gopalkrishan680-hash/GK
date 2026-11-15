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
}
