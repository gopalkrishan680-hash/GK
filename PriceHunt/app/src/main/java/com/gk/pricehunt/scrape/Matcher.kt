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

    /** A short search query from a long product title (used for "compare this item"). */
    fun queryFromTitle(title: String): String {
        val cleaned = title.replace(Regex("\\(.*?\\)|\\[.*?]|\\|.*$"), " ")
            .replace(Regex("[,/]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return cleaned.split(' ').take(7).joinToString(" ")
    }
}
