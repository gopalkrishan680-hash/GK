package com.gk.speakup

import android.content.Context
import com.gk.speakup.ai.AiException
import com.gk.speakup.ai.ClaudeService
import com.gk.speakup.data.Store
import com.gk.speakup.speech.SpeechManager

/** Simple app-wide container (no DI framework needed). */
class AppGraph(context: Context) {
    val store = Store(context.applicationContext)
    val speech = SpeechManager(context.applicationContext)

    private var cached: Pair<String, ClaudeService>? = null

    /** Claude client for the current API key + model. */
    fun ai(): ClaudeService {
        val p = store.profile
        if (p.apiKey.isBlank()) throw AiException("Pehle Settings me apni Anthropic API key daalein.")
        val key = p.apiKey + "|" + p.model
        cached?.let { if (it.first == key) return it.second }
        return ClaudeService(p.apiKey.trim(), p.model).also { cached = key to it }
    }
}
