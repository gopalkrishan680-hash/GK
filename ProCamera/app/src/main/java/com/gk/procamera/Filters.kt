package com.gk.procamera

import android.graphics.ColorMatrix

/** A colour look. [matrix] == null means "Original" (no processing). */
data class PhotoFilter(val name: String, val matrix: ColorMatrix?)

object Filters {

    private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

    /** Contrast around mid-grey plus optional brightness offset (0..255 scale). */
    private fun contrast(c: Float, brightness: Float = 0f): ColorMatrix {
        val t = (1f - c) * 128f + brightness
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }

    private fun tint(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

    /** Applies the matrices in the given order. */
    private fun chain(vararg m: ColorMatrix) = ColorMatrix().apply { m.forEach { postConcat(it) } }

    private val warm get() = tint(1.08f, 1.0f, 0.86f)
    private val cool get() = tint(0.90f, 0.99f, 1.10f)

    val all: List<PhotoFilter> = listOf(
        PhotoFilter("Original", null),
        PhotoFilter("Vivid", chain(saturation(1.35f), contrast(1.10f))),
        PhotoFilter("Vivid Warm", chain(saturation(1.30f), contrast(1.10f), warm)),
        PhotoFilter("Vivid Cool", chain(saturation(1.30f), contrast(1.10f), cool)),
        PhotoFilter("Dramatic", chain(saturation(0.85f), contrast(1.35f, -12f))),
        PhotoFilter("Drama Warm", chain(saturation(0.85f), contrast(1.35f, -12f), warm)),
        PhotoFilter("Drama Cool", chain(saturation(0.85f), contrast(1.35f, -12f), cool)),
        PhotoFilter("Cinematic", chain(saturation(1.10f), contrast(1.18f, -6f), tint(1.06f, 0.98f, 1.04f))),
        PhotoFilter("Film", chain(saturation(0.88f), contrast(0.86f, 14f), tint(1.05f, 1.0f, 0.93f))),
        PhotoFilter("Mono", saturation(0f)),
        PhotoFilter("Silvertone", chain(saturation(0f), contrast(0.92f, 12f), tint(1.0f, 1.0f, 1.04f))),
        PhotoFilter("Noir", chain(saturation(0f), contrast(1.6f, -8f))),
    )
}
