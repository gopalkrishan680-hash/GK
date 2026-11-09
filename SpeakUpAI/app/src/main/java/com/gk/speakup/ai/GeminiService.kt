package com.gk.speakup.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google Gemini API (free tier key from aistudio.google.com) over REST.
 *
 * The model is picked automatically from the models your key can use (newest stable
 * "Flash" model first), so the app keeps working when Google releases new versions.
 * If a model hits its free-tier limit, the next model in the list is tried.
 */
class GeminiService(
    private val apiKey: String,
    private val preferredModel: String = "",
    private val onModelChosen: (String) -> Unit = {},
) : AiService {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var candidates: List<String>? = null
    @Volatile private var lastGood: String? = null

    override fun askJson(
        system: String,
        history: List<ChatTurn>,
        schema: JSONObject,
        effort: Effort,
        maxTokens: Long,
    ): JSONObject {
        require(history.isNotEmpty() && history.last().fromUser) { "Last turn must be from the user" }

        val contents = JSONArray()
        for (turn in history) {
            contents.put(
                JSONObject()
                    .put("role", if (turn.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
            )
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put(
                "generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", toGeminiSchema(schema))
                    .put("maxOutputTokens", maxTokens.coerceAtMost(65536L))
                    .put("temperature", if (effort == Effort.LOW) 0.8 else 0.3)
            )
            .toString()

        var lastError: AiException? = null
        for (model in modelList()) {
            try {
                val json = generate(model, body)
                if (lastGood != model) {
                    lastGood = model
                    if (model != preferredModel) onModelChosen(model)
                }
                return json
            } catch (e: RetryOtherModel) {
                lastError = AiException(e.message ?: "Model error")
                continue
            }
        }
        throw lastError ?: AiException("Koi Gemini model available nahi mila.")
    }

    private class RetryOtherModel(message: String) : Exception(message)

    private fun generate(model: String, body: String): JSONObject {
        val req = Request.Builder()
            .url("$BASE/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody(JSON))
            .build()
        val (code, text) = try {
            http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            throw AiException("Internet connection check karein: ${e.message}")
        }

        if (code != 200) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                ?: text.take(200)
            when (code) {
                400 -> if (msg.contains("API key", ignoreCase = true))
                    throw AiException("Gemini API key galat hai. Settings me sahi key daalein (aistudio.google.com).")
                else throw RetryOtherModel("Gemini error: $msg")
                401, 403 -> throw AiException("Gemini API key kaam nahi kar rahi: $msg")
                404 -> throw RetryOtherModel("Model $model nahi mila")
                429 -> throw RetryOtherModel(
                    "Aaj ki free limit poori ho gayi ya bahut jaldi-jaldi requests ho gayi. 1 minute ruk kar try karein (ya kal)."
                )
                in 500..599 -> throw RetryOtherModel("Gemini server busy hai ($code). Thodi der baad try karein.")
                else -> throw AiException("Gemini error ($code): $msg")
            }
        }

        val root = JSONObject(text)
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        if (candidate == null) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw AiException("AI ne is message ka jawab nahi diya${if (reason.isNotBlank()) " ($reason)" else ""}. Kuch aur try karein.")
        }
        val finish = candidate.optString("finishReason")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val out = buildString {
            if (parts != null) for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                append(p.optString("text"))
            }
        }.trim()
        if (out.isEmpty()) {
            if (finish == "MAX_TOKENS") throw AiException("Jawab bahut lamba ho gaya. Dobara try karein.")
            throw AiException("AI se khaali jawab aaya ($finish). Dobara try karein.")
        }
        return parseJsonObject(out)
    }

    /** Models to try, best first. Uses the saved choice, then what the key can access. */
    private fun modelList(): List<String> {
        val good = lastGood
        candidates?.let { list -> return if (good == null) list else listOf(good) + (list - good) }
        val discovered = runCatching { discoverModels() }.getOrDefault(emptyList())
        val list = (listOf(preferredModel) + discovered + FALLBACK_MODELS)
            .filter { it.isNotBlank() }
            .distinct()
        candidates = list
        return list
    }

    private fun discoverModels(): List<String> {
        val req = Request.Builder()
            .url("$BASE/models?pageSize=1000")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        val text = http.newCall(req).execute().use { if (it.code == 200) it.body?.string() else null } ?: return emptyList()
        val models = JSONObject(text).optJSONArray("models") ?: return emptyList()
        val names = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")
            val canGenerate = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            val name = m.optString("name").removePrefix("models/")
            if (canGenerate) names.add(name)
        }
        return rankModels(names)
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Used if model discovery fails (e.g. no network for the list call). */
        val FALLBACK_MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite")

        private val SKIP = listOf("image", "tts", "audio", "live", "embedding", "vision", "thinking", "exp", "learnlm", "robotics", "computer")

        /**
         * Orders model names: stable Flash (newest version first), then Flash-Lite, then previews.
         * Pro models are left out because their free limits are tiny or zero.
         */
        fun rankModels(names: List<String>): List<String> {
            val version = Regex("""gemini-(\d+(?:\.\d+)?)""")
            fun ver(n: String) = version.find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val flash = names.filter { n ->
                n.startsWith("gemini-") && n.contains("flash") && SKIP.none { n.contains(it) }
            }
            fun tier(n: String): Int {
                val lite = n.contains("lite")
                val preview = n.contains("preview")
                val dated = Regex("""-\d{3}$|-\d{2}-\d{4}$""").containsMatchIn(n)
                return when {
                    !lite && !preview && !dated -> 0
                    lite && !preview && !dated -> 1
                    !preview -> 2
                    !lite -> 3
                    else -> 4
                }
            }
            return flash.sortedWith(compareBy<String>({ tier(it) }, { -ver(it) }, { it.length }))
        }

        /** Converts our JSON Schema into Gemini's responseSchema (OpenAPI subset). */
        fun toGeminiSchema(s: JSONObject): JSONObject {
            val out = JSONObject()
            val type = s.optString("type")
            if (type.isNotBlank()) out.put("type", type.uppercase())
            s.optJSONArray("enum")?.let { out.put("enum", it) }
            s.optJSONObject("properties")?.let { props ->
                val p = JSONObject()
                val order = JSONArray()
                for (k in props.keys()) {
                    p.put(k, toGeminiSchema(props.getJSONObject(k)))
                    order.put(k)
                }
                out.put("properties", p)
                out.put("propertyOrdering", order)
            }
            s.optJSONArray("required")?.let { out.put("required", it) }
            s.optJSONObject("items")?.let { out.put("items", toGeminiSchema(it)) }
            return out
        }
    }
}
