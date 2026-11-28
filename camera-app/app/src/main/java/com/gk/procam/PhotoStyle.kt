package com.gk.procam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/** Photographic styles: applied live to the viewfinder and baked into the saved photo. */
enum class PhotoStyle(val label: String) {
    STANDARD("Standard"),
    RICH_CONTRAST("Rich Contrast"),
    VIBRANT("Vibrant"),
    WARM("Warm"),
    COOL("Cool"),
    CINEMATIC("Cinematic"),
    NOIR("Noir"),
    MONO("Mono");

    fun colorMatrix(): ColorMatrix? {
        if (this == STANDARD) return null
        val m = ColorMatrix()
        when (this) {
            RICH_CONTRAST -> { m.postConcat(contrast(1.18f)); m.postConcat(saturation(1.06f)) }
            VIBRANT -> { m.postConcat(saturation(1.35f)); m.postConcat(contrast(1.04f)) }
            WARM -> { m.postConcat(scale(1.07f, 1.0f, 0.90f)); m.postConcat(saturation(1.06f)) }
            COOL -> { m.postConcat(scale(0.93f, 1.0f, 1.08f)) }
            CINEMATIC -> {
                m.postConcat(saturation(0.85f)); m.postConcat(contrast(1.12f))
                m.postConcat(scale(1.02f, 1.0f, 1.05f))
            }
            NOIR -> { m.postConcat(saturation(0f)); m.postConcat(contrast(1.35f)) }
            MONO -> { m.postConcat(saturation(0f)); m.postConcat(contrast(1.05f)) }
            STANDARD -> {}
        }
        return m
    }

    fun apply(src: Bitmap): Bitmap {
        val cm = colorMatrix() ?: return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        if (out !== src) src.recycle()
        return out
    }

    companion object {
        private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }
        private fun scale(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }
        private fun contrast(c: Float): ColorMatrix {
            val t = 128f * (1f - c)
            return ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ))
        }
    }
}
