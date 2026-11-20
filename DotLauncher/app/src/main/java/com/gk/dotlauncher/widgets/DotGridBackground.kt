package com.gk.dotlauncher.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.gk.dotlauncher.util.Prefs

/** Kaala wallpaper jisme halka dot grid. Setting off ho to system wallpaper dikhta hai. */
class DotGridBackground(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1C.toInt() }

    override fun onDraw(canvas: Canvas) {
        if (!Prefs(context).dotWallpaper) {
            // Thoda dim taaki widgets padhne me aayein
            canvas.drawColor(0x55000000)
            return
        }
        canvas.drawColor(Color.BLACK)
        val step = resources.displayMetrics.density * 20
        val r = resources.displayMetrics.density * 1.1f
        var y = step / 2
        while (y < height) {
            var x = step / 2
            while (x < width) {
                canvas.drawCircle(x, y, r, paint)
                x += step
            }
            y += step
        }
    }
}
