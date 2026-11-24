package com.gk.facelessstudio.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import com.gk.facelessstudio.model.Scene
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Data / infographic layouts and info overlays, so the story is told by visuals, not only by captions. */
object InfoTemplates {

    private fun bg(k: Kit, c: Canvas, s: Scene, t: Float) {
        Fx.backdrop(k, c, s, t)
    }

    private fun fmt(v: Double): String = when {
        v >= 1_000_000_000 -> String.format("%.1fB", v / 1e9)
        v >= 1_000_000 -> String.format("%.1fM", v / 1e6)
        v >= 10_000 -> String.format("%,d", v.roundToLong())
        v % 1.0 == 0.0 -> v.roundToLong().toString()
        else -> String.format("%.1f", v)
    }

    // ------------------------------------------------------------------ DONUT
    fun donut(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        bg(k, c, s, t)
        val value = s.lines.firstOrNull()?.toFloatOrNull() ?: 50f
        val p = k.outExpo(k.seg(t, 0.15f, min(1.6f, d * 0.6f)))
        val r = (if (k.portrait) k.w * 0.32f else k.h * 0.27f)
        val cx = if (k.portrait) k.cx else k.w * 0.32f
        val cy = if (k.portrait) k.h * 0.38f else k.cy
        val sw = r * 0.28f
        val pop = k.outBack(k.seg(t, 0f, 0.35f))
        c.save(); c.scale(pop, pop, cx, cy)
        k.stroke.strokeCap = Paint.Cap.BUTT
        k.stroke.color = k.alpha(Color.WHITE, 0.12f); k.stroke.strokeWidth = sw
        c.drawCircle(cx, cy, r, k.stroke)
        k.rect.set(cx - r, cy - r, cx + r, cy + r)
        k.stroke.shader = SweepGradient(cx, cy, intArrayOf(k.theme.accent, k.mix(k.theme.accent, Color.WHITE, 0.4f), k.theme.accent), null)
        k.stroke.strokeCap = Paint.Cap.ROUND
        c.drawArc(k.rect, -90f, 360f * value / 100f * p, false, k.stroke)
        k.stroke.shader = null
        val label = "${(value * p).toInt()}%"
        k.setText(k.fonts.headline, r * 0.55f, Color.WHITE, label); k.text.textAlign = Paint.Align.CENTER
        k.glow(k.theme.accent, 16f * k.u)
        c.drawText(label, cx, cy + r * 0.2f, k.text)
        c.restore()
        if (s.title.isNotBlank()) {
            val x = if (k.portrait) 40f * k.u else k.w * 0.56f
            val y = if (k.portrait) k.h * 0.66f else k.cy - 40f * k.u
            val bottom = k.banner(c, s.title, x, y, if (k.portrait) k.w - 80f * k.u else k.w * 0.4f, 34f * k.u, k.seg(t, 0.4f, 0.8f))
            k.setText(k.fonts.body, 22f * k.u, k.alpha(Color.WHITE, 0.8f))
            k.text.alpha = (220 * k.seg(t, 0.8f, 1.2f)).toInt()
            c.drawText("${value.toInt()} out of 100", x, bottom + 40f * k.u, k.text)
        }
    }

    // ------------------------------------------------------------------ BAR CHART
    fun barChart(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        bg(k, c, s, t)
        val items = s.lines.mapNotNull { l -> l.split("|").takeIf { it.size == 2 }?.let { Pair(it[0], it[1].toDoubleOrNull() ?: 0.0) } }.take(5)
        if (items.isEmpty()) return
        val maxV = items.maxOf { it.second }.coerceAtLeast(1e-9)
        val top = k.banner(c, s.title, 44f * k.u, 44f * k.u, k.w - 88f * k.u, 30f * k.u, k.seg(t, 0f, 0.35f)) + 30f * k.u
        val left = 60f * k.u; val right = k.w - 60f * k.u
        val bottom = k.h - (if (k.portrait) 220f else 70f) * k.u
        val n = items.size
        val slot = (right - left) / n
        val bw = min(slot * 0.55f, 140f * k.u)
        val chartH = bottom - top - 60f * k.u
        k.stroke.color = k.alpha(Color.WHITE, 0.25f); k.stroke.strokeWidth = 2f * k.u
        c.drawLine(left, bottom, right, bottom, k.stroke)
        val colors = listOf(k.theme.accent, k.theme.accent2, k.mix(k.theme.accent, Color.WHITE, 0.4f), k.mix(k.theme.accent2, Color.WHITE, 0.4f), Color.WHITE)
        items.forEachIndexed { i, (label, v) ->
            val st = 0.3f + i * 0.25f
            val p = k.outCubic(k.seg(t, st, st + 0.8f))
            val x = left + slot * i + slot / 2
            val bh = (chartH * (v / maxV)).toFloat() * p
            k.rect.set(x - bw / 2, bottom - bh, x + bw / 2, bottom)
            k.fill.color = colors[i % colors.size]
            c.drawRoundRect(k.rect, 10f * k.u, 10f * k.u, k.fill)
            c.drawRect(x - bw / 2, bottom - min(bh, 12f * k.u), x + bw / 2, bottom, k.fill)
            k.setText(k.fonts.headline, min(34f * k.u, bw * 0.4f), Color.WHITE); k.text.textAlign = Paint.Align.CENTER
            k.text.alpha = (255 * p).toInt()
            c.drawText(fmt(v * p), x, bottom - bh - 12f * k.u, k.text)
            val ls = k.fitSize(label, k.fonts.bold, slot - 8f * k.u, 20f * k.u, 10f * k.u)
            k.setText(k.fonts.bold, ls, k.alpha(Color.WHITE, 0.85f), label); k.text.textAlign = Paint.Align.CENTER
            c.drawText(label, x, bottom + ls * 1.5f, k.text)
        }
    }

    // ------------------------------------------------------------------ TIMELINE
    fun timeline(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        bg(k, c, s, t)
        val items = s.lines.mapNotNull { l -> l.split("|").takeIf { it.isNotEmpty() }?.let { Pair(it[0], it.getOrElse(1) { "" }) } }.take(5)
        if (items.isEmpty()) return
        if (s.title.isNotBlank()) k.banner(c, s.title, 44f * k.u, 44f * k.u, k.w * 0.7f, 30f * k.u, k.seg(t, 0f, 0.35f))
        val n = items.size
        val span = max(0.6f, d * 0.75f)
        val lp = k.inOutCubic(k.seg(t, 0.1f, span))
        k.stroke.strokeWidth = 5f * k.u
        if (!k.portrait) {
            val y = k.cy + 10f * k.u; val x0 = 80f * k.u; val x1 = k.w - 80f * k.u
            k.stroke.color = k.alpha(Color.WHITE, 0.2f); c.drawLine(x0, y, x1, y, k.stroke)
            k.stroke.color = k.theme.accent; c.drawLine(x0, y, k.lerp(x0, x1, lp), y, k.stroke)
            items.forEachIndexed { i, (year, label) ->
                val x = if (n == 1) k.cx else x0 + (x1 - x0) * (i + 0.5f) / n
                val reached = lp * (x1 - x0) + x0 >= x
                val pp = if (reached) 1f else 0f
                val dotP = k.outBack(k.seg(t, 0.1f + span * ((i + 0.5f) / n), 0.1f + span * ((i + 0.5f) / n) + 0.3f))
                k.fill.color = if (reached) k.theme.accent else k.alpha(Color.WHITE, 0.3f)
                c.drawCircle(x, y, 14f * k.u * max(0.6f, dotP), k.fill)
                val up = i % 2 == 0
                k.setText(k.fonts.headline, 44f * k.u, if (reached) Color.WHITE else k.alpha(Color.WHITE, 0.3f), year); k.text.textAlign = Paint.Align.CENTER
                k.text.alpha = (255 * (0.3f + 0.7f * dotP)).toInt()
                c.drawText(year, x, if (up) y - 40f * k.u else y + 76f * k.u, k.text)
                if (label.isNotBlank() && pp > 0) {
                    val ls = k.fitSize(label, k.fonts.body, (x1 - x0) / n - 10f * k.u, 20f * k.u, 11f * k.u)
                    k.setText(k.fonts.body, ls, k.alpha(Color.WHITE, 0.85f), label); k.text.textAlign = Paint.Align.CENTER
                    k.text.alpha = (220 * dotP).toInt()
                    c.drawText(label, x, if (up) y - 92f * k.u else y + 108f * k.u, k.text)
                }
            }
        } else {
            val x = 90f * k.u; val y0 = k.h * 0.2f; val y1 = k.h * 0.78f
            k.stroke.color = k.alpha(Color.WHITE, 0.2f); c.drawLine(x, y0, x, y1, k.stroke)
            k.stroke.color = k.theme.accent; c.drawLine(x, y0, x, k.lerp(y0, y1, lp), k.stroke)
            items.forEachIndexed { i, (year, label) ->
                val y = y0 + (y1 - y0) * (i + 0.5f) / n
                val dotP = k.outBack(k.seg(t, 0.1f + span * ((i + 0.5f) / n), 0.1f + span * ((i + 0.5f) / n) + 0.3f))
                k.fill.color = if (dotP > 0) k.theme.accent else k.alpha(Color.WHITE, 0.3f)
                c.drawCircle(x, y, 14f * k.u * max(0.6f, dotP), k.fill)
                k.setText(k.fonts.headline, 52f * k.u, Color.WHITE, year)
                k.text.alpha = (255 * (0.3f + 0.7f * dotP)).toInt()
                c.drawText(year, x + 40f * k.u, y + 10f * k.u, k.text)
                if (label.isNotBlank()) {
                    k.setText(k.fonts.body, 26f * k.u, k.alpha(Color.WHITE, 0.85f), label); k.text.alpha = (220 * dotP).toInt()
                    c.drawText(label, x + 40f * k.u, y + 48f * k.u, k.text)
                }
            }
        }
    }

    // ------------------------------------------------------------------ KEY POINTS
    /** Image card on one side, headline + animated bullet chips on the other: an infographic for ordinary lines. */
    fun keyPoints(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val bmp = k.images.get(s.imageIndex)
        Fx.backdrop(k, c, s, t)
        val pad = 44f * k.u
        var textX = pad; var textW = k.w - pad * 2; var textTop = pad + 20f * k.u
        if (bmp != null) {
            val pin = k.outCubic(k.seg(t, 0f, 0.45f))
            if (k.portrait) {
                k.rect.set(pad, pad + 30f * k.u, k.w - pad, k.h * 0.45f)
                textTop = k.h * 0.5f
            } else {
                k.rect.set(k.w * 0.52f, pad + 20f * k.u, k.w - pad, k.h - pad - 20f * k.u)
                textW = k.w * 0.44f
            }
            c.save(); c.translate(0f, (1f - pin) * 60f * k.u)
            k.fill.color = 0x66000000
            k.rect2.set(k.rect.left + 10f * k.u, k.rect.top + 12f * k.u, k.rect.right + 10f * k.u, k.rect.bottom + 12f * k.u)
            c.drawRoundRect(k.rect2, 22f * k.u, 22f * k.u, k.fill)
            k.coverRound(c, bmp, RectF(k.rect), 22f * k.u, k.lerp(1.05f, 1.18f, k.clamp01(t / d)))
            k.stroke.color = k.theme.accent; k.stroke.strokeWidth = 4f * k.u
            c.drawRoundRect(k.rect, 22f * k.u, 22f * k.u, k.stroke)
            c.restore()
        }
        val bottom = k.banner(c, s.title, textX, textTop + 30f * k.u, textW, 34f * k.u, k.seg(t, 0.1f, 0.5f))
        val pts = s.lines.take(3)
        if (pts.isEmpty()) { Fx.paragraph(k, c, s, s.text, textX, bottom + 60f * k.u, textW, 32f * k.u, t, d); return }
        val span = max(0.9f, d * 0.7f)
        pts.forEachIndexed { i, pt ->
            val st = 0.45f + (span - 0.45f) * i / max(1, pts.size)
            val p = k.outBack(k.seg(t, st, st + 0.35f))
            if (p <= 0f) return@forEachIndexed
            val y = bottom + 50f * k.u + i * 74f * k.u
            c.save(); c.translate((1f - p) * -80f * k.u, 0f)
            k.fill.color = k.alpha(Color.WHITE, 0.08f)
            val size = k.fitSize(pt, k.fonts.heavy, textW - 90f * k.u, 30f * k.u)
            k.setText(k.fonts.heavy, size, Color.WHITE, pt)
            val tw = k.text.measureText(pt)
            k.rect.set(textX, y - 30f * k.u, textX + tw + 86f * k.u, y + 26f * k.u)
            c.drawRoundRect(k.rect, 28f * k.u, 28f * k.u, k.fill)
            k.fill.color = if (i % 2 == 0) k.theme.accent else k.theme.accent2
            c.drawCircle(textX + 28f * k.u, y - 2f * k.u, 16f * k.u, k.fill)
            k.check(c, textX + 19f * k.u, y - 2f * k.u, 18f * k.u, k.seg(t, st + 0.2f, st + 0.5f), 0xFF0A0A0A.toInt())
            k.text.alpha = (255 * k.clamp01(p)).toInt()
            c.drawText(pt, textX + 62f * k.u, y + size * 0.35f, k.text)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ OVERLAYS FOR IMAGE SCENES
    fun infoLayer(k: Kit, c: Canvas, s: Scene, t: Float, d: Float, kind: Int) {
        val title = s.title.ifBlank { s.emphasis.joinToString(" ").uppercase() }
        if (title.isBlank()) return
        when (kind) {
            0 -> factCard(k, c, s, title, t)
            1 -> k.banner(c, title, 40f * k.u, 40f * k.u, k.w * 0.62f, 32f * k.u, k.seg(t, 0.15f, 0.55f))
            2 -> annotation(k, c, s, title, t)
            else -> sideTag(k, c, title, t)
        }
    }

    /** Lower-third card: accent bar, headline and keyword chips. */
    private fun factCard(k: Kit, c: Canvas, s: Scene, title: String, t: Float) {
        val p = k.outCubic(k.seg(t, 0.2f, 0.6f))
        if (p <= 0f) return
        val x = 40f * k.u
        val maxW = if (k.portrait) k.w - 80f * k.u else k.w * 0.55f
        val ts = k.fitSize(title, k.fonts.headline, maxW - 50f * k.u, 38f * k.u)
        val chips = s.emphasis.filter { !title.contains(it, true) }.take(2)
        val h = ts * 1.5f + (if (chips.isNotEmpty()) 52f * k.u else 0f) + 20f * k.u
        val y = (if (k.portrait) k.h * 0.6f else k.h * 0.62f)
        c.save(); c.translate((1f - p) * -(maxW + x), 0f)
        k.fill.color = 0xD90A0D12.toInt()
        k.rect.set(x, y, x + maxW, y + h); c.drawRoundRect(k.rect, 14f * k.u, 14f * k.u, k.fill)
        k.fill.color = k.theme.accent
        c.drawRect(x, y + 12f * k.u, x + 8f * k.u, y + h - 12f * k.u, k.fill)
        k.setText(k.fonts.headline, ts, Color.WHITE, title)
        c.drawText(title, x + 28f * k.u, y + ts * 1.25f, k.text)
        var cx = x + 28f * k.u
        chips.forEachIndexed { i, ch ->
            val cp = k.outBack(k.seg(t, 0.6f + i * 0.2f, 0.9f + i * 0.2f))
            if (cp <= 0f) return@forEachIndexed
            k.setText(k.fonts.bold, 18f * k.u, 0xFF0A0A0A.toInt(), ch)
            val cw = k.text.measureText(ch) + 28f * k.u
            k.fill.color = if (i == 0) k.theme.accent else k.theme.accent2
            k.rect.set(cx, y + ts * 1.55f, cx + cw * cp, y + ts * 1.55f + 36f * k.u)
            c.drawRoundRect(k.rect, 18f * k.u, 18f * k.u, k.fill)
            if (cp > 0.9f) c.drawText(ch, cx + 14f * k.u, y + ts * 1.55f + 25f * k.u, k.text)
            cx += cw + 12f * k.u
        }
        c.restore()
    }

    /** Animated circle marker + pointer line + label, like an explainer annotation. */
    private fun annotation(k: Kit, c: Canvas, s: Scene, title: String, t: Float) {
        val r = k.rnd(s, 5)
        val ax = k.w * (0.35f + r.nextFloat() * 0.3f)
        val ay = k.h * (0.32f + r.nextFloat() * 0.25f)
        val rad = 70f * k.u
        val p = k.seg(t, 0.25f, 0.8f)
        if (p <= 0f) return
        k.stroke.color = k.theme.accent2; k.stroke.strokeWidth = 6f * k.u
        k.rect.set(ax - rad, ay - rad * 0.8f, ax + rad, ay + rad * 0.8f)
        c.drawArc(k.rect, -120f, 360f * k.outCubic(p), false, k.stroke)
        val lp = k.seg(t, 0.7f, 1.0f)
        val right = ax < k.cx
        val lx = if (right) k.w * 0.7f else k.w * 0.3f
        val ly = ay + (if (ay < k.cy) 1f else -1f) * k.h * 0.25f
        val sx = ax + (if (right) rad * 0.8f else -rad * 0.8f)
        if (lp > 0f) {
            k.stroke.strokeWidth = 4f * k.u
            c.drawLine(sx, ay, k.lerp(sx, lx, lp), k.lerp(ay, ly, lp), k.stroke)
        }
        val tp = k.outBack(k.seg(t, 0.95f, 1.3f))
        if (tp > 0f) {
            val size = k.fitSize(title, k.fonts.headline, k.w * 0.4f, 34f * k.u)
            k.setText(k.fonts.headline, size, Color.WHITE, title)
            val tw = k.text.measureText(title)
            val bx = (if (right) lx else lx - tw - 30f * k.u).coerceIn(20f * k.u, max(20f * k.u, k.w - tw - 50f * k.u))
            c.save(); c.scale(tp, tp, lx, ly)
            k.fill.color = k.theme.accent2
            k.rect.set(bx, ly - size, bx + tw + 30f * k.u, ly + size * 0.45f)
            c.drawRoundRect(k.rect, 8f * k.u, 8f * k.u, k.fill)
            c.drawText(title, bx + 15f * k.u, ly + size * 0.05f, k.text)
            c.restore()
        }
    }

    /** Vertical stack of the headline words on the side, words slide in one by one. */
    private fun sideTag(k: Kit, c: Canvas, title: String, t: Float) {
        val words = title.split(" ").filter { it.isNotBlank() }.take(4)
        val size = if (k.portrait) 56f * k.u else 52f * k.u
        k.fill.shader = android.graphics.LinearGradient(0f, 0f, k.w * 0.5f, 0f, 0xCC000000.toInt(), 0, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, k.w * 0.5f, k.h.toFloat(), k.fill); k.fill.shader = null
        val y0 = k.cy - (words.size - 1) * size * 0.55f
        words.forEachIndexed { i, w ->
            val p = k.outCubic(k.seg(t, 0.2f + i * 0.15f, 0.5f + i * 0.15f))
            k.setText(k.fonts.headline, size, if (i == words.lastIndex) k.theme.accent else Color.WHITE, w)
            k.text.alpha = (255 * p).toInt(); k.shadow()
            c.drawText(w, 44f * k.u - (1f - p) * 80f * k.u, y0 + i * size * 1.1f + size * 0.35f, k.text)
        }
        k.text.clearShadowLayer()
    }

}
