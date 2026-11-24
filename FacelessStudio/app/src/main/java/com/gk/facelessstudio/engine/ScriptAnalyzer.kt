package com.gk.facelessstudio.engine

import com.gk.facelessstudio.model.SceneType

/** One spoken piece of the script plus everything the director needs to pick a visual. */
data class Chunk(
    val text: String,
    val chapterIndex: Int,
    val chapterStart: Boolean,
    val forced: SceneType? = null,
    val forceCut: Boolean = false,
) {
    val words: List<String> = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    val weight: Float get() = text.length + 6f + (if (text.endsWith(",")) 0f else 4f)
    val isQuestion: Boolean get() = text.trimEnd().endsWith("?")
}

data class Analysis(
    val chunks: List<Chunk>,
    val chapters: List<String>,
)

object ScriptAnalyzer {

    private val TAGS = mapOf(
        "kinetic" to SceneType.KINETIC, "words" to SceneType.KINETIC,
        "title" to SceneType.CHAPTER, "chapter" to SceneType.CHAPTER,
        "image" to SceneType.KEN_BURNS, "cinematic" to SceneType.KEN_BURNS,
        "banner" to SceneType.IMAGE_BANNER, "list" to SceneType.QUESTION_LIST,
        "questions" to SceneType.QUESTION_LIST, "checklist" to SceneType.CHECKLIST,
        "mindmap" to SceneType.MINDMAP, "tree" to SceneType.MINDMAP,
        "note" to SceneType.PAPER_NOTE, "paper" to SceneType.PAPER_NOTE,
        "stat" to SceneType.BIG_STAT, "number" to SceneType.BIG_STAT,
        "date" to SceneType.DATE_STAMP, "vs" to SceneType.VERSUS, "versus" to SceneType.VERSUS,
        "quote" to SceneType.QUOTE, "arrow" to SceneType.SPLIT_ARROW, "cause" to SceneType.SPLIT_ARROW,
        "photos" to SceneType.POLAROID, "polaroid" to SceneType.POLAROID,
        "headline" to SceneType.HEADLINE_IMAGE, "phone" to SceneType.PHONE,
        "spotlight" to SceneType.SPOTLIGHT,
    )

    private val SENTENCE_END = Regex("(?<=[.!?।])\\s+")
    private val TAG = Regex("^\\[(\\w+)]\\s*", RegexOption.IGNORE_CASE)

    fun analyze(script: String, maxWordsPerScene: Int): Analysis {
        val chapters = mutableListOf<String>()
        val chunks = mutableListOf<Chunk>()
        var chapterIdx = -1
        var pendingChapterStart = false
        var pendingForced: SceneType? = null
        var pendingCut = false

        for (rawLine in script.replace("\r", "").split("\n")) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line == "---" || line == "***") { pendingCut = true; continue }

            if (line.startsWith("#") || isHeadingLike(line)) {
                val heading = line.trimStart('#').trim().trimEnd(':').trim()
                if (heading.isNotEmpty()) {
                    chapters += heading
                    chapterIdx = chapters.lastIndex
                    pendingChapterStart = true
                }
                continue
            }

            TAG.find(line)?.let { m ->
                TAGS[m.groupValues[1].lowercase()]?.let { pendingForced = it }
                line = line.substring(m.range.last + 1).trim()
            }
            if (line.isEmpty()) continue

            for (sentence in line.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }) {
                for (piece in splitLong(sentence, maxWordsPerScene)) {
                    chunks += Chunk(
                        text = piece,
                        chapterIndex = chapterIdx,
                        chapterStart = pendingChapterStart,
                        forced = pendingForced,
                        forceCut = pendingCut,
                    )
                    pendingChapterStart = false
                    pendingForced = null
                    pendingCut = false
                }
            }
        }
        return Analysis(chunks, chapters)
    }

    /** Short line without ending punctuation like "Step 2: Research" or "INTRO" counts as a chapter heading. */
    private fun isHeadingLike(line: String): Boolean {
        val words = line.split(Regex("\\s+"))
        if (words.size > 6) return false
        if (line.last() in ".!?,।") return false
        val stepLike = Regex("^(step|chapter|part|section|point|tip)\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(line)
        val shouty = line.any { it.isLetter() } && line.filter { it.isLetter() }.all { it.isUpperCase() } && line.length >= 3
        return stepLike || shouty || line.endsWith(":")
    }

    private val QUOTED = Regex("[\"“][^\"”]*[\"”]")

    private val SOFT_BREAK = Regex(
        ",|;|—| - | aur | lekin | par | but | and | because | kyunki | isliye | so | which | jo | jab | when ",
        RegexOption.IGNORE_CASE
    )

    /** Keeps scenes short so the visuals keep changing; splits at commas/conjunctions near the middle. */
    fun splitLong(sentence: String, maxWords: Int): List<String> {
        val words = sentence.split(Regex("\\s+"))
        if (words.size <= maxWords) return listOf(sentence)
        val mid = sentence.length / 2
        val candidates = SOFT_BREAK.findAll(sentence).map {
            // split after a comma, before a conjunction
            if (it.value.startsWith(",") || it.value.startsWith(";")) it.range.last + 1 else it.range.first
        }.filter { it in 8 until sentence.length - 8 }
            .filter { pos -> QUOTED.findAll(sentence).none { pos > it.range.first && pos <= it.range.last } }
            .toList()
        val cut = candidates.minByOrNull { kotlin.math.abs(it - mid) }
            ?: run {
                // hard split by word count
                val half = words.size / 2
                words.take(half).joinToString(" ").length
            }
        val a = sentence.substring(0, cut).trim()
        val b = sentence.substring(cut).trim()
        if (a.isEmpty() || b.isEmpty()) return listOf(sentence)
        return splitLong(a, maxWords) + splitLong(b, maxWords)
    }

    // ---------- content detectors ----------

    private val STOP = setOf(
        "the", "a", "an", "is", "are", "was", "were", "of", "in", "on", "at", "to", "for", "and", "or", "but",
        "it", "this", "that", "with", "as", "by", "be", "you", "your", "we", "our", "they", "their", "i", "me", "my",
        "he", "she", "his", "her", "them", "so", "if", "then", "than", "there", "here", "what", "when", "where", "who",
        "why", "how", "which", "can", "will", "just", "do", "does", "did", "have", "has", "had", "not", "no", "yes",
        "hai", "hain", "ka", "ki", "ke", "ko", "se", "me", "mein", "aur", "ye", "yeh", "wo", "woh", "ek", "bhi", "to",
        "toh", "kya", "nahi", "nahin", "ho", "hota", "hoti", "hote", "tha", "thi", "the", "kar", "karte", "karta",
        "liye", "par", "pe", "hum", "aap", "tum", "unka", "uska", "iska", "jo", "jab", "tab", "kaise", "kyun",
        "kyunki", "isliye", "lekin", "agar", "sab", "kuch", "bahut", "very", "really", "about", "into", "from",
        "also", "all", "some", "more", "most", "been", "being", "its", "it's", "let's", "lets",
        "jabki", "magar", "phir", "fir", "waise", "wahi", "yahi", "abhi", "aaj", "kal", "sirf", "bas", "kaafi", "jaise",
        "gaya", "gayi", "gaye", "raha", "rahi", "rahe", "karna", "karne", "karti", "sakte", "sakta", "sakti", "milti",
        "milta", "milte", "dikhta", "dikhti", "dikhte", "hota", "hoti", "aata", "aati", "aate", "jaata", "jaati", "jata",
        "jati", "padho", "padhte", "dekha", "dekho", "kaha", "bana", "bani", "aaya", "aayi", "laga", "lagta", "rakhti",
        "rakhta", "batati", "batata", "unke", "inke", "uske", "iske", "apne", "apni", "logon", "log", "wala", "wali", "wale",
    )

    fun cleanWord(w: String) = w.trim { !it.isLetterOrDigit() && it != '%' && it != '₹' && it != '$' }

    fun keywords(text: String, max: Int): List<String> {
        val words = text.split(Regex("\\s+")).map { cleanWord(it) }.filter { it.isNotEmpty() }
        val marked = Regex("\\*([^*]+)\\*").findAll(text).map { it.groupValues[1] }.toList()
        val scored = words.mapIndexed { i, w ->
            var s = w.length.toFloat()
            if (w.lowercase() in STOP) s -= 100
            if (w.any { it.isDigit() }) s += 8
            if (i > 0 && w.first().isUpperCase()) s += 5
            if (w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() }) s += 6
            Pair(w, s)
        }.filter { it.second > 3 }
        return (marked + scored.sortedByDescending { it.second }.map { it.first })
            .distinctBy { it.lowercase() }.take(max)
    }

    fun headline(text: String, maxWords: Int = 3): String {
        val kw = keywords(text, maxWords).toSet()
        // keep original order of keywords so the headline reads naturally
        val ordered = text.split(Regex("\\s+")).map { cleanWord(it) }.filter { it in kw }.distinct()
        return ordered.joinToString(" ").uppercase().ifEmpty { text.take(18).uppercase() }
    }

    data class Stat(val prefix: String, val number: Double, val decimals: Int, val grouped: Boolean, val suffix: String, val label: String)

    private val STAT = Regex(
        "([₹$€£]?)\\s?(\\d{1,3}(?:,\\d{2,3})+|\\d+(?:\\.\\d+)?)\\s?(%|percent|k\\b|lakh|crore|million|billion|thousand|hazaar|hazar|crore|x\\b)?",
        RegexOption.IGNORE_CASE
    )

    fun detectStat(original: String): Stat? {
        // dates and clock times are handled by the date stamp, not the counter
        val text = listOf(DATE_FULL, DATE_MY, TIME).fold(original) { acc, r -> r.replace(acc) { " ".repeat(it.value.length) } }
        val m = STAT.findAll(text).firstOrNull { match ->
            val raw = match.groupValues[2].replace(",", "")
            val v = raw.toDoubleOrNull() ?: return@firstOrNull false
            val isYear = raw.length == 4 && v in 1000.0..2100.0 && match.groupValues[3].isEmpty() && match.groupValues[1].isEmpty()
            !isYear && (v >= 10 || match.groupValues[3].isNotEmpty() || match.groupValues[1].isNotEmpty())
        } ?: return null
        val raw = m.groupValues[2]
        val num = raw.replace(",", "").toDouble()
        val decimals = raw.substringAfter('.', "").length
        val unit = m.groupValues[3].let { if (it.equals("percent", true)) "%" else it }
        val rest = (text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1))
        val label = headline(rest, 4)
        return Stat(m.groupValues[1], num, decimals, raw.contains(','), unit.uppercase().let { if (it == "%") "%" else if (it.isEmpty()) "" else " $it" }, label)
    }

    private val MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private val DATE_FULL = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+($MONTHS)[a-z]*,?\\s+(1\\d{3}|20\\d{2})\\b", RegexOption.IGNORE_CASE)
    private val DATE_MY = Regex("\\b($MONTHS)[a-z]*\\s+(\\d{1,2},?\\s+)?(1\\d{3}|20\\d{2})\\b", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("\\b(1[0-9]{3}|20[0-9]{2})\\b")
    private val TIME = Regex("\\b(\\d{1,2}):(\\d{2})\\s?(am|pm|AM|PM)?\\b")

    /** Returns [dateLabel, timeLabel] or null. */
    fun detectDate(text: String): List<String>? {
        val time = TIME.find(text)?.let { m ->
            "${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2]}" + (m.groupValues[3].takeIf { it.isNotEmpty() }?.let { " ${it.uppercase()}" } ?: "")
        } ?: ""
        DATE_FULL.find(text)?.let { m ->
            return listOf("${m.groupValues[1]} ${m.groupValues[2].take(3).uppercase()} ${m.groupValues[3]}", time)
        }
        DATE_MY.find(text)?.let { m ->
            return listOf("${m.groupValues[1].take(3).uppercase()} ${m.groupValues[3]}", time)
        }
        YEAR.find(text)?.let { m ->
            val v = m.value.toInt()
            if (v in 1000..2100) return listOf(m.value, time)
        }
        if (time.isNotEmpty()) return listOf("", time)
        return null
    }

    private val VERSUS = Regex("\\s(vs\\.?|versus|banam|compared to|instead of)\\s", RegexOption.IGNORE_CASE)

    fun detectVersus(text: String): Pair<String, String>? {
        val m = VERSUS.find(text) ?: return null
        val left = text.substring(0, m.range.first)
        val right = text.substring(m.range.last + 1)
        val l = headline(left, 2); val r = headline(right, 2)
        if (l.isBlank() || r.isBlank()) return null
        return Pair(l, r)
    }

    fun detectQuote(text: String): String? {
        val m = Regex("[\"“]([^\"”]{8,})[\"”]").find(text) ?: return null
        return m.groupValues[1].takeIf { it.split(" ").size >= 3 }
    }

    /** "Why do people smoke: peer pressure, stress relief and advertising" -> (title, items) */
    fun detectList(text: String): Pair<String, List<String>>? {
        val colon = text.indexOf(':')
        val head = if (colon in 3 until text.length - 3) text.substring(0, colon) else ""
        val body = if (head.isNotEmpty()) text.substring(colon + 1) else text
        val items = body.split(Regex(",|;|\\band\\b|\\baur\\b|\\bor\\b", RegexOption.IGNORE_CASE))
            .map { it.trim().trimEnd('.', '!', '?') }
            .filter { it.isNotEmpty() }
        if (items.size < 3 && !(head.isNotEmpty() && items.size >= 2)) return null
        if (items.any { it.split(" ").size > 5 }) return null
        val title = if (head.isNotEmpty()) head.trim() else ""
        return Pair(title, items.take(5).map { it.replaceFirstChar { c -> c.uppercase() } })
    }

    private val CAUSE = Regex("\\s(because|so that|therefore|which means|leads to|results in|kyunki|isliye|iska matlab|jisse|->|→)\\s", RegexOption.IGNORE_CASE)

    fun detectCause(text: String): Pair<String, String>? {
        val m = CAUSE.find(text) ?: return null
        val a = text.substring(0, m.range.first); val b = text.substring(m.range.last + 1)
        if (a.length < 4 || b.length < 4) return null
        return Pair(headline(a, 2), headline(b, 3))
    }

    /** "50%" / "50 percent" -> 50 */
    fun detectPercent(text: String): Pair<Float, String>? {
        val m = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s?(%|percent|pratishat)", RegexOption.IGNORE_CASE).find(text) ?: return null
        val v = m.groupValues[1].toFloatOrNull() ?: return null
        if (v <= 0f || v > 100f) return null
        val rest = text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1)
        return Pair(v, headline(rest, 4))
    }

    private val UNITS = setOf("crore", "lakh", "million", "billion", "thousand", "hazaar", "hazar", "k", "percent", "rupees",
        "rupaye", "dollars", "people", "users", "saal", "years", "km", "kg")

    /** Two or more numbers in one line -> labelled bars ("India 140 crore, China 141 crore"). */
    fun detectNumbers(original: String): List<Pair<String, Double>>? {
        val text = listOf(DATE_FULL, DATE_MY, TIME, YEAR).fold(original) { acc, r -> r.replace(acc) { " ".repeat(it.value.length) } }
        val ms = Regex("(\\d{1,3}(?:,\\d{2,3})+|\\d+(?:\\.\\d+)?)").findAll(text).toList()
        if (ms.size < 2 || ms.size > 5) return null
        val out = ms.mapIndexed { i, m ->
            val v = m.value.replace(",", "").toDoubleOrNull() ?: return null
            val from = if (i == 0) 0 else ms[i - 1].range.last + 1
            val before = text.substring(from, m.range.first).split(Regex("\\s+")).map { cleanWord(it) }
                .filter { it.isNotEmpty() && it.lowercase() !in STOP && it.lowercase() !in UNITS }.takeLast(2).joinToString(" ")
            Pair(before.ifBlank { "#${i + 1}" }.uppercase(), v)
        }
        if (out.all { it.second == out[0].second }) return null
        return out
    }

    /** Two or more years -> timeline points with a short label after each year. */
    fun detectYears(text: String): List<Pair<String, String>>? {
        val ms = YEAR.findAll(text).toList()
        if (ms.size < 2) return null
        // label = the words just before each year ("Internet 1983", "Google 1998 me aaya")
        return ms.take(5).mapIndexed { i, m ->
            val from = if (i == 0) 0 else ms[i - 1].range.last + 1
            val before = text.substring(from, m.range.first).split(Regex("\\s+")).map { cleanWord(it) }
                .filter { it.isNotEmpty() && it.lowercase() !in STOP }.takeLast(2).joinToString(" ")
            Pair(m.value, before)
        }
    }

    /**
     * Ready-to-paste prompt for any AI image generator. Always names the subject (topic + what this line says),
     * so the image tool never has to ask "what should I draw?".
     */
    fun imagePrompt(sceneText: String, style: String, ratio: String, topic: String = "", chapter: String = ""): String {
        val scene = sceneText.replace(Regex("\\[[a-zA-Z]+]"), "").replace("*", "").trim().trimEnd('.', '!', '?')
        val kw = keywords(sceneText, 4).joinToString(", ")
        val ctx = listOf(topic, chapter).filter { it.isNotBlank() }.distinct().joinToString(" – ")
        return buildString {
            append(style)
            if (ctx.isNotBlank()) append(" about \"").append(ctx).append("\"")
            append(". Scene that visually shows: \"").append(scene).append("\"")
            if (kw.isNotBlank()) append(". Main subjects: ").append(kw)
            append(". Cinematic lighting, dramatic shadows, rich detail, faceless storytelling visual, aspect ratio ")
            append(ratio).append(", no text, no letters, no watermark")
        }
    }
}
