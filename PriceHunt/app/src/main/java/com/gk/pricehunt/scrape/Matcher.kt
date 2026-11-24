package com.gk.pricehunt.scrape

/** Lightweight title matching used to find "the same item" across different stores. */
object Matcher {
    private val stop = setOf(
        "the", "and", "for", "with", "of", "in", "a", "an", "to", "by", "on", "from", "new", "latest",
        "pack", "combo", "free", "best", "buy", "online", "india", "set", "piece", "pcs", "variant",
    )

    fun tokens(s: String): List<String> = s.lowercase()
        .replace(Regex("[^a-z0-9.]+"), " ")
        .split(' ')
        .map { it.trim('.') }
        .filter { it.isNotEmpty() && (it.length > 1 || it[0].isDigit()) && it !in stop }

    private fun weight(t: String) = if (t.any { it.isDigit() }) 2.0 else 1.0

    private fun contains(tokens: Collection<String>, t: String) =
        tokens.any { it == t || (t.length >= 4 && (it.startsWith(t) || t.startsWith(it)) && it.length >= 3) }

    /** Fraction (0..1, weighted) of query words present in the title. */
    fun relevance(query: String, title: String): Double {
        val q = tokens(query).distinct()
        if (q.isEmpty()) return 0.0
        val t = tokens(title)
        val total = q.sumOf { weight(it) }
        val hit = q.filter { contains(t, it) }.sumOf { weight(it) }
        return hit / total
    }

    private val unitRe = Regex("""(\d+(?:\.\d+)?)\s*(gb|tb|mb|ml|ltr|litre|l|kg|gm|g|inch|in|cm|mm|w|mah|hz|mp|pcs|pc|pack|watt)\b""")

    private fun units(s: String): Map<String, Set<String>> {
        val m = HashMap<String, MutableSet<String>>()
        unitRe.findAll(s.lowercase()).forEach {
            val unit = when (val u = it.groupValues[2]) { "ltr", "litre" -> "l"; "gm" -> "g"; "pc" -> "pcs"; "watt" -> "w"; "in" -> "inch"; else -> u }
            m.getOrPut(unit) { mutableSetOf() } += it.groupValues[1].trimEnd('0').trimEnd('.')
        }
        return m
    }

    /**
     * Penalty (0..1) when both titles mention the same unit with different numbers,
     * e.g. "128 GB" vs "256 GB" or "500 ml" vs "1 l" – i.e. a different variant.
     */
    fun variantConflict(ref: String, cand: String): Double {
        val a = units(ref)
        val b = units(cand)
        var pen = 0.0
        for ((unit, nums) in a) {
            val other = b[unit] ?: continue
            if (!other.containsAll(nums)) pen += 0.35
        }
        return pen.coerceAtMost(0.7)
    }

    /** Main score used everywhere: word overlap minus a penalty for a conflicting variant. */
    fun score(query: String, title: String): Double =
        (relevance(query, title) - variantConflict(query, title)).coerceAtLeast(0.0)

    /** A short search query from a long product title (used for "compare this item"). */
    fun queryFromTitle(title: String): String {
        val cleaned = title.replace(Regex("\\(.*?\\)|\\[.*?]|\\|.*$"), " ")
            .replace(Regex("[,/]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return cleaned.split(' ').filter { it.isNotBlank() }.take(8).joinToString(" ")
    }
}
