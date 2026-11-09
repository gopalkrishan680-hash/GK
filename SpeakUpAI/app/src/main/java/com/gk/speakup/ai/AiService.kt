package com.gk.speakup.ai

import org.json.JSONObject

/** How hard the model should think. LOW keeps conversation replies fast. */
enum class Effort { LOW, MEDIUM }

/** Common interface for the AI back-ends (Google Gemini free tier, or Claude). */
interface AiService {
    /**
     * Sends [system] + [history] and returns the JSON object the model produced,
     * matching [schema] (a JSON Schema object with properties/required).
     */
    fun askJson(
        system: String,
        history: List<ChatTurn>,
        schema: JSONObject,
        effort: Effort = Effort.LOW,
        maxTokens: Long = 8000L,
    ): JSONObject
}

/** Parses model text as JSON, tolerating ```json fences or text around the object. */
internal fun parseJsonObject(raw: String): JSONObject {
    val text = raw.trim()
    runCatching { return JSONObject(text) }
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start >= 0 && end > start) {
        runCatching { return JSONObject(text.substring(start, end + 1)) }
    }
    throw AiException("AI ka jawab samajh nahi aaya. Dobara try karein.")
}
