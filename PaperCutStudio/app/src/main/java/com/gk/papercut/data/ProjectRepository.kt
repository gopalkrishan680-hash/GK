package com.gk.papercut.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Stores every project as a folder: project.json + images + audio. */
class ProjectRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun dir(projectId: String) = File(root, projectId).apply { mkdirs() }
    fun file(projectId: String, name: String) = File(dir(projectId), name)

    fun list(): List<Project> = root.listFiles().orEmpty()
        .mapNotNull { d -> runCatching { load(d.name) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun load(id: String): Project =
        json.decodeFromString(Project.serializer(), File(dir(id), "project.json").readText())

    fun save(p: Project): Project {
        val out = p.copy(updatedAt = System.currentTimeMillis())
        val tmp = File(dir(p.id), "project.json.tmp")
        tmp.writeText(json.encodeToString(Project.serializer(), out))
        tmp.renameTo(File(dir(p.id), "project.json"))
        return out
    }

    fun create(name: String): Project = save(Project(id = UUID.randomUUID().toString().take(8), name = name))

    fun delete(id: String) { dir(id).deleteRecursively() }

    /** Copies a picked file (audio / srt / image) into the project folder. */
    fun importUri(projectId: String, uri: Uri, name: String): File {
        val f = file(projectId, name)
        context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    fun saveBitmap(projectId: String, name: String, bmp: Bitmap): String {
        file(projectId, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    fun loadBitmap(projectId: String, name: String?): Bitmap? {
        if (name == null) return null
        val f = file(projectId, name)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = false }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    /** Decodes an image URI, downscaled so the longest side is at most [maxSide]. */
    fun decodeScaled(uri: Uri, maxSide: Int = 1600): Bitmap {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Could not read this image")
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(v) = prefs.edit().putString("gemini_key", v.trim()).apply()
    var textModel: String
        get() = prefs.getString("text_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) = prefs.edit().putString("text_model", v.trim()).apply()
}
