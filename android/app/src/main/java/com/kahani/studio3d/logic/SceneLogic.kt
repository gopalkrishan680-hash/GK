package com.kahani.studio3d.logic

import com.kahani.studio3d.data.Project
import com.kahani.studio3d.data.Scene
import com.kahani.studio3d.data.SplitMode
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

object SceneLogic {

    private val sentenceEnd = Regex("(?<=[.!?।॥])\\s+|\\n+")

    fun split(script: String, mode: SplitMode): List<String> {
        val clean = script.replace("\r", "").trim()
        if (clean.isEmpty()) return emptyList()
        return when (mode) {
            SplitMode.LINE -> clean.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            SplitMode.SENTENCE -> sentences(clean)
            SplitMode.SMART -> {
                // group sentences until ~20 words (~8 sec of narration)
                val out = ArrayList<String>()
                val cur = StringBuilder()
                for (s in sentences(clean)) {
                    val words = wordCount(cur.toString()) + wordCount(s)
                    if (cur.isNotEmpty() && words > 22) { out += cur.toString().trim(); cur.clear() }
                    cur.append(s).append(' ')
                }
                if (cur.isNotBlank()) out += cur.toString().trim()
                out
            }
        }.flatMap { breakLong(it) }
    }

    private fun sentences(t: String) = t.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }

    /** Very long sentences become several scenes so visuals keep changing (retention!). */
    private fun breakLong(s: String): List<String> {
        val words = s.split(Regex("\\s+"))
        if (words.size <= 32) return listOf(s)
        // try commas first
        val parts = s.split(Regex("(?<=[,;:])\\s+"))
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (p in parts) {
            if (cur.isNotEmpty() && wordCount(cur.toString()) + wordCount(p) > 20) { out += cur.toString().trim(); cur.clear() }
            cur.append(p).append(' ')
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.flatMap { part ->
            val w = part.split(Regex("\\s+"))
            if (w.size <= 32) listOf(part) else w.chunked(18).map { it.joinToString(" ") }
        }
    }

    fun wordCount(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }

    private fun weight(s: String) = s.count { !it.isWhitespace() } + 6.0 // + pause

    fun buildScenes(texts: List<String>, old: List<Scene>): List<Scene> = texts.mapIndexed { i, t ->
        val prev = old.getOrNull(i)
        // keep image/prompt of same index if text matches (re-split keeps work)
        if (prev != null && prev.text == t) prev
        else Scene(id = UUID.randomUUID().toString().take(8), text = t, imagePath = prev?.imagePath)
    }

    /**
     * Distributes the voiceover duration over scenes in proportion to how much text
     * each scene has, then (if pauses are known) snaps each cut onto the nearest pause.
     * Scenes with locked=true keep their manual duration.
     */
    fun autoTime(p: Project, pauses: List<Long> = emptyList()): List<Scene> {
        val scenes = p.scenes
        if (scenes.isEmpty()) return scenes
        val total = if (p.voiceDurationMs > 0) p.voiceDurationMs else scenes.sumOf { max(2500L, (weight(it.text) * 70).toLong()) }
        val lockedSum = scenes.filter { it.locked }.sumOf { it.durationMs }
        val freeTotal = max(total - lockedSum, scenes.count { !it.locked } * 800L)
        val weights = scenes.map { if (it.locked) 0.0 else weight(it.text) }
        val wSum = weights.sum().takeIf { it > 0 } ?: 1.0

        // ideal cut positions
        val durs = scenes.mapIndexed { i, s -> if (s.locked) s.durationMs.toDouble() else freeTotal * weights[i] / wSum }
        val cuts = ArrayList<Long>()
        var acc = 0.0
        for (i in 0 until durs.size - 1) { acc += durs[i]; cuts += acc.toLong() }

        // snap to pauses (within 1.2 s), keep monotonic & min 800 ms per scene
        val snapped = ArrayList<Long>()
        var last = 0L
        for ((i, c) in cuts.withIndex()) {
            val canSnap = !scenes[i].locked && !scenes[i + 1].locked
            var best = c
            if (canSnap && pauses.isNotEmpty()) {
                val near = pauses.minByOrNull { abs(it - c) }!!
                if (abs(near - c) < 1200) best = near
            }
            best = best.coerceAtLeast(last + 800)
            snapped += best
            last = best
        }
        val bounds = listOf(0L) + snapped + listOf(max(total, last + 800))
        return scenes.mapIndexed { i, s -> s.copy(durationMs = bounds[i + 1] - bounds[i]) }
    }
}
