package com.gk.papercut.render

import com.gk.papercut.data.Scene
import com.gk.papercut.data.ScriptPlanner

/** Picks the one word per scene that gets a big paper-cut pop-up. */
object Keywords {
    private val stop = setOf(
        // Hindi
        "है", "हैं", "था", "थे", "थी", "थीं", "और", "का", "की", "के", "को", "में", "से", "पर", "ने", "यह", "वह", "ये", "वो",
        "एक", "क्या", "क्यों", "तो", "भी", "ही", "नहीं", "न", "हो", "रहा", "रही", "रहे", "कर", "गया", "गई", "गए", "जो", "इस",
        "उस", "अपने", "अपनी", "उनके", "उनकी", "उन्हें", "उसे", "मैं", "मुझे", "हम", "आप", "तुम", "लिए", "जब", "तब", "अगर",
        "लेकिन", "बहुत", "सब", "कुछ", "सकता", "सकते", "सकती", "जाता", "जाती", "जाते", "होता", "होती", "हुआ", "हुई", "दिन",
        "बोले", "कहा", "खुद", "इसका", "उसका", "किसी", "दूसरी", "तरफ", "सबसे", "बड़े", "बड़ा", "बड़ी", "वाले", "वाली",
        // Hinglish / English
        "hai", "hain", "tha", "the", "thi", "aur", "ka", "ki", "ke", "ko", "mein", "se", "par", "ne", "yeh", "woh", "ek", "kya",
        "kyun", "to", "bhi", "hi", "nahi", "ho", "raha", "rahi", "kar", "gaya", "gayi", "jo", "is", "us", "apne", "main", "hum",
        "aap", "liye", "jab", "agar", "lekin", "bahut", "sab", "kuch", "a", "an", "and", "of", "in", "on", "was", "were", "are",
        "that", "this", "with", "for", "but", "not", "his", "her", "they", "he", "she", "it", "be", "has", "have", "had",
    )

    /** Letters including Hindi matras (ो ् ं …), so "क्यों" counts as a real word. */
    private fun letters(w: String) = w.count {
        it.isLetter() || Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() }
    }

    /** Index of the pop-up word in [scene], or -1. Respects the per-scene choice (-2 = automatic). */
    fun popIndex(scene: Scene): Int {
        if (scene.popWord >= -1) return scene.popWord
        return auto(scene)
    }

    fun auto(scene: Scene): Int {
        val ws = scene.words.map { it.text }.ifEmpty { ScriptPlanner.words(scene.text) }
        if (ws.size < 3 || scene.duration < 3.0) return -1
        val hintIdx = scene.elementsHint.map { it.wordIndex }.toSet()
        var best = -1; var bestScore = 0f
        ws.forEachIndexed { i, raw ->
            val w = ScriptPlanner.normalize(raw)
            if (w in stop || letters(w) < 3) return@forEachIndexed
            var score = letters(w) / 3f
            if (raw.trimEnd().lastOrNull() in setOf('?', '!')) score += 3f
            if (i + 1 < ws.size && ws[i + 1].trim() in setOf("?", "!")) score += 3f
            if (i in hintIdx) score += 2f
            if (i == 0) score -= 1f
            // A word late in the scene has no time to be read before the cut.
            val t = scene.words.getOrNull(i)?.start?.minus(scene.start) ?: 0.0
            if (scene.duration - t < 1.6) score -= 3f
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }
}
