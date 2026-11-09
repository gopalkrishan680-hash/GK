package com.gk.speakup

import android.content.Context
import com.gk.speakup.ai.AiException
import com.gk.speakup.ai.AiService
import com.gk.speakup.ai.ClaudeService
import com.gk.speakup.ai.GeminiService
import com.gk.speakup.data.Store
import com.gk.speakup.speech.SpeechManager

/** Simple app-wide container (no DI framework needed). */
class AppGraph(context: Context) {
    val store = Store(context.applicationContext)
    val speech = SpeechManager(context.applicationContext)

    private var cached: Pair<String, AiService>? = null

    /** AI client for the selected provider (Gemini free tier or Claude). */
    fun ai(): AiService {
        val p = store.profile
        if (p.usesGemini) {
            if (p.geminiKey.isBlank()) throw AiException("Pehle Settings me apni free Gemini API key daalein (aistudio.google.com).")
            val key = "gemini|" + p.geminiKey
            cached?.let { if (it.first == key) return it.second }
            return GeminiService(p.geminiKey.trim(), p.geminiModel) { model ->
                store.updateProfile { it.copy(geminiModel = model) }
            }.also { cached = key to it }
        }
        if (p.apiKey.isBlank()) throw AiException("Pehle Settings me apni Anthropic API key daalein.")
        val key = "claude|" + p.apiKey + "|" + p.model
        cached?.let { if (it.first == key) return it.second }
        return ClaudeService(p.apiKey.trim(), p.model).also { cached = key to it }
    }
}
