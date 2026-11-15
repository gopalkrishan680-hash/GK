package com.kahani.studio3d.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Stores every project as a folder inside app storage:
 *   files/projects/<id>/project.json
 *   files/projects/<id>/voice.<ext>, music.<ext>, img_<sceneId>.<ext>
 * Copying media into the app means the project keeps working even if the
 * original file in the gallery is moved or deleted.
 */
class ProjectRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val root: File get() = File(context.filesDir, "projects").apply { mkdirs() }

    fun dir(id: String) = File(root, id).apply { mkdirs() }

    fun list(): List<Project> = root.listFiles()
        ?.mapNotNull { d -> runCatching { load(d.name) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        ?: emptyList()

    fun load(id: String): Project? {
        val f = File(dir(id), "project.json")
        if (!f.exists()) return null
        return json.decodeFromString<Project>(f.readText())
    }

    fun save(p: Project) {
        val f = File(dir(p.id), "project.json")
        val tmp = File(dir(p.id), "project.json.tmp")
        tmp.writeText(json.encodeToString(p))
        tmp.renameTo(f)
    }

    fun create(title: String): Project {
        val now = System.currentTimeMillis()
        val p = Project(id = UUID.randomUUID().toString().take(12), title = title, createdAt = now, updatedAt = now)
        save(p)
        return p
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun duplicate(p: Project): Project {
        val now = System.currentTimeMillis()
        val newId = UUID.randomUUID().toString().take(12)
        File(root, p.id).copyRecursively(File(root, newId), overwrite = true)
        fun fix(path: String?) = path?.replace("/${p.id}/", "/$newId/")
        val copy = p.copy(
            id = newId, title = p.title + " (copy)", createdAt = now, updatedAt = now,
            voicePath = fix(p.voicePath), musicPath = fix(p.musicPath),
            scenes = p.scenes.map { it.copy(imagePath = fix(it.imagePath)) }
        )
        save(copy)
        return copy
    }

    /** Copies a content Uri into the project folder and returns the absolute path. */
    fun importFile(projectId: String, uri: Uri, baseName: String): String {
        val name = displayName(uri) ?: "file"
        val ext = name.substringAfterLast('.', "").lowercase().take(5).ifEmpty { "bin" }
        val d = dir(projectId)
        // remove older versions with same base name
        d.listFiles()?.filter { it.name.startsWith("$baseName.") }?.forEach { it.delete() }
        val out = File(d, "$baseName.$ext")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out.absolutePath
    }

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) = sp.edit().putString("gemini_key", v.trim()).apply()

    var geminiModel: String
        get() = sp.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) = sp.edit().putString("gemini_model", v.trim()).apply()

    var defaultStyle: VisualStyle
        get() = runCatching { VisualStyle.valueOf(sp.getString("style", "")!!) }.getOrDefault(VisualStyle.PIXAR_3D)
        set(v) = sp.edit().putString("style", v.name).apply()

    var defaultAspect: AspectRatio
        get() = runCatching { AspectRatio.valueOf(sp.getString("aspect", "")!!) }.getOrDefault(AspectRatio.PORTRAIT)
        set(v) = sp.edit().putString("aspect", v.name).apply()

    var defaultWatermark: String
        get() = sp.getString("watermark", "") ?: ""
        set(v) = sp.edit().putString("watermark", v).apply()

    var exportQuality: Int
        get() = sp.getInt("quality", 1080)
        set(v) = sp.edit().putInt("quality", v).apply()

    var exportFps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()
}
