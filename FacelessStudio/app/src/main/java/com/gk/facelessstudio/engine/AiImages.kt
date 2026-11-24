package com.gk.facelessstudio.engine

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Free AI images via Pollinations.ai (no account / API key).
 * 1) an LLM writes a fixed "look" for the main characters and places,
 * 2) it turns each script line (any language) into a concrete English visual prompt,
 * 3) an image model draws it, ONE request at a time (the free tier rejects parallel calls).
 */
object AiImages {
    private const val IMAGE_URL = "https://image.pollinations.ai/prompt/"
    private const val TEXT_URL = "https://text.pollinations.ai/openai"
    private const val TEXT_GET_URL = "https://text.pollinations.ai/"

    /** Minimum pause between two requests to the free service. */
    private const val GAP_MS = 4_000L
    @Volatile private var lastCall = 0L

    private fun throttle() {
        synchronized(this) {
            val wait = lastCall + GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
        }
    }

    /** One LLM call with retries; POST first, plain GET as a fallback. */
    private fun ask(system: String, user: String): String? {
        repeat(3) { attempt ->
            throttle()
            val r = runCatching {
                val body = JSONObject().put("model", "openai").put("seed", 7).put("private", true)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                val conn = (URL(TEXT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 20_000; readTimeout = 90_000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode !in 200..299) null else {
                    val raw = conn.inputStream.bufferedReader().readText()
                    runCatching { JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }.getOrDefault(raw)
                }
            }.getOrNull() ?: runCatching {
                throttle()
                val q = URLEncoder.encode("$system\n\n$user", "UTF-8").replace("+", "%20")
                val conn = (URL("$TEXT_GET_URL$q?model=openai&private=true").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 90_000
                }
                if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
            }.getOrNull()
            if (!r.isNullOrBlank()) return r
            Thread.sleep(3000L * (attempt + 1))
        }
        return null
    }

    /** Fixed visual description of the main characters / places, reused in every prompt for consistency. */
    fun characterBible(script: String, topic: String): String? {
        val system = "You prepare a visual bible for an illustrated YouTube story" + (if (topic.isNotBlank()) " about \"$topic\"" else "") + ". " +
            "Read the script (it may be Hindi, Hinglish or English). List up to 6 main characters or places. For each give ONE line: " +
            "Name: fixed English visual description (age, build, face, hair, clothing, colors, era). Reply with the list only."
        val out = ask(system, script.take(6000)) ?: return null
        return out.lines().map { it.trim().trimStart('-', '*', ' ') }.filter { it.contains(':') && it.length < 300 }.take(6)
            .joinToString("; ").takeIf { it.isNotBlank() }
    }

    /** Returns one English prompt per line, or null if the text service failed. */
    fun enhancePrompts(lines: List<String>, topic: String, style: String, bible: String? = null): List<String>? {
        val numbered = lines.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")
        val system = "You write prompts for an AI image generator for a faceless YouTube video" +
            (if (topic.isNotBlank()) " about \"$topic\"" else "") + ". " +
            "For EACH numbered script line (it may be Hindi, Hinglish or English) write ONE English prompt of 25-45 words " +
            "describing a concrete visual scene that illustrates exactly what that line says: who is in it, what they are doing, where, camera angle and mood. " +
            "Name the characters and describe them visually. Every line must get a DIFFERENT scene. " +
            (if (!bible.isNullOrBlank()) "Always draw characters exactly like this: $bible. " else "") +
            "Visual style: $style. Never ask questions, never include text or letters in the scene. " +
            "Reply ONLY with the numbered list using the same numbers, in English."
        val content = ask(system, numbered) ?: return null
        val out = arrayOfNulls<String>(lines.size)
        Regex("^\\s*(\\d+)[.)]\\s*(.+)$", RegexOption.MULTILINE).findAll(content).forEach { m ->
            val idx = m.groupValues[1].toInt() - 1
            if (idx in out.indices && out[idx] == null) out[idx] = m.groupValues[2].trim().trim('"', '*')
        }
        if (out.count { it != null } < (lines.size + 1) / 2) return null
        return out.mapIndexed { i, p -> p ?: lines[i] }
    }

    fun finalPrompt(visual: String, style: String, ratio: String) =
        "$visual. $style, cinematic lighting, dramatic shadows, sharp focus, highly detailed, aspect ratio $ratio, no text, no watermark"

    private fun md5(f: File): String = MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /**
     * Downloads one generated image to [dest]. Rejects pictures identical to ones we already have
     * ([seen], the service sends repeats / placeholders when busy) and retries with a new seed.
     */
    fun generate(prompt: String, width: Int, height: Int, seed: Int, dest: File, seen: MutableSet<String> = HashSet()): Boolean {
        for (attempt in 0 until 5) {
            throttle()
            var retryAfterMs = 0L
            val ok = runCatching {
                val q = URLEncoder.encode(prompt.take(900), "UTF-8").replace("+", "%20")
                val url = "$IMAGE_URL$q?width=$width&height=$height&seed=${seed + attempt * 7919}&nologo=true&private=true&model=flux"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 180_000 }
                val code = conn.responseCode
                if (code == 429) retryAfterMs = (conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 20L) * 1000
                if (code !in 200..299) return@runCatching false
                if (conn.contentType?.startsWith("image") == false) return@runCatching false
                val tmp = File(dest.parentFile, dest.name + ".part")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.absolutePath, o)
                val hash = if (o.outWidth > 0) md5(tmp) else ""
                if (o.outWidth < 256 || hash in seen) { tmp.delete(); false }
                else { seen += hash; tmp.renameTo(dest) }
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(maxOf(retryAfterMs, 5000L * (attempt + 1)))
        }
        return false
    }

    fun hashOf(path: String): String? = runCatching { md5(File(path)) }.getOrNull()
}
