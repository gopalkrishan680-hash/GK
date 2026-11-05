package com.gk.procamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

object ImageProcessing {

    // ---------------------------------------------------------------- saving

    /** ContentValues for a new JPEG in Pictures/ProCam. */
    fun newImageValues(pending: Boolean): ContentValues {
        val name = "ProCam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProCam")
                if (pending) put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ProCam")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, "$name.jpg").absolutePath)
            }
        }
    }

    fun saveBitmap(context: Context, bitmap: Bitmap, quality: Int = 96): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newImageValues(pending = true))
            ?: throw IOException("Could not create gallery entry")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open gallery file")
            stream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        bitmap.recycle()
        return uri
    }

    // ---------------------------------------------------------------- decoding

    private fun exifMatrix(orientation: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(270f)
        }
        return m
    }

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = exifMatrix(orientation)
        if (m.isIdentity) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Decodes a JPEG file to an upright, mutable ARGB bitmap. */
    fun decodeUpright(file: File): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: throw IOException("Decode failed")
        val orientation = ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val upright = applyOrientation(raw, orientation)
        return if (upright.isMutable) upright else upright.copy(Bitmap.Config.ARGB_8888, true).also { upright.recycle() }
    }

    fun loadThumbnail(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(240, 240), null)
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            bmp?.let { applyOrientation(it, orientation) }
        }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- filters

    private fun clamp255(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> v.toInt()
    }

    /** Applies a colour matrix in place (row by row, low memory). */
    fun applyFilter(src: Bitmap, cm: ColorMatrix): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        val a = cm.array
        val w = bmp.width
        val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = (p shr 16 and 0xff).toFloat()
                val g = (p shr 8 and 0xff).toFloat()
                val b = (p and 0xff).toFloat()
                val nr = clamp255(a[0] * r + a[1] * g + a[2] * b + a[3] * 255f + a[4])
                val ng = clamp255(a[5] * r + a[6] * g + a[7] * b + a[8] * 255f + a[9])
                val nb = clamp255(a[10] * r + a[11] * g + a[12] * b + a[13] * 255f + a[14])
                row[x] = (0xff shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return bmp
    }

    // ---------------------------------------------------------------- night stacking

    private class Luma(val w: Int, val h: Int, val data: IntArray)

    private const val SMALL_W = 320
    private const val SEARCH = 16

    private fun luma(p: Int): Int = ((p shr 16 and 0xff) * 77 + (p shr 8 and 0xff) * 150 + (p and 0xff) * 29) shr 8

    private fun smallLuma(b: Bitmap): Luma {
        val sw = SMALL_W
        val sh = (b.height * sw.toFloat() / b.width).roundToInt().coerceAtLeast(SEARCH * 3)
        val s = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh)
        s.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (s !== b) s.recycle()
        for (i in px.indices) px[i] = luma(px[i])
        return Luma(sw, sh, px)
    }

    /** Finds the (dx, dy) shift of [frame] relative to [base]; returns shift and mean error. */
    private fun align(base: Luma, frame: Luma): Triple<Int, Int, Float> {
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        var samples = 1
        for (dy in -SEARCH..SEARCH) {
            for (dx in -SEARCH..SEARCH) {
                var cost = 0L
                var n = 0
                var y = SEARCH
                while (y < base.h - SEARCH) {
                    val row = y * base.w
                    val frow = (y + dy) * base.w + dx
                    var x = SEARCH
                    while (x < base.w - SEARCH) {
                        cost += abs(base.data[row + x] - frame.data[frow + x])
                        n++
                        x += 2
                    }
                    if (cost >= best) break
                    y += 2
                }
                if (cost < best) {
                    best = cost; bx = dx; by = dy; samples = n
                }
            }
        }
        return Triple(bx, by, best.toFloat() / samples.coerceAtLeast(1))
    }

    /**
     * Software "Night mode": aligns several handheld frames to the first one and averages them.
     * Noise falls roughly with sqrt(frames); pixels that moved (people, cars) fall back to the
     * reference frame to avoid ghosting. A gentle shadow lift is applied at the end.
     */
    fun stackFrames(files: List<File>): Bitmap {
        val base = decodeUpright(files[0])
        val w = base.width
        val h = base.height
        val n = w * h
        val maxFrames = if (n > 14_000_000) 4 else 6
        val sumR = ShortArray(n)
        val sumG = ShortArray(n)
        val sumB = ShortArray(n)
        val row = IntArray(w)
        val baseRow = IntArray(w)

        for (y in 0 until h) {
            base.getPixels(row, 0, w, 0, y, w, 1)
            val off = y * w
            for (x in 0 until w) {
                val p = row[x]
                sumR[off + x] = (p shr 16 and 0xff).toShort()
                sumG[off + x] = (p shr 8 and 0xff).toShort()
                sumB[off + x] = (p and 0xff).toShort()
            }
        }
        var count = 1
        val baseSmall = smallLuma(base)
        val scale = w.toFloat() / SMALL_W

        for (f in files.drop(1).take(maxFrames - 1)) {
            val frame = try { decodeUpright(f) } catch (e: Exception) { continue }
            if (frame.width != w || frame.height != h) { frame.recycle(); continue }
            val (sdx, sdy, err) = align(baseSmall, smallLuma(frame))
            if (err > 22f) { frame.recycle(); continue } // too blurry / too different
            val dx = (sdx * scale).roundToInt()
            val dy = (sdy * scale).roundToInt()
            for (y in 0 until h) {
                val sy = (y + dy).coerceIn(0, h - 1)
                frame.getPixels(row, 0, w, 0, sy, w, 1)
                base.getPixels(baseRow, 0, w, 0, y, w, 1)
                val off = y * w
                for (x in 0 until w) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    var p = row[sx]
                    val q = baseRow[x]
                    if (abs(luma(p) - luma(q)) > 48) p = q // de-ghost moving objects
                    val i = off + x
                    sumR[i] = (sumR[i] + (p shr 16 and 0xff)).toShort()
                    sumG[i] = (sumG[i] + (p shr 8 and 0xff)).toShort()
                    sumB[i] = (sumB[i] + (p and 0xff)).toShort()
                }
            }
            frame.recycle()
            count++
        }

        // Averaging LUT with shadow lift (gamma 0.85).
        val maxSum = 255 * count
        val lut = IntArray(maxSum + 1) { s ->
            val v = s.toFloat() / count / 255f
            (255f * v.toDouble().pow(0.85).toFloat()).roundToInt().coerceIn(0, 255)
        }
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                val i = off + x
                row[x] = (0xff shl 24) or (lut[sumR[i].toInt()] shl 16) or (lut[sumG[i].toInt()] shl 8) or lut[sumB[i].toInt()]
            }
            base.setPixels(row, 0, w, 0, y, w, 1)
        }
        return base
    }
}
