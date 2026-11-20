package com.gk.dotlauncher.widgets

import android.content.Context
import android.graphics.Canvas
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Mahine ke har din ka ek dot: beete din safed, aaj accent, aane wale grey. */
class CalendarCard(context: Context) : DotCard(context, "calendar") {
    override val tickMs = 60_000L
    private val monthFmt = SimpleDateFormat("MMMM", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())

    override fun drawContent(canvas: Canvas) {
        val cal = Calendar.getInstance()
        label(canvas, monthFmt.format(cal.time))
        val today = cal.get(Calendar.DAY_OF_MONTH)

        dotoPaint.textSize = height * 0.2f
        val dayTop = pad + labelPaint.textSize + dotoPaint.textSize * 1.05f
        canvas.drawText(String.format(Locale.US, "%02d", today), pad, dayTop, dotoPaint)
        val dn = dayFmt.format(cal.time).uppercase()
        canvas.drawText(dn, width - pad - labelPaint.measureText(dn), dayTop, labelPaint)

        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val first = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = (first.get(Calendar.DAY_OF_WEEK) - first.firstDayOfWeek + 7) % 7
        val rows = (offset + days + 6) / 7
        val gridTop = dayTop + pad * 0.6f
        val step = minOf((width - pad * 2) / 7f, (height - gridTop - pad * 0.7f) / rows)
        val r = step * 0.22f
        val gridLeft = (width - step * 7) / 2
        for (d in 1..days) {
            val idx = offset + d - 1
            val x = gridLeft + (idx % 7) * step + step / 2
            val y = gridTop + (idx / 7) * step + step / 2
            when {
                d == today -> canvas.drawCircle(x, y, r * 1.6f, accentPaint)
                d < today -> canvas.drawCircle(x, y, r, onPaint)
                else -> canvas.drawCircle(x, y, r, offPaint)
            }
        }
    }
}
