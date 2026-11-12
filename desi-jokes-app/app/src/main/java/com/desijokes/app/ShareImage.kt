package com.desijokes.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Draws a joke as a 1080x1350 card image (good for WhatsApp/Instagram status). */
object CardRenderer {
    const val AUTHORITY = "com.desijokes.app.share"

    fun render(ctx: Context, joke: Joke, cat: Category): Uri {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2B1640.toInt(), 0xFF121016.toInt(), Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, w.toFloat(), 20f, Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, 0xFFFF7A1A.toInt(), 0xFFFFC93C.toInt(), Shader.TileMode.CLAMP)
        })

        val pad = 90f
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (cat.adult) 0xFFE0245E.toInt() else 0xFFFFC93C.toInt()
            textSize = 40f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
        }
        c.drawText("${cat.emoji}  ${cat.name.uppercase()}", pad, 150f, label)

        // shrink the font until the joke fits
        val top = 220f; val bottom = h - 190f; val avail = bottom - top
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F1EA.toInt() }
        var size = 66f
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(joke.text, 0, joke.text.length, tp, (w - 2 * pad).toInt())
                .setLineSpacing(0f, 1.25f).build()
            if (layout.height <= avail || size <= 26f) break
            size -= 2f
        }
        c.save()
        c.translate(pad, top + (avail - layout.height) / 2f)
        layout.draw(c)
        c.restore()

        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A93A6.toInt(); textSize = 38f }
        c.drawText("😂 Desi Jokes", pad, h - 95f, foot)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val name = "joke_${joke.id}.png"
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return Uri.parse("content://$AUTHORITY/$name")
    }
}

/** Minimal read-only provider so other apps can read the shared card image. */
class ShareProvider : ContentProvider() {
    private fun file(uri: Uri) = File(File(context!!.cacheDir, "share"), File(uri.lastPathSegment ?: "").name)

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<String>?, sel: String?, args: Array<String>?, sort: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else f.name }.toTypedArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<String>?) = 0
    override fun delete(uri: Uri, sel: String?, args: Array<String>?) = 0
}
