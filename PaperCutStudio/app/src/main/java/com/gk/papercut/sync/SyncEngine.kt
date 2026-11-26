package com.gk.papercut.sync

import com.gk.papercut.data.Scene
import com.gk.papercut.data.ScriptPlanner
import com.gk.papercut.data.WordTime
import kotlin.math.abs

/**
 * Offline voice-over sync.
 *
 * With an SRT: word times come straight from the subtitle cues.
 * Without: the voice-over's loudness envelope is analysed, pauses are detected, words are spread
 * over the *spoken* time only (pauses skipped) and every scene cut is snapped to the nearest real pause.
 */
object SyncEngine {

    private const val MIN_PAUSE_SEC = 0.12
    private const val SNAP_WINDOW_SEC = 1.5
    /** Visuals change a little before the voice starts the new scene. */
    private const val LEAD_SEC = 0.12

    fun syncWithEnvelope(scenes: List<Scene>, env: AudioTools.Envelope): List<Scene> {
        if (scenes.isEmpty()) return scenes
        val speech = speechMask(env)
        val pauses = pauses(speech, env.hopSec)
        val firstSpeech = speech.indexOfFirst { it }.coerceAtLeast(0) * env.hopSec
        val lastSpeech = (speech.indexOfLast { it }.takeIf { it >= 0 }?.plus(1) ?: speech.size) * env.hopSec

        val sceneWeights = scenes.map { s -> ScriptPlanner.words(s.text).sumOf { weight(it) } }
        // Initial cut points by spoken time, then snap to real pauses.
        val spokenTimeline = SpokenTimeline(speech, env.hopSec, firstSpeech, lastSpeech)
        val total = sceneWeights.sum().coerceAtLeast(1.0)
        var acc = 0.0
        val cuts = mutableListOf<Double>()
        for (k in 0 until scenes.size - 1) {
            acc += sceneWeights[k]
            val t = spokenTimeline.timeAt(acc / total)
            val snapped = pauses.minByOrNull { abs(it.second - t) }
                ?.takeIf { abs(it.second - t) <= SNAP_WINDOW_SEC }?.second ?: t
            cuts += snapped
        }
        // Keep monotonic with at least 0.8 s per scene.
        for (k in cuts.indices) {
            val prev = if (k == 0) firstSpeech else cuts[k - 1]
            if (cuts[k] < prev + 0.8) cuts[k] = prev + 0.8
        }
        val bounds = listOf(firstSpeech) + cuts + listOf(lastSpeech)
        return scenes.mapIndexed { k, s ->
            val a = bounds[k]; val b = bounds[k + 1].coerceAtLeast(a + 0.1)
            val words = spreadWords(ScriptPlanner.words(s.text), SpokenTimeline(speech, env.hopSec, a, b))
            s.copy(
                start = if (k == 0) 0.0 else (a - LEAD_SEC).coerceAtLeast(0.0),
                end = if (k == scenes.size - 1) env.duration else (b - LEAD_SEC),
                words = words,
            )
        }
    }

    fun syncWithSrt(scenes: List<Scene>, srt: String, duration: Double): List<Scene> {
        val cues = parseSrt(srt)
        if (cues.isEmpty()) error("No subtitles found in the SRT file")
        val srtWords = cues.flatMap { c ->
            val ws = ScriptPlanner.words(c.text)
            val tw = ws.sumOf { weight(it) }.coerceAtLeast(1.0)
            var t = c.start
            ws.map { w -> val d = (c.end - c.start) * weight(w) / tw; WordTime(w, t, t + d).also { t += d } }
        }
        val allScriptWords = scenes.flatMap { ScriptPlanner.words(it.text) }
        val n = allScriptWords.size.coerceAtLeast(1)
        var idx = 0
        val timed = scenes.map { s ->
            s.copy(words = ScriptPlanner.words(s.text).map { w ->
                val j = (idx.toLong() * srtWords.size / n).toInt().coerceIn(0, srtWords.lastIndex)
                idx++
                WordTime(w, srtWords[j].start, srtWords[j].end)
            })
        }
        val end = maxOf(duration, srtWords.last().end)
        return timed.mapIndexed { k, s ->
            val start = if (k == 0) 0.0 else (s.words.firstOrNull()?.start ?: 0.0) - LEAD_SEC
            val next = timed.getOrNull(k + 1)?.words?.firstOrNull()?.start?.minus(LEAD_SEC) ?: end
            s.copy(start = start.coerceAtLeast(0.0), end = maxOf(next, start + 0.3))
        }
    }

    /** Fallback when there is no voice-over yet: ~2.6 words per second. */
    fun syncByReadingSpeed(scenes: List<Scene>): List<Scene> {
        var t = 0.0
        return scenes.map { s ->
            val ws = ScriptPlanner.words(s.text)
            val d = (ws.size / 2.6).coerceAtLeast(2.0)
            val words = spreadWords(ws, null, t + 0.2, t + d - 0.2)
            s.copy(start = t, end = t + d, words = words).also { t += d }
        }
    }

    /** Shift one scene boundary (manual fine-tuning); words inside both scenes are re-spread. */
    fun nudgeBoundary(scenes: List<Scene>, index: Int, delta: Double): List<Scene> {
        if (index <= 0 || index >= scenes.size) return scenes
        val prev = scenes[index - 1]; val cur = scenes[index]
        val newCut = (cur.start + delta).coerceIn(prev.start + 0.5, cur.end - 0.5)
        fun respread(s: Scene, a: Double, b: Double): Scene {
            val old = s.words
            if (old.isEmpty() || s.end <= s.start) return s.copy(start = a, end = b)
            val k = (b - a) / (s.end - s.start)
            return s.copy(start = a, end = b, words = old.map { w -> WordTime(w.text, a + (w.start - s.start) * k, a + (w.end - s.start) * k) })
        }
        return scenes.toMutableList().also {
            it[index - 1] = respread(prev, prev.start, newCut)
            it[index] = respread(cur, newCut, cur.end)
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun weight(w: String) = (w.count { it.isLetterOrDigit() } + 2).toDouble() +
        if (w.last() in ".!?।,;:") 3.0 else 0.0

    private fun speechMask(env: AudioTools.Envelope): BooleanArray {
        if (env.rms.isEmpty()) return BooleanArray(0)
        val sorted = env.rms.sortedArray()
        val floor = sorted[(sorted.size * 0.1).toInt()]
        val peak = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.lastIndex)]
        val thr = maxOf(floor * 2.5f, peak * 0.06f, 0.002f)
        val raw = BooleanArray(env.rms.size) { env.rms[it] > thr }
        // Close tiny gaps (< MIN_PAUSE) so syllable dips are not pauses.
        val minGap = (MIN_PAUSE_SEC / env.hopSec).toInt()
        var i = 0
        while (i < raw.size) {
            if (!raw[i]) {
                var j = i
                while (j < raw.size && !raw[j]) j++
                if (i > 0 && j < raw.size && j - i < minGap) for (k in i until j) raw[k] = true
                i = j
            } else i++
        }
        return raw
    }

    /** Pauses as (start, end) in seconds. */
    private fun pauses(speech: BooleanArray, hop: Double): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var i = 0
        while (i < speech.size) {
            if (!speech[i]) {
                var j = i
                while (j < speech.size && !speech[j]) j++
                if (i > 0 && j < speech.size) out += i * hop to j * hop
                i = j
            } else i++
        }
        return out
    }

    /** Maps a 0..1 fraction of spoken content to a time, skipping silent frames. */
    private class SpokenTimeline(val speech: BooleanArray, val hop: Double, val a: Double, val b: Double) {
        private val i0 = (a / hop).toInt().coerceIn(0, speech.size)
        private val i1 = (b / hop).toInt().coerceIn(i0, speech.size)
        private val spoken = (i0 until i1).count { speech[it] }
        fun timeAt(frac: Double): Double {
            if (spoken == 0) return a + (b - a) * frac
            val target = frac * spoken
            var c = 0
            for (i in i0 until i1) {
                if (speech[i]) { c++; if (c >= target) return i * hop }
            }
            return b
        }
    }

    private fun spreadWords(ws: List<String>, tl: SpokenTimeline?, a: Double = tl?.a ?: 0.0, b: Double = tl?.b ?: 1.0): List<WordTime> {
        val weights = ws.map { weight(it) }
        val total = weights.sum().coerceAtLeast(1.0)
        var acc = 0.0
        return ws.mapIndexed { i, w ->
            val f0 = acc / total; acc += weights[i]; val f1 = acc / total
            val t0 = tl?.timeAt(f0) ?: (a + (b - a) * f0)
            val t1 = tl?.timeAt(f1) ?: (a + (b - a) * f1)
            WordTime(w, t0, maxOf(t1, t0 + 0.05))
        }
    }

    class Cue(val start: Double, val end: Double, val text: String)

    fun parseSrt(srt: String): List<Cue> {
        val re = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")
        fun t(h: String, m: String, s: String, ms: String) = h.toInt() * 3600.0 + m.toInt() * 60 + s.toInt() + ms.padEnd(3, '0').take(3).toInt() / 1000.0
        return srt.replace("\r", "").split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val lines = block.trim().lines()
            val li = lines.indexOfFirst { re.containsMatchIn(it) }
            if (li < 0) return@mapNotNull null
            val m = re.find(lines[li])!!.groupValues
            val text = lines.drop(li + 1).joinToString(" ").replace(Regex("<[^>]+>"), "").trim()
            if (text.isEmpty()) null else Cue(t(m[1], m[2], m[3], m[4]), t(m[5], m[6], m[7], m[8]), text)
        }
    }
}
