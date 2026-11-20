package com.gk.dotlauncher.widgets

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.os.Environment
import android.os.StatFs

/** Storage aur RAM kitni bhari hai – dot bars me. */
class StorageCard(context: Context) : DotCard(context, "storage") {
    override val tickMs = 15_000L
    private var disk = 0f
    private var ram = 0f
    private var freeGb = 0f

    override fun onTick() {
        runCatching {
            val st = StatFs(Environment.getDataDirectory().path)
            disk = 1f - st.availableBytes.toFloat() / st.totalBytes
            freeGb = st.availableBytes / 1_073_741_824f
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        ram = 1f - mi.availMem.toFloat() / mi.totalMem
    }

    override fun drawContent(canvas: Canvas) {
        label(canvas, "Storage")
        dotoPaint.textSize = height * 0.24f
        val pct = "${(disk * 100).toInt()}%"
        val y = pad + labelPaint.textSize + dotoPaint.textSize * 1.05f
        canvas.drawText(pct, pad, y, dotoPaint)
        val free = String.format("%.0f GB FREE", freeGb)
        canvas.drawText(free, pad, y + labelPaint.textSize * 1.6f, labelPaint)

        bar(canvas, "DISK", disk, height - pad * 2.6f)
        bar(canvas, "RAM", ram, height - pad * 1.1f)
    }

    private fun bar(canvas: Canvas, name: String, v: Float, y: Float) {
        val n = 10
        val labelW = labelPaint.measureText("DISK ")
        canvas.drawText(name, pad, y + labelPaint.textSize * 0.35f, labelPaint)
        val left = pad + labelW
        val step = (width - pad - left) / n
        val lit = (v * n + 0.5f).toInt()
        for (i in 0 until n) {
            val p = when {
                i < lit && v > 0.85f -> accentPaint
                i < lit -> onPaint
                else -> offPaint
            }
            canvas.drawCircle(left + step * i + step / 2, y, step * 0.28f, p)
        }
    }
}
