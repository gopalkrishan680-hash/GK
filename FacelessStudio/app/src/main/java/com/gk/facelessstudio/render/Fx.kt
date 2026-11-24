package com.gk.facelessstudio.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.gk.facelessstudio.model.Backdrop
import com.gk.facelessstudio.model.CamMove
import com.gk.facelessstudio.model.Deco
import com.gk.facelessstudio.model.Scene
import com.gk.facelessstudio.model.TextFx
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The building blocks every scene is combined from: backdrop × camera × decoration × text entrance.
 * Together with the layout and the transition they make the scene's unique "animation combination".
 */
object Fx {

    // ------------------------------------------------------------------ backdrops
    fun backdrop(k: Kit, c: Canvas, s: Scene, t: Float) {
        when (s.bg) {
            Backdrop.BLOB -> k.bgBlob(c, t, s.variant)
            Backdrop.GRID -> k.bgGrid(c, t)
            Backdrop.GLOW -> k.bgGlow(c, t)
            Backdrop.IMAGE -> if (!k.bgImageBlur(c, s.imageIndex, t, 0.62f)) k.bgBlob(c, t, s.variant)
            Backdrop.PARTICLES -> { c.drawColor(k.theme.bg); k.particles(c, t, s.variant, 90, k.theme.accent); k.particles(c, t * 0.6f, s.variant + 1, 40) }
            Backdrop.SWEEP -> {
                val a = t * 0.35f + s.variant % 7
                val dx = cos(a) * k.w; val dy = sin(a) * k.h
                k.fill.shader = LinearGradient(k.cx - dx, k.cy - dy, k.cx + dx, k.cy + dy,
                    intArrayOf(k.theme.bg, k.mix(k.theme.accent, k.theme.bg, 0.72f), k.theme.bg2, k.mix(k.theme.accent2, k.theme.bg, 0.78f)), null, Shader.TileMode.MIRROR)
                c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
            }
            Backdrop.RAYS -> { c.drawColor(k.theme.bg); k.rays(c, k.cx, k.cy, t, k.theme.accent, 18)
                k.fill.shader = RadialGradient(k.cx, k.cy, max(k.w, k.h) * 0.6f, intArrayOf(0, k.theme.bg), floatArrayOf(0.2f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null }
            Backdrop.DOTS -> {
                c.drawColor(k.theme.bg2)
                val step = 34f * k.u
                var y = step / 2
                while (y < k.h) {
                    var x = step / 2
                    while (x < k.w) {
                        val d = hypot(x - k.cx, y - k.cy) / max(k.w, k.h)
                        val r = (1.5f + 2.5f * (0.5f + 0.5f * sin(d * 18f - t * 3f))) * k.u
                        k.fill.color = k.alpha(k.theme.accent, 0.10f + 0.25f * (1 - d))
                        c.drawCircle(x, y, r, k.fill)
                        x += step
                    }
                    y += step
                }
            }
            Backdrop.WAVES -> {
                c.drawColor(k.theme.bg)
                for (i in 0 until 5) {
                    k.path.reset()
                    val base = k.h * (0.45f + i * 0.12f); val amp = (18f + i * 8f) * k.u
                    k.path.moveTo(0f, k.h.toFloat())
                    var x = 0f
                    while (x <= k.w) { k.path.lineTo(x, base + sin(x / k.w * 6.28f * (1 + i * 0.3f) + t * (0.8f + i * 0.2f)) * amp); x += 12f * k.u }
                    k.path.lineTo(k.w.toFloat(), k.h.toFloat()); k.path.close()
                    k.fill.color = k.alpha(if (i % 2 == 0) k.theme.accent else k.theme.accent2, 0.07f + i * 0.02f)
                    c.drawPath(k.path, k.fill)
                }
            }
            Backdrop.BOKEH -> {
                c.drawColor(k.theme.bg)
                val r = Random(s.variant)
                repeat(14) {
                    val rad = (40f + r.nextFloat() * 120f) * k.u
                    val x = (r.nextFloat() * k.w + t * (8f + r.nextFloat() * 18f) * k.u) % (k.w + rad * 2) - rad
                    val y = r.nextFloat() * k.h + sin(t * 0.5f + it) * 20f * k.u
                    val col = if (it % 3 == 0) k.theme.accent2 else k.theme.accent
                    k.fill.shader = RadialGradient(x, y, rad, intArrayOf(k.alpha(col, 0.28f), k.alpha(col, 0.08f), 0), floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(x, y, rad, k.fill)
                }
                k.fill.shader = null
            }
            Backdrop.STRIPES -> {
                c.drawColor(k.theme.bg2)
                val step = 70f * k.u
                c.save(); c.rotate(-30f, k.cx, k.cy)
                k.fill.color = k.alpha(k.theme.accent, 0.06f)
                var x = -k.w.toFloat() + (t * 30f * k.u) % (step * 2)
                while (x < k.w * 2) { c.drawRect(x, -k.h.toFloat(), x + step, k.h * 2f, k.fill); x += step * 2 }
                c.restore()
            }
            Backdrop.AURORA -> {
                c.drawColor(k.theme.bg)
                for (i in 0 until 4) {
                    val x = k.w * (0.15f + i * 0.25f) + sin(t * 0.4f + i * 1.7f) * k.w * 0.1f
                    val col = if (i % 2 == 0) k.theme.accent else k.theme.accent2
                    k.fill.shader = LinearGradient(x, 0f, x, k.h.toFloat(), intArrayOf(0, k.alpha(col, 0.30f), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                    k.path.reset()
                    k.path.moveTo(x - 120f * k.u, 0f)
                    k.path.cubicTo(x + 80f * k.u * sin(t + i), k.h * 0.3f, x - 80f * k.u, k.h * 0.6f, x + 60f * k.u * cos(t * 0.7f + i), k.h.toFloat())
                    k.path.lineTo(x + 200f * k.u, k.h.toFloat())
                    k.path.cubicTo(x + 60f * k.u, k.h * 0.6f, x + 180f * k.u * sin(t * 0.6f + i), k.h * 0.3f, x + 100f * k.u, 0f)
                    k.path.close()
                    c.drawPath(k.path, k.fill)
                }
                k.fill.shader = null
            }
        }
    }

    // ------------------------------------------------------------------ camera
    fun camera(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val e = k.inOutCubic(k.clamp01(t / max(0.5f, d)))
        when (s.cam) {
            CamMove.STILL -> {}
            // kept gentle so text near the edges never gets cut off
            CamMove.PUSH -> { val z = 1f + 0.04f * e; c.scale(z, z, k.cx, k.cy) }
            CamMove.PULL -> { val z = 1.04f - 0.04f * e; c.scale(z, z, k.cx, k.cy) }
            CamMove.DRIFT_L -> { c.scale(1.025f, 1.025f, k.cx, k.cy); c.translate(k.lerp(0.01f, -0.01f, e) * k.w, 0f) }
            CamMove.DRIFT_R -> { c.scale(1.025f, 1.025f, k.cx, k.cy); c.translate(k.lerp(-0.01f, 0.01f, e) * k.w, 0f) }
            CamMove.RISE -> { c.scale(1.025f, 1.025f, k.cx, k.cy); c.translate(0f, k.lerp(0.01f, -0.01f, e) * k.h) }
            CamMove.TILT -> { c.scale(1.03f, 1.03f, k.cx, k.cy); c.rotate(k.lerp(-0.8f, 0.8f, e), k.cx, k.cy) }
            CamMove.HANDHELD -> {
                c.scale(1.025f, 1.025f, k.cx, k.cy)
                c.translate((sin(t * 1.7f) * 0.5f + sin(t * 3.1f) * 0.3f) * 6f * k.u, (cos(t * 1.3f) * 0.5f + sin(t * 2.6f) * 0.3f) * 6f * k.u)
                c.rotate(sin(t * 0.9f) * 0.35f, k.cx, k.cy)
            }
            CamMove.BREATHE -> { val z = 1.015f + 0.015f * sin(t * 1.4f); c.scale(z, z, k.cx, k.cy) }
        }
    }

    // ------------------------------------------------------------------ decorations
    fun deco(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val r = Random(s.variant + 99)
        when (s.deco) {
            Deco.NONE -> {}
            Deco.LIGHT_SWEEP -> {
                val p = k.seg(t, 0.2f, 1.3f); if (p <= 0f || p >= 1f) return
                val x = k.lerp(-k.w * 0.4f, k.w * 1.4f, p)
                c.save(); c.rotate(20f, k.cx, k.cy)
                k.fill.shader = LinearGradient(x - 90f * k.u, 0f, x + 90f * k.u, 0f, intArrayOf(0, k.alpha(Color.WHITE, 0.20f), 0), null, Shader.TileMode.CLAMP)
                c.drawRect(x - 90f * k.u, -k.h.toFloat(), x + 90f * k.u, k.h * 2f, k.fill); k.fill.shader = null
                c.restore()
            }
            Deco.SPARKLES -> repeat(16) { i ->
                val x = r.nextFloat() * k.w; val y = r.nextFloat() * k.h
                val ph = r.nextFloat() * 6.28f
                val a = (0.5f + 0.5f * sin(t * 3f + ph)).let { it * it }
                val sz = (5f + r.nextFloat() * 9f) * k.u * a
                if (sz < 0.5f) return@repeat
                k.fill.color = k.alpha(if (i % 3 == 0) k.theme.accent else Color.WHITE, 0.85f * a)
                k.path.reset(); k.path.moveTo(x, y - sz); k.path.lineTo(x + sz * 0.25f, y - sz * 0.25f); k.path.lineTo(x + sz, y)
                k.path.lineTo(x + sz * 0.25f, y + sz * 0.25f); k.path.lineTo(x, y + sz); k.path.lineTo(x - sz * 0.25f, y + sz * 0.25f)
                k.path.lineTo(x - sz, y); k.path.lineTo(x - sz * 0.25f, y - sz * 0.25f); k.path.close()
                c.drawPath(k.path, k.fill)
            }
            Deco.CORNERS -> {
                val p = k.outCubic(k.seg(t, 0.1f, 0.6f)); val m = 30f * k.u; val len = 70f * k.u * p
                k.stroke.color = k.theme.accent; k.stroke.strokeWidth = 5f * k.u
                c.drawLine(m, m, m + len, m, k.stroke); c.drawLine(m, m, m, m + len, k.stroke)
                c.drawLine(k.w - m, m, k.w - m - len, m, k.stroke); c.drawLine(k.w - m, m, k.w - m, m + len, k.stroke)
                c.drawLine(m, k.h - m, m + len, k.h - m, k.stroke); c.drawLine(m, k.h - m, m, k.h - m - len, k.stroke)
                c.drawLine(k.w - m, k.h - m, k.w - m - len, k.h - m, k.stroke); c.drawLine(k.w - m, k.h - m, k.w - m, k.h - m - len, k.stroke)
            }
            Deco.CONFETTI -> {
                if (t > 2.6f) return
                repeat(46) { i ->
                    val x0 = r.nextFloat() * k.w; val sp = (160f + r.nextFloat() * 260f) * k.u
                    val y = -20f * k.u + (t - r.nextFloat() * 0.4f) * sp
                    if (y < -20f * k.u || y > k.h) return@repeat
                    val x = x0 + sin(t * 3f + i) * 30f * k.u
                    c.save(); c.rotate(t * 300f * (if (i % 2 == 0) 1 else -1) + i * 40f, x, y)
                    k.fill.color = listOf(k.theme.accent, k.theme.accent2, Color.WHITE, 0xFFFFC23B.toInt())[i % 4]
                    k.fill.alpha = (255 * (1f - k.seg(t, 2.0f, 2.6f))).toInt()
                    c.drawRect(x - 6f * k.u, y - 3f * k.u, x + 6f * k.u, y + 3f * k.u, k.fill)
                    c.restore()
                }
                k.fill.alpha = 255
            }
            Deco.SCANLINES -> {
                k.fill.color = k.alpha(Color.BLACK, 0.18f)
                var y = 0f; while (y < k.h) { c.drawRect(0f, y, k.w.toFloat(), y + 1.5f * k.u, k.fill); y += 5f * k.u }
                val by = (t * 0.35f % 1f) * k.h
                k.fill.shader = LinearGradient(0f, by - 40f * k.u, 0f, by + 40f * k.u, intArrayOf(0, k.alpha(k.theme.accent, 0.12f), 0), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, by - 40f * k.u, k.w.toFloat(), by + 40f * k.u, k.fill); k.fill.shader = null
            }
            Deco.FRAME -> {
                val m = 22f * k.u
                val path = Path().apply { addRoundRect(RectF(m, m, k.w - m, k.h - m), 18f * k.u, 18f * k.u, Path.Direction.CW) }
                val pm = PathMeasure(path, false)
                val seg = Path(); pm.getSegment(0f, pm.length * k.inOutCubic(k.seg(t, 0f, 1.0f)), seg, true)
                k.stroke.color = k.alpha(k.theme.accent, 0.8f); k.stroke.strokeWidth = 3f * k.u
                c.drawPath(seg, k.stroke)
            }
            Deco.SHAPES -> repeat(7) { i ->
                val x = (r.nextFloat() * k.w + t * (10f + i * 4f) * k.u) % k.w
                val y = r.nextFloat() * k.h + sin(t * 0.8f + i) * 24f * k.u
                val sz = (14f + r.nextFloat() * 22f) * k.u
                k.stroke.color = k.alpha(if (i % 2 == 0) k.theme.accent else k.theme.accent2, 0.45f); k.stroke.strokeWidth = 3f * k.u
                c.save(); c.rotate(t * 30f + i * 50f, x, y)
                when (i % 3) {
                    0 -> c.drawCircle(x, y, sz, k.stroke)
                    1 -> c.drawRect(x - sz, y - sz, x + sz, y + sz, k.stroke)
                    else -> { k.path.reset(); k.path.moveTo(x, y - sz); k.path.lineTo(x + sz, y + sz); k.path.lineTo(x - sz, y + sz); k.path.close(); c.drawPath(k.path, k.stroke) }
                }
                c.restore()
            }
            Deco.FLARE -> {
                val p = k.clamp01(t / max(1f, d))
                val sx = k.lerp(k.w * 0.1f, k.w * 0.4f, p); val sy = k.h * 0.18f
                k.fill.shader = RadialGradient(sx, sy, 160f * k.u, intArrayOf(k.alpha(Color.WHITE, 0.35f), 0), null, Shader.TileMode.CLAMP)
                c.drawCircle(sx, sy, 160f * k.u, k.fill); k.fill.shader = null
                for (j in 1..4) {
                    val f = j * 0.45f
                    val x = sx + (k.cx - sx) * f * 2; val y = sy + (k.cy - sy) * f * 2
                    k.fill.color = k.alpha(if (j % 2 == 0) k.theme.accent else k.theme.accent2, 0.10f)
                    c.drawCircle(x, y, (18f + j * 14f) * k.u, k.fill)
                }
            }
            Deco.SPOTLIGHT -> {
                val x = k.cx + sin(t * 0.6f) * k.w * 0.18f; val y = k.cy + cos(t * 0.5f) * k.h * 0.12f
                k.fill.shader = RadialGradient(x, y, max(k.w, k.h) * 0.55f, intArrayOf(0, 0, 0xB0000000.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
            }
            Deco.BRACKETS -> {
                val p = k.outBack(k.seg(t, 0.15f, 0.55f))
                val bw = k.w * 0.44f * p; val bh = k.h * 0.34f
                k.stroke.color = k.alpha(Color.WHITE, 0.55f); k.stroke.strokeWidth = 4f * k.u
                val l = k.cx - bw; val rr = k.cx + bw; val tp = k.cy - bh; val bt = k.cy + bh; val arm = 28f * k.u
                c.drawLine(l, tp, l, bt, k.stroke); c.drawLine(l, tp, l + arm, tp, k.stroke); c.drawLine(l, bt, l + arm, bt, k.stroke)
                c.drawLine(rr, tp, rr, bt, k.stroke); c.drawLine(rr, tp, rr - arm, tp, k.stroke); c.drawLine(rr, bt, rr - arm, bt, k.stroke)
            }
        }
    }

    /** The line itself, wrapped, words arriving one by one with the scene's entrance. */
    fun paragraph(k: Kit, c: Canvas, s: Scene, text: String, x: Float, y: Float, maxW: Float, size: Float, t: Float, d: Float, start: Float = 0.4f) {
        k.setText(k.fonts.heavy, size, Color.WHITE, text)
        val lines = k.wrap(text, maxW).take(5)
        val words = lines.sumOf { it.split(" ").size }
        val span = max(0.6f, d * 0.65f)
        val space = k.text.measureText(" ")
        val em = s.emphasis.map { it.lowercase() }.toSet()
        var wi = 0
        lines.forEachIndexed { li, l ->
            var cx0 = x
            for (wd in l.split(" ")) {
                val st = start + span * wi / max(1, words)
                k.text.color = if (com.gk.facelessstudio.engine.ScriptAnalyzer.cleanWord(wd).lowercase() in em) k.theme.accent else Color.WHITE
                text(k, c, wd, cx0, y + li * size * 1.3f, k.seg(t, st, st + 0.3f), s.fx, wi)
                cx0 += k.text.measureText(wd) + space
                wi++
            }
        }
    }

    // ------------------------------------------------------------------ text entrances
    /**
     * Draws [str] with the scene's entrance animation at progress [p] (0..1). Uses the paint as configured
     * (typeface, size, colour, alignment) on k.text.
     */
    fun text(k: Kit, c: Canvas, str: String, x: Float, y: Float, p: Float, fx: TextFx, seed: Int = 0) {
        if (p <= 0f || str.isEmpty()) return
        val paint = k.text
        val baseAlpha = paint.alpha
        val q = k.clamp01(p)
        fun alpha(a: Float) { paint.alpha = (baseAlpha * k.clamp01(a)).toInt() }
        val wdt = paint.measureText(str)
        val left = when (paint.textAlign) { Paint.Align.CENTER -> x - wdt / 2; Paint.Align.RIGHT -> x - wdt; else -> x }
        val midX = left + wdt / 2
        val size = paint.textSize
        when (fx) {
            TextFx.POP -> { val s = k.lerp(0.4f, 1f, k.outBack(q)); alpha(q * 2); c.save(); c.scale(s, s, midX, y - size * 0.35f); c.drawText(str, x, y, paint); c.restore() }
            TextFx.RISE -> { alpha(q * 1.5f); c.drawText(str, x, y + (1 - k.outCubic(q)) * size * 0.9f, paint) }
            TextFx.SLIDE -> { alpha(q * 1.5f); c.drawText(str, x - (1 - k.outCubic(q)) * size * 1.6f, y, paint) }
            TextFx.TYPE -> { val n = (str.length * q).toInt(); val oldAlign = paint.textAlign; paint.textAlign = Paint.Align.LEFT; c.drawText(str.take(n), left, y, paint); paint.textAlign = oldAlign }
            TextFx.CASCADE, TextFx.DROP -> {
                val oldAlign = paint.textAlign; paint.textAlign = Paint.Align.LEFT
                var cx = left
                str.forEachIndexed { i, ch ->
                    val cp = k.clamp01(q * (1f + str.length * 0.08f) - i * 0.08f)
                    val dy = if (fx == TextFx.DROP) -(1 - k.outBack(cp)) * size * 1.2f else (1 - k.outCubic(cp)) * size * 0.8f
                    alpha(cp * 1.5f)
                    c.drawText(ch.toString(), cx, y + dy, paint)
                    cx += paint.measureText(ch.toString())
                }
                paint.textAlign = oldAlign
            }
            TextFx.BLUR -> {
                val spread = (1 - k.outCubic(q)) * size * 0.35f
                for (j in -2..2) { alpha(q * (if (j == 0) 1f else 0.25f)); c.drawText(str, x + j * spread, y, paint) }
            }
            TextFx.WIPE -> { alpha(1f); c.save(); c.clipRect(left - 4f, y - size * 1.2f, left + wdt * k.outCubic(q) + 4f, y + size * 0.4f); c.drawText(str, x, y, paint); c.restore() }
            TextFx.FLIP -> { alpha(q * 1.5f); c.save(); c.scale(1f, k.outBack(q), midX, y - size * 0.35f); c.drawText(str, x, y, paint); c.restore() }
            TextFx.BOUNCE -> {
                val s = if (q >= 1f) 1f else 1f + sin(q * PI.toFloat() * 3f) * (1 - q) * 0.35f
                alpha(q * 3); c.save(); c.scale(s * k.clamp01(q * 3), s * k.clamp01(q * 3), midX, y - size * 0.35f); c.drawText(str, x, y, paint); c.restore()
            }
            TextFx.GLITCH -> {
                val inten = 1 - k.outCubic(q)
                val r = Random((q * 30).toInt() + seed)
                if (inten > 0.05f) {
                    val col = paint.color
                    paint.color = k.alpha(0xFFFF2050.toInt(), 0.7f * inten + 0.1f); c.drawText(str, x - 8f * k.u * inten + r.nextFloat() * 4, y, paint)
                    paint.color = k.alpha(0xFF20E0FF.toInt(), 0.7f * inten + 0.1f); c.drawText(str, x + 8f * k.u * inten - r.nextFloat() * 4, y, paint)
                    paint.color = col
                }
                alpha(if (inten > 0.3f && r.nextFloat() < 0.3f) 0.3f else 1f)
                c.drawText(str, x + (r.nextFloat() - 0.5f) * 10f * k.u * inten, y, paint)
            }
            TextFx.ZOOM -> { val s = k.lerp(2.4f, 1f, k.outCubic(q)); alpha(q * 1.4f); c.save(); c.scale(s, s, midX, y - size * 0.35f); c.drawText(str, x, y, paint); c.restore() }
        }
        paint.alpha = baseAlpha
    }
}
