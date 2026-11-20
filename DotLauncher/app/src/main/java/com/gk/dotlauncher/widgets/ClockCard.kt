package com.gk.dotlauncher.widgets

import android.content.Context
import android.graphics.Canvas
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Bada dot-matrix digital clock + seconds ki dot-line. */
class ClockCard(context: Context) : DotCard(context, "clock") {
    override val tickMs = 1000L
    private val dateFmt = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())

    init { aspect = 0.46f }

    override fun drawContent(canvas: Canvas) {
        val cal = Calendar.getInstance()
        val h = if (prefs.use24h) cal.get(Calendar.HOUR_OF_DAY) else (cal.get(Calendar.HOUR).let { if (it == 0) 12 else it })
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val blink = !prefs.showSeconds || s % 2 == 0
        val time = String.format(Locale.US, "%02d%s%02d", h, if (blink) ":" else ";", m)

        // Width stable rahe isliye colon ki jagah hamesha ":" se naapte hain
        val cols = DotMatrix.columns(String.format(Locale.US, "%02d:%02d", h, m))
        val availW = width - pad * 2
        val availH = height * 0.56f
        val step = minOf(availW / cols, availH / DotMatrix.ROWS)
        val left = (width - cols * step) / 2
        val top = pad * 1.6f + labelPaint.textSize
        DotMatrix.draw(canvas, time, left, top, step, onPaint, offPaint, setOf(':'), accentPaint)

        label(canvas, dateFmt.format(cal.time))
        if (!prefs.use24h) {
            val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
            canvas.drawText(ampm, width - pad - labelPaint.measureText(ampm), pad + labelPaint.textSize, labelPaint)
        }

        if (prefs.showSeconds) {
            // 60 chhote dots: beete seconds safed, abhi wala accent
            val y = height - pad * 0.9f
            val gap = (width - pad * 2) / 60f
            val r = minOf(gap * 0.3f, dp(1.8f))
            for (i in 0 until 60) {
                val p = when {
                    i == s -> accentPaint
                    i < s -> dimPaint
                    else -> offPaint
                }
                canvas.drawCircle(pad + gap * i + gap / 2, y, if (i == s) r * 1.5f else r, p)
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
