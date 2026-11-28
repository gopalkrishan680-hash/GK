package com.gk.procam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything that writes to the gallery (DCIM/ProCam). */
object MediaSaver {
    const val RELATIVE_DIR = "DCIM/ProCam"

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun imageValues(ext: String = "jpg", mime: String = "image/jpeg", prefix: String = "IMG"): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_${stamp()}.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }

    fun videoValues(prefix: String = "VID"): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_${stamp()}.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
    }

    fun jpegOutputOptions(ctx: Context): ImageCapture.OutputFileOptions =
        ImageCapture.OutputFileOptions.Builder(
            ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageValues()
        ).build()

    fun rawOutputOptions(ctx: Context): ImageCapture.OutputFileOptions =
        ImageCapture.OutputFileOptions.Builder(
            ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageValues("dng", "image/x-adobe-dng")
        ).build()

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE,
    )

    /** Saves a processed bitmap as a high quality JPEG and carries over the original EXIF. */
    fun saveJpeg(ctx: Context, bmp: Bitmap, exifSource: ByteArray?, note: String): Uri? {
        val resolver = ctx.contentResolver
        val values = imageValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 97, it) }
            runCatching {
                val src = exifSource?.let { ExifInterface(ByteArrayInputStream(it)) }
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val dst = ExifInterface(pfd.fileDescriptor)
                    if (src != null) for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                    dst.setAttribute(ExifInterface.TAG_SOFTWARE, "ProCam ($note)")
                    dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    dst.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bmp.width.toString())
                    dst.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bmp.height.toString())
                    dst.saveAttributes()
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    fun copyVideoToGallery(ctx: Context, file: File, prefix: String): Uri? {
        val resolver = ctx.contentResolver
        val values = videoValues(prefix).apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null); null
        }
    }

    /** Most recent photo or video ProCam saved: (uri, mime). */
    fun latest(ctx: Context): Pair<Uri, String>? {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        return runCatching {
            ctx.contentResolver.query(collection, projection, selection, arrayOf("$RELATIVE_DIR%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(0)
                val mime = c.getString(1) ?: "image/jpeg"
                val isVideo = c.getInt(2) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                ContentUris.withAppendedId(base, id) to mime
            }
        }.getOrNull()
    }
}
