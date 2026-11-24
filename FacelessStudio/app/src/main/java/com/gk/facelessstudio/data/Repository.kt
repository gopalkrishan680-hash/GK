package com.gk.facelessstudio.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.gk.facelessstudio.model.Project
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Repository(private val context: Context) {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    fun dir(id: String) = File(root, id).apply { mkdirs() }

    fun newProject(): Project = Project(id = UUID.randomUUID().toString().take(12), seed = System.currentTimeMillis())

    fun save(p: Project) {
        val f = File(dir(p.id), "project.json")
        val tmp = File(f.parentFile, "project.json.tmp")
        tmp.writeText(p.copy(updatedAt = System.currentTimeMillis()).toJson().toString())
        tmp.renameTo(f)
    }

    fun load(id: String): Project? = runCatching {
        Project.fromJson(JSONObject(File(dir(id), "project.json").readText()))
    }.getOrNull()

    fun all(): List<Project> = (root.listFiles() ?: emptyArray())
        .mapNotNull { d -> runCatching { Project.fromJson(JSONObject(File(d, "project.json").readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun delete(id: String) { File(root, id).deleteRecursively() }

    fun duplicate(p: Project): Project {
        val copy = p.copy(id = UUID.randomUUID().toString().take(12), title = p.title + " (copy)", lastExport = null)
        dir(p.id).copyRecursively(dir(copy.id), overwrite = true)
        fun remap(path: String?) = path?.replace("/${p.id}/", "/${copy.id}/")
        val fixed = copy.copy(
            voicePath = remap(p.voicePath), musicPath = remap(p.musicPath), images = p.images.map { remap(it)!! }
        )
        save(fixed)
        return fixed
    }

    /** Copies a picked file into the project folder so it survives after the picker permission expires. */
    fun import(projectId: String, uri: Uri, prefix: String): Pair<String, String>? = runCatching {
        val name = displayName(uri) ?: "$prefix.bin"
        val ext = name.substringAfterLast('.', "bin").take(5)
        val dest = File(dir(projectId), "${prefix}_${System.nanoTime()}.$ext")
        context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
        Pair(dest.absolutePath, name)
    }.getOrNull()

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun tempVideo(p: Project) = File(context.cacheDir, "render_${p.id}.mp4")

    /** Publishes the finished mp4 into the phone gallery (Movies/FacelessStudio). Returns a shareable Uri. */
    fun publishToGallery(file: File, title: String): Uri {
        val safe = title.ifBlank { "video" }.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "video" }
        val name = "${safe}_${System.currentTimeMillis() / 1000}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FacelessStudio")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            return uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "FacelessStudio").apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true); file.delete()
            return androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", dest)
        }
    }
}
