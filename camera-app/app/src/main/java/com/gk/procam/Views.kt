package com.gk.procam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

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

/**
 * Round zoom dial: the top of a large wheel with tick marks and zoom numbers. Dragging left or
 * right turns the wheel under a fixed yellow marker; the value under the marker is the zoom.
 * Spacing is logarithmic, so the same drag feels like the same zoom change at 1x and at 10x.
 */
class ZoomDial @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onChange: ((Float) -> Unit)? = null

    var tracking: Boolean = false
        private set

    var value: Float = 1f
        set(v) { field = v; invalidate() }

    private var lo = 1f
    private var hi = 1f
    private val d = resources.displayMetrics.density
    private val degPerOctave = 22f
    private val visibleDeg = 46f
    private var startX = 0f
    private var startLog = 0f
    private var lastMajors = -1

    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x80000000.toInt()
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt() }

    fun setRange(min: Float, max: Float) {
        if (min != lo || max != hi) {
            lo = min
            hi = max
            invalidate()
        }
    }

    private fun log2(v: Float): Float = (ln(v.toDouble()) / ln(2.0)).toFloat()

    private fun radius(): Float = width * 0.85f

    override fun onDraw(canvas: Canvas) {
        if (hi <= lo * 1.01f || lo <= 0f || value <= 0f || width == 0) return
        val cx = width / 2f
        val r = radius()
        val top = 16f * d
        val cy = top + r
        val bandWidth = 52f * d
        band.strokeWidth = bandWidth
        canvas.drawCircle(cx, cy, r - bandWidth / 2f + 6f * d, band)

        val cur = log2(value)
        val loL = log2(lo)
        val hiL = log2(hi)

        // Minor ticks every tenth of a doubling.
        tick.strokeWidth = 1.2f * d
        var k = Math.ceil(loL * 10.0 - 0.001).toInt()
        val kEnd = Math.floor(hiL * 10.0 + 0.001).toInt()
        while (k <= kEnd) {
            drawTick(canvas, cx, cy, r, (k / 10f - cur) * degPerOctave, 7f * d, 0.55f)
            k++
        }
        // Numbered ticks.
        tick.strokeWidth = 2.2f * d
        label.textSize = 11f * d
        for (m in MAJORS) {
            if (m < lo * 0.97f || m > hi * 1.03f) continue
            val ang = (log2(m) - cur) * degPerOctave
            if (abs(ang) > visibleDeg) continue
            drawTick(canvas, cx, cy, r, ang, 13f * d, 1f)
            val rad = Math.toRadians(ang.toDouble())
            val lr = r - 27f * d
            label.alpha = (255 * fade(ang)).roundToInt()
            val text = if (m == m.toInt().toFloat()) m.toInt().toString() else m.toString()
            canvas.drawText(
                text, cx + lr * sin(rad).toFloat(), cy - lr * cos(rad).toFloat() + 4f * d, label
            )
        }
        // Fixed marker at the top centre.
        marker.strokeWidth = 3f * d
        canvas.drawLine(cx, top - 10f * d, cx, top + 15f * d, marker)
    }

    private fun fade(ang: Float): Float = (1f - abs(ang) / visibleDeg).coerceIn(0f, 1f)

    private fun drawTick(
        canvas: Canvas, cx: Float, cy: Float, r: Float, ang: Float, len: Float, strength: Float
    ) {
        if (abs(ang) > visibleDeg) return
        val rad = Math.toRadians(ang.toDouble())
        val sn = sin(rad).toFloat()
        val cs = cos(rad).toFloat()
        tick.alpha = (255 * strength * fade(ang)).roundToInt()
        canvas.drawLine(cx + r * sn, cy - r * cs, cx + (r - len) * sn, cy - (r - len) * cs, tick)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (hi <= lo * 1.01f || lo <= 0f || value <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                startX = e.x
                startLog = log2(value)
                lastMajors = majorsBelow(value)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                // Dragging left brings higher numbers under the marker (zoom in).
                val deg = Math.toDegrees(((e.x - startX) / radius()).toDouble()).toFloat()
                val l = (startLog - deg / degPerOctave).coerceIn(log2(lo), log2(hi))
                val z = 2.0.pow(l.toDouble()).toFloat().coerceIn(lo, hi)
                val majors = majorsBelow(z)
                if (majors != lastMajors) {
                    lastMajors = majors
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                value = z
                onChange?.invoke(z)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }

    private fun majorsBelow(z: Float): Int = MAJORS.count { it <= z * 1.001f }

    companion object {
        private val MAJORS = floatArrayOf(0.5f, 1f, 2f, 3f, 5f, 10f, 20f, 30f, 50f, 100f)
    }
}
