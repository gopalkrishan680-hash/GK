package com.gk.papercut.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional online helper: with a free Gemini API key it rewrites every scene's image prompt
 * (in English, more visual) and suggests elements + animations. Everything else stays offline.
 */
class GeminiDirector(private val apiKey: String, private val model: String) {

    fun improve(project: Project, scenes: List<Scene>): List<Scene> {
        val enters = EnterAnim.entries.joinToString("|") { it.name }
        val idles = IdleAnim.entries.joinToString("|") { it.name }
        val cams = CameraMove.entries.joinToString("|") { it.name }
        val instruction = buildString {
            appendLine("You direct a paper cut-out animation video (${project.aspect.ratio}). The narration may be Hindi, Hinglish or English.")
            appendLine("For every scene return an object with:")
            appendLine("  prompt: one detailed ENGLISH image prompt in layered handmade paper cut-out style. Every character/object must be a separate cut-out with a thin white edge, not overlapping, on a simple background, no text in the image.")
            appendLine("  elements: up to 4 objects {name, keyword, enter, idle}; keyword = the exact word from the scene text when it should appear; enter one of $enters; idle one of $idles.")
            appendLine("  camera: one of $cams")
            if (project.characterNote.isNotBlank()) appendLine("Characters must look identical in every prompt: ${project.characterNote}")
            if (project.styleNote.isNotBlank()) appendLine("Overall style: ${project.styleNote}")
            appendLine("Return ONLY a JSON array with exactly ${scenes.size} objects in order.")
            appendLine()
            scenes.forEachIndexed { i, s -> appendLine("Scene ${i + 1}: ${s.text}") }
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.7))
        val text = post(body)
        val arr = JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1))
        return scenes.mapIndexed { i, s ->
            val o = arr.optJSONObject(i) ?: return@mapIndexed s
            val sceneWords = ScriptPlanner.words(s.text).map(ScriptPlanner::normalize)
            val els = o.optJSONArray("elements") ?: JSONArray()
            val hints = (0 until els.length()).mapNotNull { k ->
                val e = els.optJSONObject(k) ?: return@mapNotNull null
                val kw = ScriptPlanner.normalize(e.optString("keyword"))
                ElementHint(
                    name = e.optString("name", "element"),
                    wordIndex = sceneWords.indexOfFirst { it == kw || (kw.length > 3 && it.startsWith(kw)) },
                    enter = enumOr(e.optString("enter"), EnterAnim.POP),
                    idle = enumOr(e.optString("idle"), IdleAnim.BOB),
                )
            }
            val prompt = o.optString("prompt").ifBlank { s.prompt }
            s.copy(
                prompt = if (prompt.contains("No text", ignoreCase = true)) prompt else "$prompt ${ScriptPlanner.STYLE_SUFFIX}",
                elementsHint = hints.ifEmpty { s.elementsHint },
                camera = enumOr(o.optString("camera"), s.camera),
            )
        }
    }

    /** Any JSON answer from the text model. */
    fun generateJson(prompt: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.8))
        return post(body)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, def: T): T =
        runCatching { enumValueOf<T>(name.trim().uppercase()) }.getOrDefault(def)

    private fun post(body: JSONObject): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30_000
        c.readTimeout = 120_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", apiKey)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) error("Gemini error $code: ${resp.take(300)}")
        val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
    }
}
