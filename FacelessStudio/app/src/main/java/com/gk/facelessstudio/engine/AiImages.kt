package com.gk.facelessstudio.engine

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free AI images via Pollinations.ai (no account / API key).
 * 1) an LLM turns each script line (any language) into a concrete English visual prompt,
 * 2) an image model draws it. Everything falls back gracefully when the service is unreachable.
 */
object AiImages {
    private const val IMAGE_URL = "https://image.pollinations.ai/prompt/"
    private const val TEXT_URL = "https://text.pollinations.ai/openai"

    /** Returns one English prompt per line, or null if the text service failed. */
    fun enhancePrompts(lines: List<String>, topic: String, style: String): List<String>? = runCatching {
        val numbered = lines.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")
        val system = "You write prompts for an AI image generator for a faceless YouTube video" +
            (if (topic.isNotBlank()) " about \"$topic\"" else "") + ". " +
            "For EACH numbered script line (it may be Hindi, Hinglish or English) write ONE English prompt of 25-45 words " +
            "describing a concrete visual scene that illustrates that line: main subject, setting, action, camera angle and mood. " +
            "Keep characters and style consistent across lines. Visual style: $style. Never ask questions, never include text or letters in the scene. " +
            "Reply ONLY with the numbered list using the same numbers."
        val body = JSONObject()
            .put("model", "openai")
            .put("seed", 7)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", numbered)))
        val conn = (URL(TEXT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 20_000; readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) return null
        val raw = conn.inputStream.bufferedReader().readText()
        val content = runCatching {
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }.getOrDefault(raw)
        val out = arrayOfNulls<String>(lines.size)
        Regex("^\\s*(\\d+)[.)]\\s*(.+)$", RegexOption.MULTILINE).findAll(content).forEach { m ->
            val idx = m.groupValues[1].toInt() - 1
            if (idx in out.indices && out[idx] == null) out[idx] = m.groupValues[2].trim().trim('"')
        }
        if (out.count { it != null } < lines.size / 2) return null
        out.mapIndexed { i, p -> p ?: lines[i] }
    }.getOrNull()

    fun finalPrompt(visual: String, style: String, ratio: String) =
        "$visual. $style, cinematic lighting, dramatic shadows, high detail, aspect ratio $ratio, no text, no watermark"

    /** Downloads one generated image to [dest]. Returns true when a valid image was saved. */
    fun generate(prompt: String, width: Int, height: Int, seed: Int, dest: File): Boolean {
        repeat(3) { attempt ->
            val ok = runCatching {
                val q = URLEncoder.encode(prompt.take(900), "UTF-8").replace("+", "%20")
                val url = "$IMAGE_URL$q?width=$width&height=$height&seed=$seed&nologo=true&model=flux"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 150_000 }
                if (conn.responseCode !in 200..299) return@runCatching false
                val tmp = File(dest.parentFile, dest.name + ".part")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.absolutePath, o)
                if (o.outWidth <= 0) { tmp.delete(); false } else tmp.renameTo(dest)
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(2000L * (attempt + 1))
        }
        return false
    }
}
