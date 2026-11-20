package com.gk.dotlauncher.widgets

import android.content.Context
import android.graphics.Canvas
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.gk.dotlauncher.util.buzz
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Ek tap me torch on/off. CameraManager.setTorchMode ko camera permission nahi chahiye. */
class TorchCard(context: Context) : DotCard(context, "torch") {
    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val camId: String? = runCatching {
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    private var on = false

    private val cb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == camId) { on = enabled; invalidate() }
        }
    }

    init {
        setOnClickListener {
            val id = camId
            if (id == null) {
                Toast.makeText(context, "Is phone me flash nahi mila", Toast.LENGTH_SHORT).show()
            } else {
                runCatching { cm.setTorchMode(id, !on) }
                buzz()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
    }

    override fun onDetachedFromWindow() {
        cm.unregisterTorchCallback(cb)
        super.onDetachedFromWindow()
    }

    override fun drawContent(canvas: Canvas) {
        label(canvas, "Torch")
        val cx = width / 2f
        val cy = height * 0.52f
        val base = min(width, height) * 0.1f
        // 3 rings + center
        for (ring in 1..3) {
            val n = ring * 8
            val rr = base * ring
            for (i in 0 until n) {
                val a = Math.toRadians(i * 360.0 / n)
                val p = if (on) (if (ring == 3) dimPaint else onPaint) else offPaint
                canvas.drawCircle(cx + (rr * cos(a)).toFloat(), cy + (rr * sin(a)).toFloat(), base * 0.16f, p)
            }
        }
        canvas.drawCircle(cx, cy, base * 0.35f, if (on) accentPaint else offPaint)
        dotoPaint.textSize = height * 0.13f
        val t = if (on) "ON" else "OFF"
        canvas.drawText(t, cx - dotoPaint.measureText(t) / 2, height - pad * 0.8f, dotoPaint)
    }
}
