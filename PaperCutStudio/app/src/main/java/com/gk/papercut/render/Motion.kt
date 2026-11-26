package com.gk.papercut.render

import com.gk.papercut.data.EnterAnim
import com.gk.papercut.data.IdleAnim
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Per-element transform for one frame. dx/dy in output pixels, rot in degrees. */
data class Xf(
    var dx: Float = 0f, var dy: Float = 0f,
    var sx: Float = 1f, var sy: Float = 1f,
    var rot: Float = 0f, var alpha: Float = 1f,
    /** 0 = scale around centre, 1 = scale around the bottom edge (grow from the ground). */
    var pivotBottom: Boolean = false,
)

object Ease {
    fun outCubic(t: Float) = 1 - (1 - t).pow(3)
    fun inOut(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
    fun outBack(t: Float, s: Float = 1.70158f): Float { val u = t - 1; return 1 + (s + 1) * u * u * u + s * u * u }
    fun outBounce(t: Float): Float {
        val n = 7.5625f; val d = 2.75f
        return when {
            t < 1 / d -> n * t * t
            t < 2 / d -> { val u = t - 1.5f / d; n * u * u + 0.75f }
            t < 2.5 / d -> { val u = t - 2.25f / d; n * u * u + 0.9375f }
            else -> { val u = t - 2.625f / d; n * u * u + 0.984375f }
        }
    }
}

object Motion {
    const val ENTER_SEC = 0.7f

    /** p = 0..1 progress of the entrance. [w],[h] = output frame size. */
    fun enter(a: EnterAnim, p: Float, w: Float, h: Float, xf: Xf) {
        val q = p.coerceIn(0f, 1f)
        when (a) {
            EnterAnim.POP -> { val s = Ease.outBack(q, 2.2f).coerceAtLeast(0f); xf.sx = s; xf.sy = s; xf.alpha = (q * 4).coerceAtMost(1f) }
            EnterAnim.SLIDE_LEFT -> { xf.dx = -(1 - Ease.outBack(q, 1.2f)) * w * 0.75f; xf.rot = (1 - q) * -6 }
            EnterAnim.SLIDE_RIGHT -> { xf.dx = (1 - Ease.outBack(q, 1.2f)) * w * 0.75f; xf.rot = (1 - q) * 6 }
            EnterAnim.SLIDE_UP -> { xf.dy = (1 - Ease.outBack(q, 1.3f)) * h * 0.85f }
            EnterAnim.DROP -> { xf.dy = -(1 - Ease.outBounce(q)) * h * 0.9f }
            EnterAnim.GROW_UP -> { xf.pivotBottom = true; xf.sy = Ease.outBack(q, 1.8f).coerceAtLeast(0f); xf.sx = 0.7f + 0.3f * Ease.outCubic(q) }
            EnterAnim.SPIN_IN -> { val e = Ease.outCubic(q); xf.rot = (1 - e) * -360; xf.sx = e; xf.sy = e }
            EnterAnim.FLIP -> { xf.sx = cos((1 - Ease.outCubic(q)) * PI.toFloat() / 2); xf.alpha = (q * 3).coerceAtMost(1f) }
            EnterAnim.FADE -> { xf.alpha = Ease.inOut(q) }
            EnterAnim.WOBBLE_IN -> {
                val e = Ease.outBack(q, 1.6f).coerceAtLeast(0f); xf.sx = e; xf.sy = e
                xf.rot = sin(q * 14) * (1 - q) * 16
            }
            EnterAnim.NONE -> {}
        }
    }

    /** t = seconds since the element finished entering; [eh] = element height on screen. */
    fun idle(a: IdleAnim, t: Float, eh: Float, w: Float, xf: Xf) {
        when (a) {
            IdleAnim.NONE -> {}
            IdleAnim.BOB -> xf.dy += sin(t * 3.2f) * eh * 0.015f
            IdleAnim.SWAY -> { xf.pivotBottom = true; xf.rot += sin(t * 1.8f) * 3.5f }
            IdleAnim.PULSE -> { val s = 1 + sin(t * 4.5f) * 0.035f; xf.sx *= s; xf.sy *= s }
            IdleAnim.SHAKE -> { val burst = if ((t % 2.2f) < 0.5f) 1f else 0.15f; xf.dx += sin(t * 45) * 3 * burst; xf.rot += sin(t * 37) * 1.5f * burst }
            IdleAnim.FLOAT -> { xf.dy += sin(t * 1.4f) * eh * 0.035f; xf.rot += sin(t * 1.1f) * 2f }
            IdleAnim.WALK -> {
                xf.pivotBottom = true
                xf.rot += sin(t * 7f) * 4f
                xf.dy -= abs(sin(t * 7f)) * eh * 0.025f
                xf.dx += (t * w * 0.012f).coerceAtMost(w * 0.08f)
            }
            IdleAnim.SPIN -> xf.rot += t * 25f
        }
    }

    /** "Boiling" stop-motion jitter: tiny random wobble that changes every animation step. */
    fun boil(seed: Int, step: Int, xf: Xf) {
        var x = seed * 73856093 xor step * 19349663
        fun r(): Float { x = x * 1103515245 + 12345; return ((x ushr 8) and 0xFFFF) / 65535f - 0.5f }
        xf.rot += r() * 1.2f
        xf.dx += r() * 2.2f
        xf.dy += r() * 2.2f
    }
}
