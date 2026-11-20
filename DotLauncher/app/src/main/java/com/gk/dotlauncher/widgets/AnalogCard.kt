package com.gk.dotlauncher.widgets

import android.content.Context
import android.graphics.Canvas
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Dots se bani analog ghadi – hands bhi dotted. */
class AnalogCard(context: Context) : DotCard(context, "analog") {
    override val tickMs = 1000L

    override fun drawContent(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.38f
        val dotR = radius * 0.035f

        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val big = i % 5 == 0
            canvas.drawCircle(
                cx + (radius * cos(a)).toFloat(), cy + (radius * sin(a)).toFloat(),
                if (big) dotR * 1.6f else dotR, if (big) onPaint else offPaint
            )
        }

        val c = Calendar.getInstance()
        val sec = c.get(Calendar.SECOND)
        val min = c.get(Calendar.MINUTE) + sec / 60f
        val hr = c.get(Calendar.HOUR) + min / 60f
        hand(canvas, cx, cy, hr * 30f, radius * 0.5f, dotR * 1.5f)
        hand(canvas, cx, cy, min * 6f, radius * 0.78f, dotR * 1.25f)
        val sa = Math.toRadians(sec * 6.0 - 90)
        canvas.drawCircle(cx + (radius * 0.9f * cos(sa)).toFloat(), cy + (radius * 0.9f * sin(sa)).toFloat(), dotR * 2f, accentPaint)
        canvas.drawCircle(cx, cy, dotR * 2.4f, accentPaint)
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, deg: Float, len: Float, r: Float) {
        val a = Math.toRadians(deg - 90.0)
        val steps = (len / (r * 3.2f)).toInt()
        for (i in 2..steps) {
            val d = len * i / steps
            canvas.drawCircle(cx + (d * cos(a)).toFloat(), cy + (d * sin(a)).toFloat(), r, onPaint)
        }
    }
}
