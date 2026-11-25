package com.gk.smartcompressor.compress

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.gk.smartcompressor.core.ImageFormat
import com.gk.smartcompressor.core.Job
import com.gk.smartcompressor.core.Level
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Photo compression: smart resize + JPEG/WebP re-encode, EXIF rotation sahi rakhta hai. */
object ImageCompressor : Compressor {

    fun loadBitmap(ctx: Context, job: Job, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(job.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(job.uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Photo open nahi hui")
        val orientation = runCatching {
            ctx.contentResolver.openInputStream(job.uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        val longSide = max(bmp.width, bmp.height)
        if (longSide > maxDim) {
            val s = maxDim.toFloat() / longSide
            m.postScale(s, s)
        }
        if (!m.isIdentity) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r != bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    private fun encode(bmp: Bitmap, fmt: ImageFormat, quality: Int): ByteArray {
        val format = when {
            fmt == ImageFormat.WEBP && Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
            fmt == ImageFormat.WEBP -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val bos = ByteArrayOutputStream()
        val src = if (format == Bitmap.CompressFormat.JPEG && bmp.hasAlpha()) flattenWhite(bmp) else bmp
        src.compress(format, quality, bos)
        if (src != bmp) src.recycle()
        return bos.toByteArray()
    }

    private fun flattenWhite(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(b, 0f, 0f, null)
        return out
    }

    override suspend fun compress(ctx: Context, job: Job, workDir: File, progress: Progress): CompressResult =
        withContext(Dispatchers.Default) {
            val o = job.options
            var maxDim = when (o.level) {
                Level.HIGH_QUALITY -> 3264
                Level.BALANCED -> 2400
                Level.MAX_SAVING -> 1600
            }
            val baseQ = when (o.level) {
                Level.HIGH_QUALITY -> 85
                Level.BALANCED -> 75
                Level.MAX_SAVING -> 62
            }
            val target = job.targetBytes()
            progress(0.1f, "Photo load ho rahi hai…")
            var bmp = loadBitmap(ctx, job, if (target != null) 3264 else maxDim)
            var bytes: ByteArray

            if (target == null) {
                bytes = encode(bmp, o.imageFormat, baseQ)
            } else {
                // Target size tak pahunchne ke liye quality ka binary search, zarurat ho to resize
                var best: ByteArray? = null
                var attempt = 0
                while (true) {
                    coroutineContext.ensureActive()
                    var lo = 45; var hi = 92
                    var found: ByteArray? = null
                    while (lo <= hi) {
                        val mid = (lo + hi) / 2
                        val b = encode(bmp, o.imageFormat, mid)
                        if (b.size <= target) { found = b; lo = mid + 1 } else hi = mid - 1
                    }
                    progress(0.3f + attempt * 0.15f, "Target size dhoond rahe hain…")
                    if (found != null) { best = found; break }
                    if (attempt >= 4) { best = encode(bmp, o.imageFormat, 45); break }
                    val nb = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.8).roundToInt(), (bmp.height * 0.8).roundToInt(), true)
                    bmp.recycle(); bmp = nb
                    attempt++
                }
                bytes = best!!
            }
            bmp.recycle()
            val ext = if (o.imageFormat == ImageFormat.WEBP) "webp" else "jpg"
            val out = File(workDir, "out_${job.id}.$ext")
            out.writeBytes(bytes)
            progress(1f, "")
            CompressResult(out, ext, if (ext == "webp") "image/webp" else "image/jpeg")
        }
}
