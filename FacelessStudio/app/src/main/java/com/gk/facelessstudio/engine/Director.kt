package com.gk.facelessstudio.engine

import com.gk.facelessstudio.model.Pacing
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.Scene
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.model.Transition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What we learned from the voiceover file. */
data class VoiceInfo(
    val durationMs: Long,
    val speechStartMs: Long,
    val speechEndMs: Long,
    /** Silent gaps: centre time and length in ms. */
    val pauses: List<Pair<Long, Long>>,
)

/**
 * The "auto editor": turns script + voice timing into a list of scenes, picking a layout,
 * transition, image and animation variant for each so consecutive scenes never look alike.
 */
object Director {

    const val OUTRO_MS = 3500L
    private const val WORDS_PER_SEC = 2.5f

    fun maxWords(project: Project) = (project.pacing.maxSceneSec * WORDS_PER_SEC).toInt().coerceAtLeast(5)

    /** The spoken pieces exactly as [build] will cut them (used to make one AI image per piece). */
    fun analysisFor(project: Project) = ScriptAnalyzer.analyze(project.script, maxWords(project))

    fun build(project: Project, voice: VoiceInfo?): Project {
        val analysis = analysisFor(project)
        if (analysis.chunks.isEmpty()) return project.copy(scenes = emptyList(), durationMs = 0, chapters = analysis.chapters)

        val rnd = Random(project.seed)
        val times = timeChunks(analysis.chunks, voice)
        val groups = group(analysis.chunks, times)

        val images = project.images.size
        val total = times.last().second
        val scenes = mutableListOf<Scene>()
        val history = ArrayDeque<SceneType>()
        var lastTransition = Transition.CUT

        groups.forEachIndexed { gi, g ->
            val first = g.chunks.first()
            val text = g.chunks.joinToString(" ") { it.text }
            val chapter = first.chapterIndex
            val mid = (g.start + g.end) / 2
            val imageIdx = when {
                images == 0 -> -1
                // images were generated one per script line -> each scene gets the picture made for its own words
                project.lineImages -> min(images - 1, g.startIdx / project.imageStep.coerceAtLeast(1))
                else -> min(images - 1, (mid.toDouble() / max(1, total) * images).toInt())
            }

            var type: SceneType
            var lines: List<String> = emptyList()
            var title = ""
            val emphasis = ScriptAnalyzer.keywords(text, 2)

            val forced = first.forced
            val list = ScriptAnalyzer.detectList(text)
            val stat = ScriptAnalyzer.detectStat(text)
            val date = ScriptAnalyzer.detectDate(text)
            val vs = ScriptAnalyzer.detectVersus(text)
            val quote = ScriptAnalyzer.detectQuote(text)
            val cause = ScriptAnalyzer.detectCause(text)
            val percent = ScriptAnalyzer.detectPercent(text)
            val numbers = ScriptAnalyzer.detectNumbers(text)
            val years = ScriptAnalyzer.detectYears(text)

            type = when {
                forced != null -> forced
                g.isQuestionGroup -> SceneType.QUESTION_LIST
                first.chapterStart && analysis.chapters.size >= 2 && chapter >= 0 && rnd.nextFloat() < 0.6f -> SceneType.CHECKLIST
                first.chapterStart && chapter >= 0 -> SceneType.CHAPTER
                vs != null -> SceneType.VERSUS
                quote != null -> SceneType.QUOTE
                list != null -> SceneType.MINDMAP
                years != null -> SceneType.TIMELINE
                percent != null && history.lastOrNull() != SceneType.DONUT -> SceneType.DONUT
                numbers != null && history.lastOrNull() != SceneType.BAR_CHART -> SceneType.BAR_CHART
                stat != null && history.lastOrNull() != SceneType.BIG_STAT -> SceneType.BIG_STAT
                date != null && history.lastOrNull() != SceneType.DATE_STAMP -> SceneType.DATE_STAMP
                cause != null && rnd.nextFloat() < 0.55f -> SceneType.SPLIT_ARROW
                first.words.size <= 4 -> if (rnd.nextFloat() < 0.75f) SceneType.KINETIC else SceneType.SPOTLIGHT
                else -> pickGeneric(rnd, history, images > 0, if (project.lineImages) 1f else images.toFloat() / max(1, groups.size))
            }
            if (type.needsImage && imageIdx < 0) type = pickGeneric(rnd, history, false, 0f)

            when (type) {
                SceneType.QUESTION_LIST -> {
                    lines = if (g.isQuestionGroup) g.chunks.map { it.text } else list?.second ?: g.chunks.map { it.text }
                    title = analysis.chapters.getOrNull(chapter)?.uppercase() ?: ""
                }
                SceneType.CHECKLIST -> { lines = analysis.chapters; title = project.title.ifBlank { "FULL GUIDE" }.uppercase() }
                SceneType.CHAPTER -> title = analysis.chapters.getOrNull(chapter) ?: ScriptAnalyzer.headline(text, 3)
                SceneType.MINDMAP -> {
                    val l = list ?: ScriptAnalyzer.detectList(text)
                    title = (l?.first?.takeIf { it.isNotBlank() } ?: ScriptAnalyzer.headline(text, 4)).uppercase()
                    lines = l?.second ?: ScriptAnalyzer.keywords(text, 3).map { it.replaceFirstChar { c -> c.uppercase() } }
                }
                SceneType.BIG_STAT -> {
                    val s = stat ?: ScriptAnalyzer.detectStat(text)
                    if (s != null) {
                        lines = listOf(s.prefix, s.number.toString(), s.decimals.toString(), s.grouped.toString(), s.suffix)
                        title = s.label
                    } else type = SceneType.KINETIC
                }
                SceneType.DATE_STAMP -> {
                    lines = date ?: listOf(ScriptAnalyzer.headline(text, 2), "")
                    title = ScriptAnalyzer.headline(text, 3)
                }
                SceneType.VERSUS -> {
                    val p = vs ?: ScriptAnalyzer.keywords(text, 2).let { Pair(it.getOrElse(0) { "THIS" }, it.getOrElse(1) { "THAT" }) }
                    lines = listOf(p.first.uppercase(), p.second.uppercase())
                }
                SceneType.QUOTE -> lines = listOf(quote ?: text)
                SceneType.DONUT -> {
                    val pc = percent ?: ScriptAnalyzer.detectPercent(text)
                    if (pc != null) { lines = listOf(pc.first.toString()); title = pc.second } else type = SceneType.KEY_POINTS
                }
                SceneType.BAR_CHART -> {
                    val n = numbers ?: ScriptAnalyzer.detectNumbers(text)
                    if (n != null) { lines = n.map { "${it.first}|${it.second}" }; title = ScriptAnalyzer.headline(text, 4) } else type = SceneType.KEY_POINTS
                }
                SceneType.TIMELINE -> {
                    val y = years ?: ScriptAnalyzer.detectYears(text)
                    if (y != null) { lines = y.map { "${it.first}|${it.second}" }; title = analysis.chapters.getOrNull(chapter)?.uppercase() ?: "" } else type = SceneType.KEY_POINTS
                }
                else -> {}
            }
            when (type) {
                SceneType.KEY_POINTS -> {
                    title = ScriptAnalyzer.headline(text, 4)
                    lines = ScriptAnalyzer.keywords(text, 3).map { it.replaceFirstChar { c -> c.uppercase() } }
                }
                SceneType.KEN_BURNS, SceneType.POLAROID -> title = ScriptAnalyzer.headline(text, 4)
                SceneType.SPLIT_ARROW -> {
                    val c = cause ?: ScriptAnalyzer.keywords(text, 2).let { Pair(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }) }
                    lines = listOf(c.first.uppercase(), c.second.uppercase())
                }
                SceneType.IMAGE_BANNER, SceneType.HEADLINE_IMAGE, SceneType.PHONE, SceneType.SPOTLIGHT, SceneType.PAPER_NOTE ->
                    title = (analysis.chapters.getOrNull(chapter)?.takeIf { type == SceneType.IMAGE_BANNER && rnd.nextBoolean() }
                        ?: ScriptAnalyzer.headline(text, if (type == SceneType.HEADLINE_IMAGE) 2 else 4)).uppercase()
                else -> {}
            }

            val transition = when {
                gi == 0 -> Transition.FADE
                first.forceCut -> Transition.CUT
                first.chapterStart -> listOf(Transition.FLASH, Transition.GLITCH, Transition.ZOOM_PUNCH, Transition.SHUTTER).random(rnd)
                else -> pickTransition(rnd, project.pacing, lastTransition)
            }
            lastTransition = transition

            scenes += Scene(
                text = text,
                startMs = g.start,
                endMs = g.end,
                type = type,
                transition = transition,
                imageIndex = imageIdx,
                variant = rnd.nextInt(1_000_000),
                lines = lines,
                lineStarts = g.lineStarts,
                title = title,
                emphasis = emphasis,
                chapterIndex = chapter,
            )
            history.addLast(type); if (history.size > 3) history.removeFirst()
        }

        var duration = max(total, voice?.durationMs ?: 0L)
        if (project.handle.isNotBlank()) {
            scenes += Scene(
                text = "Subscribe", startMs = duration, endMs = duration + OUTRO_MS,
                type = SceneType.OUTRO, transition = Transition.ZOOM_PUNCH, variant = rnd.nextInt(1_000_000),
                title = project.handle, imageIndex = if (images > 0) images - 1 else -1,
            )
            duration += OUTRO_MS
        } else if (scenes.isNotEmpty()) {
            // stretch last scene to the end of the audio
            scenes[scenes.lastIndex] = scenes.last().copy(endMs = duration)
        }
        return project.copy(scenes = scenes, durationMs = duration, chapters = analysis.chapters)
    }

    /** Re-roll a single scene's look while keeping its timing and content. */
    fun reroll(project: Project, index: Int): Project {
        val s = project.scenes.getOrNull(index) ?: return project
        val rnd = Random(s.variant.toLong() * 31 + System.nanoTime())
        val tr = pickTransition(rnd, project.pacing, s.transition)
        val updated = project.scenes.toMutableList()
        updated[index] = s.copy(variant = rnd.nextInt(1_000_000), transition = if (index == 0) Transition.FADE else tr)
        return project.copy(scenes = updated)
    }

    // ---------------- helpers ----------------

    private val IMAGE_POOL = listOf(
        SceneType.KEN_BURNS to 5f, SceneType.IMAGE_BANNER to 2.5f, SceneType.HEADLINE_IMAGE to 2f,
        SceneType.POLAROID to 1.2f, SceneType.PHONE to 1.2f, SceneType.KEY_POINTS to 2f,
    )
    private val TEXT_POOL = listOf(
        SceneType.KINETIC to 2.5f, SceneType.SPOTLIGHT to 2f, SceneType.PAPER_NOTE to 1.8f, SceneType.KEY_POINTS to 2f,
    )

    private fun pickGeneric(rnd: Random, history: ArrayDeque<SceneType>, hasImages: Boolean, imageRatio: Float): SceneType {
        val imgChance = if (!hasImages) 0f else (0.6f + imageRatio * 0.4f).coerceIn(0.65f, 0.9f)
        val lastTwoText = history.takeLast(2).let { it.size == 2 && it.none { t -> t.needsImage } }
        val useImage = hasImages && (lastTwoText || rnd.nextFloat() < imgChance)
        val pool = if (useImage) IMAGE_POOL else TEXT_POOL
        val last = history.lastOrNull()
        val candidates = pool.filter { it.first != last && it.second > 0f }
        return weighted(rnd, candidates.ifEmpty { pool })
    }

    private fun weighted(rnd: Random, items: List<Pair<SceneType, Float>>): SceneType {
        val sum = items.sumOf { it.second.toDouble() }.toFloat()
        var r = rnd.nextFloat() * sum
        for ((t, w) in items) { r -= w; if (r <= 0f) return t }
        return items.last().first
    }

    private fun pickTransition(rnd: Random, pacing: Pacing, last: Transition): Transition {
        val pool = when (pacing) {
            Pacing.CALM -> listOf(Transition.FADE to 5f, Transition.SLIDE_LEFT to 2f, Transition.CIRCLE to 1f, Transition.ZOOM_PUNCH to 1f, Transition.SLIDE_UP to 1f)
            Pacing.BALANCED -> listOf(
                Transition.FADE to 3f, Transition.ZOOM_PUNCH to 3f, Transition.SLIDE_LEFT to 2f, Transition.WHIP to 2f,
                Transition.SLIDE_UP to 1.5f, Transition.CIRCLE to 1f, Transition.CUT to 2f, Transition.SPIN to 1f,
                Transition.SHUTTER to 1f, Transition.FLASH to 0.7f, Transition.GLITCH to 0.7f,
            )
            Pacing.ENERGETIC -> listOf(
                Transition.ZOOM_PUNCH to 3f, Transition.WHIP to 3f, Transition.CUT to 3f, Transition.GLITCH to 2f,
                Transition.FLASH to 1.5f, Transition.SPIN to 1.5f, Transition.SHUTTER to 1.5f, Transition.SLIDE_UP to 1f,
            )
        }.filter { it.first != last || it.first == Transition.CUT }
        val sum = pool.sumOf { it.second.toDouble() }.toFloat()
        var r = rnd.nextFloat() * sum
        for ((t, w) in pool) { r -= w; if (r <= 0f) return t }
        return pool.last().first
    }

    /** Distributes chunks over the speech by text length, then snaps every cut to the nearest real pause. */
    fun timeChunks(chunks: List<Chunk>, voice: VoiceInfo?): List<Pair<Long, Long>> {
        val weights = chunks.map { it.weight }
        val totalW = weights.sum()
        val start: Long
        val end: Long
        if (voice == null || voice.durationMs <= 0) {
            start = 0L
            val words = chunks.sumOf { it.words.size }
            end = (words / WORDS_PER_SEC * 1000).toLong() + 600
        } else {
            start = voice.speechStartMs.coerceAtLeast(0)
            end = voice.speechEndMs.coerceAtMost(voice.durationMs).coerceAtLeast(start + 500)
        }
        val span = (end - start).toFloat()
        val bounds = LongArray(chunks.size + 1)
        bounds[0] = 0L
        var acc = 0f
        val used = HashSet<Int>()
        for (i in 1 until chunks.size) {
            acc += weights[i - 1]
            val ideal = start + (acc / totalW * span).toLong()
            var best = ideal
            if (voice != null) {
                val win = max(700f, span / chunks.size * 0.45f)
                var bestScore = Float.MAX_VALUE
                voice.pauses.forEachIndexed { pi, (c, len) ->
                    if (pi in used) return@forEachIndexed
                    val d = abs(c - ideal).toFloat()
                    if (d < win && c > bounds[i - 1] + 400) {
                        val score = d - min(len, 600L) * 0.6f
                        if (score < bestScore) { bestScore = score; best = c }
                    }
                }
                voice.pauses.indexOfFirst { it.first == best }.takeIf { it >= 0 }?.let { used += it }
            }
            bounds[i] = best.coerceAtLeast(bounds[i - 1] + 350)
        }
        bounds[chunks.size] = max(voice?.durationMs ?: end, bounds[chunks.size - 1] + 800)
        return (0 until chunks.size).map { Pair(bounds[it], bounds[it + 1]) }
    }

    private class Group(val chunks: List<Chunk>, val start: Long, val end: Long, val isQuestionGroup: Boolean, val lineStarts: List<Float>, val startIdx: Int)

    /** Merges runs of questions into one typewriter-list scene (like a research question list). */
    private fun group(chunks: List<Chunk>, times: List<Pair<Long, Long>>): List<Group> {
        val out = mutableListOf<Group>()
        var i = 0
        while (i < chunks.size) {
            if (chunks[i].isQuestion && chunks[i].forced == null) {
                var j = i
                while (j + 1 < chunks.size && chunks[j + 1].isQuestion && j + 1 - i < 8 &&
                    chunks[j + 1].chapterIndex == chunks[i].chapterIndex && !chunks[j + 1].chapterStart && chunks[j + 1].forced == null) j++
                if (j - i >= 1) {
                    val s = times[i].first; val e = times[j].second
                    val starts = (i..j).map { ((times[it].first - s).toFloat() / max(1L, e - s)) }
                    out += Group(chunks.subList(i, j + 1), s, e, true, starts, i)
                    i = j + 1
                    continue
                }
            }
            out += Group(listOf(chunks[i]), times[i].first, times[i].second, false, listOf(0f), i)
            i++
        }
        return out
    }
}
