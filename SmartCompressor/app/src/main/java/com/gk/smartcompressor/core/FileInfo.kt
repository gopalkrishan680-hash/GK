package com.gk.smartcompressor.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

data class SourceInfo(val name: String, val size: Long, val mime: String, val kind: FileKind)

object FileInfo {
    fun read(ctx: Context, uri: Uri): SourceInfo {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size < 0) size = runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: 0L
        var mime = ctx.contentResolver.getType(uri)
        if (mime.isNullOrBlank() || mime == "application/octet-stream") {
            val ext = name.substringAfterLast('.', "").lowercase()
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        return SourceInfo(name, size, mime, kindOf(mime, name))
    }

    fun kindOf(mime: String, name: String): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("video/") -> FileKind.VIDEO
            mime.startsWith("image/") && ext != "gif" && ext != "svg" -> FileKind.IMAGE
            mime == "application/pdf" || ext == "pdf" -> FileKind.PDF
            mime.startsWith("audio/") -> FileKind.AUDIO
            else -> FileKind.OTHER
        }
    }
}

object Fmt {
    fun size(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    fun saved(orig: Long, now: Long): Int =
        if (orig <= 0) 0 else (((orig - now) * 100.0) / orig).toInt().coerceIn(0, 100)
}
