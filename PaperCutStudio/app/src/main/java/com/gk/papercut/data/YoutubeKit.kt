package com.gk.papercut.data

import kotlinx.serialization.Serializable
import org.json.JSONObject

@Serializable
data class YoutubeKit(
    val titles: List<String> = emptyList(),
    val description: String = "",
    val chapters: String = "",
    val hashtags: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val thumbnailPrompt: String = "",
)

/**
 * YouTube upload kit. Chapters come from the real voice-over timing; the text parts come from
 * Gemini (if a key is set) or from a ready-made message for ChatGPT/Gemini chat.
 * Rules: description in English, hashtags start with a small letter (#mahabharat, not #Mahabharat).
 */
object YoutubeKitBuilder {

    /** "#Bhishma Pitamah" -> "#bhishmaPitamah": no spaces, first letter small. */
    fun hashtag(raw: String): String {
        val words = raw.trim().removePrefix("#").split(Regex("[\\s_\\-]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        val joined = words.first() + words.drop(1).joinToString("") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        // Keep letters, digits and combining marks (Hindi matras like ा ि ्).
        val clean = joined.filter { it.isLetterOrDigit() || Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() } }
        return if (clean.isEmpty()) "" else "#" + clean.replaceFirstChar { it.lowercaseChar() }
    }

    fun fmt(t: Double): String {
        val s = t.toInt().coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Groups scenes into chapters of >= 10 s (YouTube needs 0:00 first and at least 3 chapters). */
    fun chapterStarts(project: Project): List<Pair<Double, Int>> {
        val out = mutableListOf<Pair<Double, Int>>()
        var lastStart = -100.0
        project.scenes.forEachIndexed { i, s ->
            val start = if (i == 0) 0.0 else s.start
            if (start - lastStart >= 10.0 && (project.audioDuration - start >= 10.0 || i == 0)) { out += start to i; lastStart = start }
        }
        return out
    }

    fun chapters(project: Project, titles: List<String>?): String {
        var starts = chapterStarts(project)
        // Keep the description English: drop chapters the AI gave no title for (YouTube still needs >= 3).
        if (titles != null && titles.size >= 3 && titles.size < starts.size) starts = starts.take(titles.size)
        if (starts.size < 3) return ""
        return starts.mapIndexed { k, (t, i) ->
            val title = titles?.getOrNull(k)?.takeIf { it.isNotBlank() }
                ?: ScriptPlanner.words(project.scenes[i].text).take(6).joinToString(" ").trimEnd(',', '.', '।', '?', '!')
            "${fmt(t)} $title"
        }.joinToString("\n")
    }

    private fun rules(project: Project) = buildString {
        val starts = chapterStarts(project)
        appendLine("You are a YouTube growth expert. Create an upload kit for my paper cut-out animation video \"${project.name}\" (${fmt(project.audioDuration)} long, ${project.aspect.label}).")
        appendLine("Rules:")
        appendLine("- 3 catchy, click-worthy titles under 70 characters (same language style as the script).")
        appendLine("- Description in ENGLISH only: a strong hook in the first 2 lines, a short summary, a call to subscribe. 150-250 words. Do not put hashtags inside it.")
        appendLine("- 12-15 hashtags, every hashtag starts with a small (lowercase) letter, no spaces, e.g. #mahabharat #bhishmPitamah.")
        appendLine("- 15-20 search tags (plain words, comma separated).")
        appendLine("- One thumbnail image prompt in layered paper cut-out style, bold emotional faces, high contrast, room for big title text.")
        if (starts.size >= 3) {
            appendLine("- Chapter titles in English, 2-5 words each, for these exact chapter start times (keep the times):")
            starts.forEach { (t, i) -> appendLine("  ${fmt(t)} -> ${project.scenes[i].text.take(120)}") }
        }
        appendLine()
        appendLine("Script:")
        appendLine(project.script.take(6000))
    }

    /** A message to paste into ChatGPT / Gemini chat (works without any API key). */
    fun chatbotPrompt(project: Project): String = rules(project) + "\nAnswer with clear sections: TITLES, DESCRIPTION, CHAPTERS, HASHTAGS, TAGS, THUMBNAIL PROMPT."

    fun generateWithGemini(project: Project, director: GeminiDirector): YoutubeKit {
        val text = director.generateJson(
            rules(project) + "\nReturn ONLY JSON: {\"titles\":[...],\"description\":\"...\",\"chapterTitles\":[...],\"hashtags\":[...],\"tags\":[...],\"thumbnailPrompt\":\"...\"}"
        )
        val o = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return YoutubeKit(
            titles = list("titles"),
            description = o.optString("description").replace(Regex("(?m)^#\\S+.*$"), "").trim(),
            chapters = chapters(project, list("chapterTitles")),
            hashtags = list("hashtags").map(::hashtag).filter { it.length > 1 }.distinct(),
            tags = list("tags").map { it.trim() }.filter { it.isNotEmpty() },
            thumbnailPrompt = o.optString("thumbnailPrompt"),
        )
    }

    /** Reads the sectioned answer ChatGPT/Gemini chat gives for [chatbotPrompt]. */
    fun parseChatbotAnswer(answer: String, project: Project): YoutubeKit {
        val heads = linkedMapOf(
            "titles" to Regex("^\\W*titles?\\W*$", RegexOption.IGNORE_CASE),
            "description" to Regex("^\\W*description\\W*$", RegexOption.IGNORE_CASE),
            "chapters" to Regex("^\\W*(chapters?|timestamps?)\\W*$", RegexOption.IGNORE_CASE),
            "hashtags" to Regex("^\\W*hash\\s?tags?\\W*$", RegexOption.IGNORE_CASE),
            "tags" to Regex("^\\W*(search\\s)?tags?\\W*$", RegexOption.IGNORE_CASE),
            "thumb" to Regex("^\\W*thumbnail(\\s+(image\\s+)?prompt)?\\W*$", RegexOption.IGNORE_CASE),
        )
        val sections = HashMap<String, StringBuilder>()
        var cur: String? = null
        for (raw in answer.replace("\r", "").lines()) {
            val line = raw.trim()
            // Heading alone on a line, or "Heading: value" on the same line.
            val head = if (line.contains(':') && line.indexOf(':') < 30) line.substringBefore(':') else line
            val key = heads.entries.firstOrNull { (_, re) -> re.matches(head) }?.key
            if (key != null) {
                cur = key
                val rest = if (head != line) line.substringAfter(':').trim() else ""
                sections.getOrPut(key) { StringBuilder() }.also { if (rest.isNotEmpty()) it.appendLine(rest) }
                continue
            }
            cur?.let { sections.getOrPut(it) { StringBuilder() }.appendLine(raw) }
        }
        fun text(k: String) = sections[k]?.toString()?.trim().orEmpty()
        fun clean(l: String) = l.trim().replace(Regex("^([-*•]|\\d+[.)])\\s*"), "").trim('*', '"', ' ')
        val titles = text("titles").lines().map(::clean).filter { it.isNotBlank() }
        val chapterLines = text("chapters").lines().map { clean(it) }.filter { Regex("^\\d+:\\d{2}").containsMatchIn(it) }
        val chapterTitles = chapterLines.map { it.replace(Regex("^[\\d:]+\\s*(->|[-–—:])?\\s*"), "") }
        return YoutubeKit(
            titles = titles,
            description = text("description").lines().filterNot { it.trim().startsWith("#") }.joinToString("\n").trim(),
            chapters = chapters(project, chapterTitles.ifEmpty { null }),
            hashtags = Regex("#[\\p{L}\\p{M}\\p{N}_]+").findAll(text("hashtags").ifBlank { answer }).map { hashtag(it.value) }.filter { it.length > 1 }.distinct().toList(),
            tags = text("tags").split(Regex("[,\\n]")).map(::clean).filter { it.isNotBlank() && !it.startsWith("#") },
            thumbnailPrompt = text("thumb").lines().joinToString(" ") { clean(it) }.trim(),
        )
    }

    /** Everything ready to paste into YouTube's description box. */
    fun fullDescription(k: YoutubeKit): String = buildString {
        if (k.description.isNotBlank()) appendLine(k.description.trim()).appendLine()
        if (k.chapters.isNotBlank()) appendLine("Chapters:").appendLine(k.chapters).appendLine()
        if (k.hashtags.isNotEmpty()) append(k.hashtags.joinToString(" "))
    }.trim()

}
