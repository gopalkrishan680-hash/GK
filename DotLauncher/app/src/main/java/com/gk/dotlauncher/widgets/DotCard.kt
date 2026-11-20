package com.gk.dotlauncher.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import com.gk.dotlauncher.util.Fonts
import com.gk.dotlauncher.util.Palette
import com.gk.dotlauncher.util.Prefs
import com.gk.dotlauncher.util.dp
import com.gk.dotlauncher.util.sp

/**
 * Sab built-in widgets ka base: rounded dark card + dot paints + ek optional "tick" timer
 * jo sirf tab chalta hai jab widget screen par attached ho (battery bachane ke liye).
 */
abstract class DotCard(context: Context, val key: String) : View(context) {
    protected val prefs = Prefs(context)
    /** height = width * aspect */
    var aspect = 1f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.CARD }
    protected val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT }
    protected val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    protected val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DOT_OFF }
    protected val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val pad = dp(18f)

    protected val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.mono(context); textSize = sp(11f); color = Palette.DIM; letterSpacing = 0.14f
    }
    protected val dotoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.doto(context); color = Palette.TEXT
    }

    open val tickMs: Long = 0L
    open fun onTick() {}

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            invalidate()
            if (tickMs > 0) postDelayed(this, tickMs - System.currentTimeMillis() % tickMs)
        }
    }

    protected fun restartTicker() {
        removeCallbacks(ticker)
        if (tickMs > 0 && isAttachedToWindow) post(ticker)
    }

    init {
        isClickable = true
        isLongClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * aspect).toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (tickMs > 0) post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        accentPaint.color = prefs.accent
        val r = dp(28f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bgPaint)
        drawContent(canvas)
    }

    protected fun label(canvas: Canvas, text: String, paint: Paint = labelPaint) {
        canvas.drawText(text.uppercase(), pad, pad + labelPaint.textSize, paint)
    }

    abstract fun drawContent(canvas: Canvas)
}
