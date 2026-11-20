package com.gk.dotlauncher.widgets

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.widget.EditText
import com.gk.dotlauncher.util.Fonts
import com.gk.dotlauncher.util.Palette

/** Chhota sa sticky note. Tap karo, likho, home par dikh jayega. */
class NoteCard(context: Context) : DotCard(context, "note") {

    init {
        setOnClickListener {
            val input = EditText(context).apply {
                setText(prefs.note)
                setSelection(text.length)
                typeface = Fonts.mono(context)
                minLines = 3
                setTextColor(Palette.TEXT)
            }
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Quick note")
                .setView(input)
                .setPositiveButton("Save") { _, _ -> prefs.note = input.text.toString(); invalidate() }
                .setNeutralButton("Clear") { _, _ -> prefs.note = ""; invalidate() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun drawContent(canvas: Canvas) {
        label(canvas, "Note")
        canvas.drawCircle(width - pad - 4f, pad + labelPaint.textSize * 0.6f, labelPaint.textSize * 0.3f, accentPaint)
        val note = prefs.note
        val tp = android.text.TextPaint(labelPaint).apply {
            letterSpacing = 0f; textSize = labelPaint.textSize * 1.25f
            color = if (note.isEmpty()) Palette.DIM else Palette.TEXT
        }
        val text = note.ifEmpty { "Tap to write…" }
        val w = (width - pad * 2).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(((height - pad * 3.2f) / (tp.textSize * 1.3f)).toInt().coerceAtLeast(1))
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(pad, pad * 2.2f + labelPaint.textSize)
        layout.draw(canvas)
        canvas.restore()
    }
}
