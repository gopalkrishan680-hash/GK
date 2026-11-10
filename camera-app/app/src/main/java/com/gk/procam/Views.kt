package com.gk.procam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Rule-of-thirds grid drawn over the visible preview area (preview is centred with fitCenter). */
class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Height / width of the preview in portrait, e.g. 4/3 or 16/9. */
    var aspect: Float = 4f / 3f
        set(value) { field = value; invalidate() }

    var show: Boolean = false
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        if (!show || width == 0 || height == 0) return
        var w = width.toFloat()
        var h = w * aspect
        if (h > height) { h = height.toFloat(); w = h / aspect }
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        for (i in 1..2) {
            canvas.drawLine(left + w * i / 3f, top, left + w * i / 3f, top + h, paint)
            canvas.drawLine(left, top + h * i / 3f, left + w, top + h * i / 3f, paint)
        }
    }
}

/** Shutter button: white disc for photo, red disc for video, red rounded square while recording. */
class ShutterButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var videoMode: Boolean = false
        set(value) { field = value; invalidate() }

    var recording: Boolean = false
        set(value) { field = value; invalidate() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init { isClickable = true }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        ring.strokeWidth = r * 0.1f
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
        fill.color = if (videoMode) 0xFFE53935.toInt() else 0xFFFFFFFF.toInt()
        val pressedScale = if (isPressed) 0.88f else 1f
        if (recording) {
            val s = r * 0.42f * pressedScale
            rect.set(cx - s, cy - s, cx + s, cy + s)
            canvas.drawRoundRect(rect, s * 0.3f, s * 0.3f, fill)
        } else {
            canvas.drawCircle(cx, cy, r * 0.8f * pressedScale, fill)
        }
    }
}
