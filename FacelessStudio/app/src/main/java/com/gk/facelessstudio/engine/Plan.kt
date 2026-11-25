package com.gk.facelessstudio.engine

/**
 * The AI Visual Plan (Explainer Canvas style): a cast of separate drawable elements (characters / objects, each one
 * made as its own picture on white) and, for every narration line, what happens on the canvas while it is spoken.
 *
 * Reply format (written by the user's ChatGPT / Gemini from [GridWorkflow.planPrompt]):
 *
 *     ELEMENTS
 *     E1: macrophage | big round friendly cyan immune cell with a smiling face
 *     E2: virus | small orange spiky virus with an angry face
 *     LINES
 *     1: new; E1 @cell
 *     2: E2 @virus; E2 > E1 @attacks; text "danger" @danger
 *     3: arrow E2 > E1; bg blue
 *
 * Actions: `En` draw an element, `En > Em` move En next to Em, `arrow En > Em` hand-drawn arrow, `text "word"`
 * handwritten word, `new` start a fresh area of the canvas, `bg <colour>` tint the paper. `@word` = the spoken word
 * that triggers the action.
 */
object Plan {

    data class Element(val id: String, val name: String, val desc: String)

    enum class Kind { DRAW, MOVE, ARROW, TEXT, NEW, BG }

    data class Action(val kind: Kind, val a: String = "", val b: String = "", val text: String = "", val cue: String = "")

    data class Parsed(val elements: List<Element>, val lines: Map<Int, List<Action>>) {
        fun index(id: String) = elements.indexOfFirst { it.id.equals(id, true) }
        val isEmpty get() = elements.isEmpty()
    }

    private val elemRe = Regex("""^\s*[*\-•]*\s*\**\s*(E\d+)\s*\**\s*[:.)\-–]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val lineRe = Regex("""^\s*[*\-•]*\s*\**\s*(?:line\s*)?(\d+)\s*\**\s*[:.)\-–]\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val idRe = Regex("""\bE\d+\b""", RegexOption.IGNORE_CASE)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Parsed>()

    fun parse(text: String): Parsed = cache.getOrPut(text) { doParse(text) }

    private fun doParse(text: String): Parsed {
        val elems = LinkedHashMap<String, Element>()
        val lines = HashMap<Int, List<Action>>()
        for (raw in text.lines()) {
            val ln = raw.replace("`", "").trim()
            if (ln.isEmpty()) continue
            elemRe.find(ln)?.let { m ->
                val id = m.groupValues[1].uppercase()
                val body = m.groupValues[2].trim()
                val parts = body.split("|", limit = 2)
                val name = if (parts.size == 2) parts[0].trim() else body.split(Regex("[,(]")).first().trim().take(30)
                val desc = (if (parts.size == 2) parts[1] else body).trim().trimEnd('.')
                if (desc.isNotEmpty()) elems[id] = Element(id, name.trim('*', ' '), desc)
                return@let
            } ?: lineRe.find(ln)?.let { m ->
                val n = m.groupValues[1].toInt() - 1
                if (n >= 0) lines[n] = actions(m.groupValues[2])
            }
        }
        return Parsed(elems.values.toList(), lines)
    }

    fun actions(body: String): List<Action> = body.split(';').mapNotNull { part ->
        var s = part.trim().trim('*', ' ')
        if (s.isEmpty()) return@mapNotNull null
        var cue = ""
        Regex("""@\s*"?([^";@]+?)"?\s*$""").find(s)?.let { cue = it.groupValues[1].trim(); s = s.substring(0, it.range.first).trim() }
        val low = s.lowercase()
        val quoted = Regex("\"([^\"]+)\"|“([^”]+)”|'([^']+)'").find(s)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        val ids = idRe.findAll(s).map { it.value.uppercase() }.toList()
        when {
            low.startsWith("new") || low.startsWith("clear") -> Action(Kind.NEW, cue = cue)
            low.startsWith("bg") || low.startsWith("background") -> Action(Kind.BG, text = low.substringAfter(' ', "").trim(), cue = cue)
            low.startsWith("text") || (quoted != null && ids.isEmpty()) -> quoted?.let { Action(Kind.TEXT, text = it, cue = cue) }
                ?: Action(Kind.TEXT, text = s.substringAfter(' ', "").trim(), cue = cue)
            low.startsWith("arrow") && ids.size >= 2 -> Action(Kind.ARROW, ids[0], ids[1], cue = cue)
            ids.size >= 2 && (s.contains('>') || low.contains("move") || low.contains("to ")) -> Action(Kind.MOVE, ids[0], ids[1], cue = cue)
            ids.isNotEmpty() -> Action(Kind.DRAW, ids[0], cue = cue)
            else -> null
        }
    }

    /** Merges a new reply (e.g. part 2 of a long script) into the stored plan text: later definitions win. */
    fun merge(old: String, reply: String): String {
        val a = parse(old); val b = parse(reply)
        if (b.isEmpty && b.lines.isEmpty()) return old
        val elems = LinkedHashMap<String, Element>().apply { a.elements.forEach { put(it.id, it) }; b.elements.forEach { put(it.id, it) } }
        val lines = HashMap(a.lines).apply { putAll(b.lines) }
        return format(Parsed(elems.values.sortedBy { it.id.drop(1).toIntOrNull() ?: 0 }, lines))
    }

    fun format(p: Parsed): String = buildString {
        append("ELEMENTS\n")
        p.elements.forEach { append(it.id).append(": ").append(it.name).append(" | ").append(it.desc).append('\n') }
        append("LINES\n")
        p.lines.keys.sorted().forEach { n ->
            append(n + 1).append(": ").append(p.lines[n]!!.joinToString("; ") { act ->
                val core = when (act.kind) {
                    Kind.DRAW -> act.a
                    Kind.MOVE -> "${act.a} > ${act.b}"
                    Kind.ARROW -> "arrow ${act.a} > ${act.b}"
                    Kind.TEXT -> "text \"${act.text}\""
                    Kind.NEW -> "new"
                    Kind.BG -> "bg ${act.text}"
                }
                if (act.cue.isNotEmpty()) "$core @${act.cue}" else core
            }).append('\n')
        }
    }
}
