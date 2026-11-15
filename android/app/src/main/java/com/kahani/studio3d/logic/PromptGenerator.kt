package com.kahani.studio3d.logic

import com.kahani.studio3d.data.AspectRatio
import com.kahani.studio3d.data.Project
import com.kahani.studio3d.data.Scene
import com.kahani.studio3d.data.ShotType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

object PromptGenerator {

    private val shotCycle = listOf(
        ShotType.WIDE, ShotType.MEDIUM, ShotType.CLOSE, ShotType.LOW_ANGLE,
        ShotType.MEDIUM, ShotType.OVER_SHOULDER, ShotType.CLOSE, ShotType.TOP_DOWN
    )

    private fun aspectWords(a: AspectRatio) = when (a) {
        AspectRatio.PORTRAIT -> "vertical 9:16 composition, subject centered"
        AspectRatio.LANDSCAPE -> "widescreen 16:9 cinematic composition"
        AspectRatio.SQUARE -> "square 1:1 composition"
        AspectRatio.FOUR_FIVE -> "4:5 portrait composition"
    }

    fun charactersIn(p: Project, text: String) = p.characters.filter {
        it.name.isNotBlank() && text.contains(it.name, ignoreCase = true)
    }

    /** Offline prompt — works without internet or API key. */
    fun offline(p: Project, index: Int, scene: Scene): String {
        val shot = shotCycle[index % shotCycle.size].label
        val chars = charactersIn(p, scene.text)
            .joinToString("; ") { "${it.name}: ${it.description}" }
        return buildString {
            append(p.style.promptPrefix).append(", ").append(shot).append(". ")
            append("Scene: ").append(scene.text.trim().trimEnd('.', '।')).append(". ")
            if (chars.isNotEmpty()) append("Characters (keep exactly consistent): ").append(chars).append(". ")
            if (p.extraPrompt.isNotBlank()) append(p.extraPrompt.trim()).append(", ")
            append(p.style.promptSuffix).append(", ").append(aspectWords(p.aspect))
            append(", no text, no watermark")
        }
    }

    fun offlineAll(p: Project): List<Scene> = p.scenes.mapIndexed { i, s ->
        if (s.prompt.isNotBlank() && s.imagePath != null) s else s.copy(prompt = offline(p, i, s))
    }

    /**
     * Uses Google Gemini (free tier key from aistudio.google.com) to turn the script
     * (any language — Hindi/Hinglish too) into detailed English visual prompts
     * with consistent characters & locations across the whole story.
     */
    fun gemini(p: Project, key: String, model: String, onProgress: (Int, Int) -> Unit): List<Scene> {
        val result = p.scenes.toMutableList()
        val chunks = p.scenes.indices.chunked(20)
        var done = 0
        for (chunk in chunks) {
            val prompts = callGemini(p, chunk, key, model)
            chunk.forEachIndexed { k, idx ->
                val text = prompts.getOrNull(k)?.takeIf { it.isNotBlank() } ?: offline(p, idx, p.scenes[idx])
                result[idx] = result[idx].copy(prompt = text)
            }
            done += chunk.size
            onProgress(done, p.scenes.size)
        }
        return result
    }

    private fun callGemini(p: Project, idx: List<Int>, key: String, model: String): List<String> {
        val chars = if (p.characters.isEmpty()) "None defined - invent consistent looks and reuse them."
        else p.characters.joinToString("\n") { "- ${it.name}: ${it.description}" }
        val fullStory = p.scenes.joinToString(" ") { it.text }.take(6000)
        val sceneList = idx.joinToString("\n") { "${it + 1}. ${p.scenes[it].text}" }
        val instruction = """
You are a storyboard artist creating prompts for an AI image generator to make a 3D animated video.
Visual style: ${p.style.promptPrefix}; ${p.style.promptSuffix}.
Frame: ${aspectWords(p.aspect)}.
${if (p.extraPrompt.isNotBlank()) "Extra instructions: ${p.extraPrompt}" else ""}
Character bible (describe each character IDENTICALLY every time they appear - same face, age, clothes, colors):
$chars

Full story for context: $fullStory

For EACH numbered narration line below, write ONE detailed English image prompt (45-80 words) that visually shows that moment.
Include: subject + action + emotion, setting, camera shot type (vary shots: wide, medium, close-up, low angle...), lighting, mood, and the style keywords.
Never put written text, captions or letters in the image. Do not number prompts.
Narration lines:
$sceneList

Return ONLY a JSON array of ${idx.size} strings, in the same order.
""".trimIndent()

        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", instruction) }) })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("temperature", 0.8)
            })
        }.toString()

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) {
            val msg = runCatching {
                Json.parseToJsonElement(resp).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            }.getOrDefault(resp.take(200))
            throw RuntimeException("Gemini error $code: $msg")
        }
        val text = Json.parseToJsonElement(resp).jsonObject["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            .joinToString("") { (it as JsonObject)["text"]?.jsonPrimitive?.content ?: "" }
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = Json.parseToJsonElement(cleaned)
        return when (arr) {
            is JsonArray -> arr.map { e ->
                when (e) {
                    is JsonPrimitive -> e.content
                    is JsonObject -> (e["prompt"] ?: e.values.first()).jsonPrimitive.content
                    else -> ""
                }
            }
            else -> emptyList()
        }
    }

    /** Numbered text, ready to paste into any image generator or share. */
    fun exportText(p: Project, withNegative: Boolean): String = buildString {
        append("${p.title} — ${p.scenes.size} image prompts (${p.style.label}, ${p.aspect.label})\n\n")
        p.scenes.forEachIndexed { i, s ->
            append("Scene ${i + 1}:\n").append(s.prompt).append("\n\n")
        }
        if (withNegative && p.negativePrompt.isNotBlank()) append("Negative prompt: ${p.negativePrompt}\n")
    }
}
