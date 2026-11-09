package com.gk.speakup.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONArray
import org.json.JSONObject

/** One line of the conversation that we replay to Claude (text only, append-only). */
data class ChatTurn(val fromUser: Boolean, val text: String)

class AiException(message: String) : Exception(message)

/**
 * Thin wrapper around the official Anthropic Java SDK.
 *
 * Every call asks Claude for JSON that matches a schema (structured outputs), so the
 * app can show corrections, scores and new words as proper UI instead of parsing prose.
 */
class ClaudeService(apiKey: String, private val model: String) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    /**
     * Sends [system] + [history] and returns the JSON object Claude produced.
     * History is replayed as plain text turns only, so it stays append-only and stable
     * (good for prompt caching).
     */
    fun askJson(
        system: String,
        history: List<ChatTurn>,
        schema: JSONObject,
        effort: OutputConfig.Effort = OutputConfig.Effort.LOW,
        maxTokens: Long = 8000L,
    ): JSONObject {
        require(history.isNotEmpty() && history.last().fromUser) { "Last turn must be from the user" }

        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(system)
            // Cache the stable prefix (system prompt + earlier turns) so long chats stay fast and cheap.
            .cacheControl(CacheControlEphemeral.builder().build())
            .outputConfig(
                OutputConfig.builder()
                    .effort(effort)
                    .format(
                        JsonOutputFormat.builder()
                            .schema(schemaOf(schema))
                            .build()
                    )
                    .build()
            )

        for (turn in history) {
            if (turn.fromUser) builder.addUserMessage(turn.text) else builder.addAssistantMessage(turn.text)
        }

        // Server-side refusal fallback: if a safety classifier declines, the API retries on
        // a suitable model automatically. Available for Opus 5.5 on the Claude API.
        if (model.startsWith("claude-opus")) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        val response = try {
            client.messages().create(builder.build())
        } catch (e: UnauthorizedException) {
            throw AiException("API key galat hai (invalid). Settings me sahi Anthropic API key daalein.")
        } catch (e: RateLimitException) {
            throw AiException("Bahut saari requests ho gayi. Thoda ruk kar dobara try karein.")
        } catch (e: AnthropicServiceException) {
            throw AiException("AI server error (${e.statusCode()}): ${e.message}")
        } catch (e: AnthropicIoException) {
            throw AiException("Internet connection check karein: ${e.message}")
        }

        val stop = response.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) {
            throw AiException("AI ne is message ka jawab nahi diya. Kuch aur try karein.")
        }
        if (stop == StopReason.MAX_TOKENS) {
            throw AiException("Jawab bahut lamba ho gaya. Dobara try karein.")
        }

        val text = response.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
            .trim()
        if (text.isEmpty()) throw AiException("AI se khaali jawab aaya. Dobara try karein.")
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw AiException("AI ka jawab samajh nahi aaya. Dobara try karein.")
        }
    }

    private fun schemaOf(schema: JSONObject): JsonOutputFormat.Schema {
        val b = JsonOutputFormat.Schema.builder()
        for (key in schema.keys()) {
            b.putAdditionalProperty(key, toJsonValue(schema.get(key)))
        }
        return b.build()
    }

    private fun toJsonValue(v: Any?): JsonValue = when (v) {
        null, JSONObject.NULL -> JsonValue.from(null)
        is JSONObject -> JsonValue.from(v.keys().asSequence().associateWith { k -> toPlain(v.get(k)) })
        is JSONArray -> JsonValue.from((0 until v.length()).map { i -> toPlain(v.get(i)) })
        else -> JsonValue.from(v)
    }

    private fun toPlain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.keys().asSequence().associateWith { k -> toPlain(v.get(k)) }
        is JSONArray -> (0 until v.length()).map { i -> toPlain(v.get(i)) }
        else -> v
    }

    companion object {
        const val MODEL_BEST = "claude-opus-5-5"
        const val MODEL_FAST = "claude-haiku-5-5"
    }
}
