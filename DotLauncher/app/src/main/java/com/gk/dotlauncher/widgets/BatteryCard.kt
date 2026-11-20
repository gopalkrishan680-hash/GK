package com.gk.dotlauncher.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.BatteryManager
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Dots ki ring me battery level. Charging par ek accent dot ghoomta hai. */
class BatteryCard(context: Context) : DotCard(context, "battery") {
    private var level = 0
    private var charging = false
    private var frame = 0

    override val tickMs: Long get() = if (charging) 120L else 0L
    override fun onTick() { frame++ }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update(i)
    }

    private fun update(i: Intent?) {
        i ?: return
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val wasCharging = charging
        level = l * 100 / s
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        if (charging != wasCharging) restartTicker()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!registered) {
            update(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
            registered = true
        }
    }

    private var registered = false

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    override fun drawContent(canvas: Canvas) {
        label(canvas, "Battery")
        if (charging) {
            val t = "⚡"
            canvas.drawText(t, width - pad - labelPaint.measureText(t), pad + labelPaint.textSize, accentPaint.also { it.textSize = labelPaint.textSize })
        }
        val cx = width / 2f
        val cy = height * 0.56f
        val radius = min(width, height) * 0.32f
        val n = 36
        val lit = level * n / 100
        val low = level <= 20
        val dotR = radius * 0.07f
        for (i in 0 until n) {
            val a = Math.toRadians(i * 360.0 / n - 90)
            val p = when {
                charging && i == frame % n -> accentPaint
                i < lit -> if (low) accentPaint else onPaint
                else -> offPaint
            }
            canvas.drawCircle(cx + (radius * cos(a)).toFloat(), cy + (radius * sin(a)).toFloat(), dotR, p)
        }
        val txt = level.toString()
        val cols = DotMatrix.columns(txt)
        val step = min(radius * 1.15f / cols, radius * 0.9f / DotMatrix.ROWS)
        DotMatrix.draw(canvas, txt, cx - cols * step / 2, cy - DotMatrix.ROWS * step / 2, step, if (low) accentPaint else onPaint, null)
    }
}
