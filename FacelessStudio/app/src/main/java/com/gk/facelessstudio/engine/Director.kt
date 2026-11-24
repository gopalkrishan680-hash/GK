package com.gk.facelessstudio.engine

import com.gk.facelessstudio.model.Pacing
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.Scene
import com.gk.facelessstudio.model.Backdrop
import com.gk.facelessstudio.model.CamMove
import com.gk.facelessstudio.model.Deco
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.model.TextFx
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
        val groups = group(analysis.chunks, times, mergeQuestions = !project.lineImages)

        val images = project.images.size
        val total = times.last().second
        val scenes = mutableListOf<Scene>()
        val history = ArrayDeque<SceneType>()
        var lastTransition = Transition.CUT
        val variety = Variety(project.seed, project.pacing)

        groups.forEachIndexed { gi, g ->
            val first = g.chunks.first()
            val text = g.chunks.joinToString(" ") { it.text }
            val chapter = first.chapterIndex
            val mid = (g.start + g.end) / 2
            val imageIdx = when {
                images == 0 -> -1
                // images were generated one per script line -> each scene gets the picture made for its own words
                project.lineImages -> min(images - 1, g.startIdx / project.imageStep.coerceAtLeast(1))
                    .takeIf { project.images[it].isNotBlank() } ?: -1
                else -> min(images - 1, (mid.toDouble() / max(1, total) * images).toInt())
            }

            var type: SceneType
            var lines: List<String> = emptyList()
            var title = ""
            // only real names / numbers pop on screen, never filler words
            val emphasis = ScriptAnalyzer.strongWords(text, 2)

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

            val hasImg = imageIdx >= 0
            // the picture was made for exactly this line: it must be on screen
            val ownImage = project.lineImages && hasImg
            val short = first.words.size <= 4
            val strong = ScriptAnalyzer.strongWords(text, 3)
            // layouts that fit what this line says
            val preferred = buildList {
                if (forced != null) add(forced)
                if (g.isQuestionGroup) { add(SceneType.QUESTION_LIST); add(SceneType.SEARCH_BAR) }
                if (first.chapterStart && chapter >= 0) { add(SceneType.CHAPTER); if (analysis.chapters.size >= 2) add(SceneType.CHECKLIST) }
                if (vs != null) add(SceneType.VERSUS)
                if (quote != null) { add(SceneType.QUOTE); if (!ownImage) add(SceneType.CHAT_CARD) }
                if (ScriptAnalyzer.detectSpeaker(text) != null && !ownImage) add(SceneType.CHAT_CARD)
                if (years != null) add(SceneType.TIMELINE)
                if (percent != null) { add(SceneType.DONUT); add(SceneType.GAUGE) }
                if (numbers != null) add(SceneType.BAR_CHART)
                if (stat != null) add(SceneType.BIG_STAT)
                if (date != null) add(SceneType.DATE_STAMP)
                if (list != null) { add(SceneType.MINDMAP); add(SceneType.STEPS); if (!ownImage) add(SceneType.STICKY_NOTES) }
                if (cause != null) add(SceneType.SPLIT_ARROW)
                if (first.isQuestion && !ownImage) add(SceneType.SEARCH_BAR)
            }
            // short punchy lines suit big-type layouts
            val soft = if (!short) emptyList() else buildList {
                if (hasImg) { if (strong.isNotEmpty()) add(SceneType.TEXT_MASK); add(SceneType.HEADLINE_IMAGE) }
                if (!ownImage) add(SceneType.KINETIC)
            }
            // layouts that show the picture big
            val pictureLayouts = if (!hasImg) emptyList() else buildList {
                addAll(listOf(SceneType.KEN_BURNS, SceneType.IMAGE_BANNER, SceneType.HEADLINE_IMAGE, SceneType.POLAROID, SceneType.PHONE,
                    SceneType.FILMSTRIP, SceneType.MAGNIFY, SceneType.NEWS_FLASH, SceneType.CIRCLE_REVEAL, SceneType.SPLIT_DIAGONAL, SceneType.KEY_POINTS))
                if (strong.isNotEmpty()) add(SceneType.TEXT_MASK)
            }
            // text layouts (with a picture they are drawn over it)
            val textLayouts = buildList {
                addAll(listOf(SceneType.KINETIC, SceneType.SPOTLIGHT, SceneType.PAPER_NOTE, SceneType.CHAT_CARD))
                if (!hasImg) add(SceneType.KEY_POINTS)
                if (ScriptAnalyzer.keywords(text, 5).size >= 4) add(SceneType.WORD_CLOUD)
                if (strong.size >= 2) add(SceneType.STICKY_NOTES)
            }
            type = if (forced != null) forced.also { variety.use(it) }
            else if (ownImage) variety.layout(preferred.filter { !it.needsImage || hasImg }, pictureLayouts + textLayouts, soft, first = pictureLayouts)
            else variety.layout(preferred.filter { !it.needsImage || hasImg }, pictureLayouts + textLayouts, soft)

            val filled = fill(type, text, g.chunks.map { it.text }, g.isQuestionGroup, project, analysis.chapters, chapter,
                list, stat, date, vs, quote, cause, percent, numbers, years)
            lines = filled.first; title = filled.second

            val transition = when {
                gi == 0 -> Transition.FADE
                first.forceCut -> Transition.CUT
                else -> variety.transition(lastTransition, first.chapterStart)
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
            ).let { sc ->
                // photo layouts keep their picture; text layouts may get any backdrop
                // a line with its own picture always shows it, even behind text layouts
                val look = variety.look(type, when {
                    ownImage && !type.needsImage -> listOf(Backdrop.IMAGE)
                    hasImg -> Backdrop.entries
                    else -> Backdrop.entries.filter { it != Backdrop.IMAGE }
                })
                sc.copy(fx = look.fx, bg = look.bg, cam = look.cam, deco = look.deco)
            }
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
        val taken = project.scenes.filterIndexed { i, _ -> i != index }.map { it.combo }.toSet()
        var next = s
        for (attempt in 0 until 80) {
            next = s.copy(variant = rnd.nextInt(1_000_000), transition = if (index == 0) Transition.FADE else tr,
                fx = TextFx.entries.random(rnd), bg = Backdrop.entries.random(rnd), cam = CamMove.entries.random(rnd), deco = Deco.entries.random(rnd))
            if (next.combo !in taken && next.combo != s.combo) break
        }
        val updated = project.scenes.toMutableList()
        updated[index] = next
        return project.copy(scenes = updated)
    }

    /** Content (lines, title) every layout needs, from what the analyzer found in the line. */
    fun fill(
        type: SceneType, text: String, groupLines: List<String>, questionGroup: Boolean, project: Project,
        chapters: List<String>, chapter: Int,
        list: Pair<String, List<String>>? = ScriptAnalyzer.detectList(text), stat: ScriptAnalyzer.Stat? = ScriptAnalyzer.detectStat(text),
        date: List<String>? = ScriptAnalyzer.detectDate(text), vs: Pair<String, String>? = ScriptAnalyzer.detectVersus(text),
        quote: String? = ScriptAnalyzer.detectQuote(text), cause: Pair<String, String>? = ScriptAnalyzer.detectCause(text),
        percent: Pair<Float, String>? = ScriptAnalyzer.detectPercent(text), numbers: List<Pair<String, Double>>? = ScriptAnalyzer.detectNumbers(text),
        years: List<Pair<String, String>>? = ScriptAnalyzer.detectYears(text),
    ): Pair<List<String>, String> {
        val cap = { w: String -> w.replaceFirstChar { it.uppercase() } }
        val kw = { n: Int -> ScriptAnalyzer.keywords(text, n).map(cap) }
        val ph = { n: Int -> ScriptAnalyzer.phrase(text, n).uppercase() }
        return when (type) {
            SceneType.QUESTION_LIST -> Pair(if (questionGroup) groupLines else list?.second ?: groupLines, chapters.getOrNull(chapter)?.uppercase() ?: "")
            SceneType.CHECKLIST -> Pair(chapters.ifEmpty { kw(4) }, project.title.ifBlank { "FULL GUIDE" }.uppercase())
            SceneType.CHAPTER -> Pair(emptyList(), chapters.getOrNull(chapter) ?: ScriptAnalyzer.phrase(text, 4))
            SceneType.MINDMAP -> Pair(list?.second ?: kw(3), (list?.first?.ifBlank { null } ?: ScriptAnalyzer.phrase(text, 5)).uppercase())
            SceneType.STEPS, SceneType.STICKY_NOTES -> Pair(list?.second?.take(5) ?: ScriptAnalyzer.strongWords(text, 4).map(cap).ifEmpty { kw(3) }, (list?.first?.ifBlank { null } ?: ScriptAnalyzer.phrase(text, 5)).uppercase())
            SceneType.BIG_STAT -> stat?.let { Pair(listOf(it.prefix, it.number.toString(), it.decimals.toString(), it.grouped.toString(), it.suffix), it.label) } ?: Pair(emptyList(), ph(4))
            SceneType.DATE_STAMP -> Pair(date ?: listOf(ScriptAnalyzer.headline(text, 2), ""), ph(4))
            SceneType.VERSUS -> Pair((vs?.toList() ?: ScriptAnalyzer.keywords(text, 2).let { listOf(it.getOrElse(0) { "THIS" }, it.getOrElse(1) { "THAT" }) }).map { it.uppercase() }, "")
            SceneType.QUOTE -> Pair(listOf(quote ?: text), "")
            SceneType.CHAT_CARD -> Pair(listOf(quote ?: text), ScriptAnalyzer.detectSpeaker(text) ?: chapters.getOrNull(chapter) ?: project.title.ifBlank { "Did you know?" })
            SceneType.DONUT, SceneType.GAUGE -> percent?.let { Pair(listOf(it.first.toString()), it.second) } ?: Pair(listOf("50"), ph(4))
            SceneType.BAR_CHART -> Pair(numbers?.map { "${it.first}|${it.second}" } ?: emptyList(), ph(5))
            SceneType.TIMELINE -> Pair(years?.map { "${it.first}|${it.second}" } ?: emptyList(), chapters.getOrNull(chapter)?.uppercase() ?: "")
            SceneType.SPLIT_ARROW -> Pair((cause?.toList() ?: ScriptAnalyzer.keywords(text, 2)).map { it.uppercase() }, "")
            // bullets only from real names/numbers; with none the layout shows the line itself
            SceneType.KEY_POINTS, SceneType.SPLIT_DIAGONAL -> Pair(ScriptAnalyzer.strongWords(text, 3).map(cap), ph(5))
            // a question group: first question is typed, the next ones show up as results
            SceneType.SEARCH_BAR -> if (questionGroup && groupLines.size > 1) Pair(groupLines.drop(1).take(3), groupLines.first())
                else Pair(ScriptAnalyzer.strongWords(text, 3).map(cap), text)
            SceneType.WORD_CLOUD -> Pair(ScriptAnalyzer.keywords(text, 7), "")
            SceneType.TEXT_MASK -> Pair(listOf(ScriptAnalyzer.phrase(text, 8)), (ScriptAnalyzer.strongWords(text, 1).firstOrNull() ?: ScriptAnalyzer.keywords(text, 1).firstOrNull() ?: ScriptAnalyzer.phrase(text, 2)).uppercase())
            SceneType.KEN_BURNS, SceneType.POLAROID, SceneType.IMAGE_BANNER, SceneType.PHONE, SceneType.SPOTLIGHT, SceneType.PAPER_NOTE,
            SceneType.FILMSTRIP, SceneType.MAGNIFY, SceneType.CIRCLE_REVEAL -> Pair(emptyList(), ph(5))
            // short lines are shown whole ("KYA AAP JAANTE HO"), long ones as their strongest run of words
            SceneType.HEADLINE_IMAGE -> Pair(emptyList(), ph(6))
            SceneType.NEWS_FLASH -> Pair(emptyList(), ph(7))
            SceneType.OUTRO -> Pair(emptyList(), project.handle)
            SceneType.KINETIC -> Pair(emptyList(), "")
        }
    }

    // ---------------- helpers ----------------

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
    private fun group(chunks: List<Chunk>, times: List<Pair<Long, Long>>, mergeQuestions: Boolean = true): List<Group> {
        val out = mutableListOf<Group>()
        var i = 0
        while (i < chunks.size) {
            if (mergeQuestions && chunks[i].isQuestion && chunks[i].forced == null) {
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
