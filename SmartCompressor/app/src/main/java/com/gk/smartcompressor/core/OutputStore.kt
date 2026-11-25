package com.gk.smartcompressor.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Compressed files ko phone ke Download/SmartCompressor folder me save karta hai. */
object OutputStore {
    const val FOLDER = "SmartCompressor"

    fun save(ctx: Context, src: File, displayName: String, mime: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Download folder me file nahi ban payi")
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            dir.mkdirs()
            var target = File(dir, displayName)
            var i = 1
            while (target.exists()) {
                target = File(dir, displayName.substringBeforeLast('.') + "($i)." + displayName.substringAfterLast('.'))
                i++
            }
            src.copyTo(target)
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", target)
        }
    }

    fun outName(srcName: String, ext: String): String {
        val base = srcName.substringBeforeLast('.').ifBlank { "file" }
        return "${base}_compressed.$ext"
    }
}
