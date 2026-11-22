package com.gopalkrishan.stickmanstudio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube Data API calls with the access token from Google sign-in (the app's Android OAuth client):
 * resumable video upload (private, scheduled with publishAt), custom thumbnail and the first comment.
 */
object YouTube {
    private const val TAG = "SSAPP"
    val SCOPES = listOf("https://www.googleapis.com/auth/youtube.upload", "https://www.googleapis.com/auth/youtube.force-ssl")

    interface Progress { fun update(msg: String, frac: Double) }

    /** meta: {title, description, tags[], categoryId, publishAt, privacy, language, pinned} → result JSON. */
    fun upload(c: Context, token: String, video: Uri, meta: JSONObject, thumb: ByteArray?, prog: Progress): JSONObject {
        val size = fileSize(c, video)
        if (size <= 0) throw IOException("The exported video file is empty")
        val lang = meta.optString("language", "hi")
        val snippet = JSONObject()
            .put("title", meta.optString("title").take(100))
            .put("description", meta.optString("description").take(5000))
            .put("tags", meta.optJSONArray("tags") ?: JSONArray())
            .put("categoryId", meta.optString("categoryId", "27"))
            .put("defaultLanguage", lang).put("defaultAudioLanguage", lang)
        val status = JSONObject()
            .put("privacyStatus", meta.optString("privacy", "private"))
            .put("selfDeclaredMadeForKids", false)
        val publishAt = meta.optString("publishAt")
        if (publishAt.isNotEmpty()) status.put("privacyStatus", "private").put("publishAt", publishAt)
        val body = JSONObject().put("snippet", snippet).put("status", status).toString().toByteArray()

        // 1. start the resumable session
        val start = (URL("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", "video/mp4")
            setRequestProperty("X-Upload-Content-Length", size.toString())
            setFixedLengthStreamingMode(body.size)
        }
        start.outputStream.use { it.write(body) }
        if (start.responseCode !in 200..299) throw IOException("YouTube refused the upload: " + errorText(start))
        val session = start.getHeaderField("Location") ?: throw IOException("YouTube gave no upload address")
        start.disconnect()

        // 2. send the file, resuming after a dropped connection
        var sent = 0L; var tries = 0; var video0: JSONObject? = null
        while (video0 == null) {
            try {
                video0 = sendFrom(c, session, video, size, sent, prog)
            } catch (e: IOException) {
                if (++tries > 5) throw e
                Log.w(TAG, "upload interrupted, resuming: ${e.message}")
                Thread.sleep(3000L * tries)
                sent = queryOffset(session, size)
                if (sent < 0) throw e
            }
        }
        val id = video0.optString("id")
        val out = JSONObject().put("id", id).put("url", "https://youtu.be/$id").put("publishAt", publishAt)
            .put("status", video0.optJSONObject("status")?.optString("uploadStatus") ?: "")

        // 3. thumbnail (needs a verified channel) and the first comment
        if (thumb != null && thumb.isNotEmpty()) {
            prog.update("Setting the thumbnail…", 1.0)
            out.put("thumbnail", try { post("https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=$id", token, "image/jpeg", thumb); "ok" } catch (e: IOException) { e.message })
        }
        val pinned = meta.optString("pinned")
        if (pinned.isNotBlank()) {
            prog.update("Posting the comment…", 1.0)
            val cm = JSONObject().put("snippet", JSONObject().put("videoId", id)
                .put("topLevelComment", JSONObject().put("snippet", JSONObject().put("textOriginal", pinned))))
            out.put("comment", try { post("https://www.googleapis.com/youtube/v3/commentThreads?part=snippet", token, "application/json; charset=UTF-8", cm.toString().toByteArray()); "ok" } catch (e: IOException) { e.message })
        }
        return out
    }

    private fun sendFrom(c: Context, session: String, video: Uri, size: Long, from: Long, prog: Progress): JSONObject {
        val conn = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            setRequestProperty("Content-Type", "video/mp4")
            if (from > 0) setRequestProperty("Content-Range", "bytes $from-${size - 1}/$size")
            setFixedLengthStreamingMode(size - from)
            connectTimeout = 30_000; readTimeout = 120_000
        }
        c.contentResolver.openInputStream(video)?.use { input ->
            var skip = from; while (skip > 0) { val s = input.skip(skip); if (s <= 0) break; skip -= s }
            conn.outputStream.use { os ->
                val buf = ByteArray(256 * 1024); var done = from; var last = 0L
                while (true) {
                    val r = input.read(buf); if (r < 0) break
                    os.write(buf, 0, r); done += r
                    if (done - last > size / 50) { last = done; prog.update("Uploading to YouTube… ${done * 100 / size}%", done.toDouble() / size) }
                }
            }
        } ?: throw IOException("Cannot read the video file")
        val code = conn.responseCode
        if (code in 200..299) return JSONObject(conn.inputStream.bufferedReader().readText())
        if (code == 308) throw IOException("upload incomplete")
        if (code in 500..599) throw IOException("YouTube server error $code")
        throw IllegalStateException("YouTube upload failed: " + errorText(conn))
    }

    /** How many bytes YouTube already has, or -1 when the session is gone. */
    private fun queryOffset(session: String, size: Long): Long {
        val conn = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            setRequestProperty("Content-Range", "bytes */$size"); setFixedLengthStreamingMode(0)
        }
        conn.outputStream.close()
        if (conn.responseCode != 308) return -1
        val range = conn.getHeaderField("Range") ?: return 0
        return (range.substringAfter("-").toLongOrNull() ?: -1) + 1
    }

    private fun post(url: String, token: String, type: String, data: ByteArray): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Content-Type", type)
            setFixedLengthStreamingMode(data.size)
        }
        conn.outputStream.use { it.write(data) }
        if (conn.responseCode !in 200..299) throw IOException(errorText(conn))
        return conn.inputStream.bufferedReader().readText()
    }

    private fun errorText(conn: HttpURLConnection): String {
        val raw = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
        val msg = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
        return "${conn.responseCode} ${msg ?: raw.take(300)}"
    }

    private fun fileSize(c: Context, uri: Uri): Long {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) return it.getLong(0) }
        return c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
    }
}
