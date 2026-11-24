package com.gk.facelessstudio.render

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import com.gk.facelessstudio.engine.ScriptAnalyzer
import com.gk.facelessstudio.model.Scene
import com.gk.facelessstudio.model.SceneType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Twelve more layouts so a video can run a long time without repeating itself. */
object MoreTemplates {

    fun draw(k: Kit, c: Canvas, s: Scene, t: Float, d: Float): Boolean {
        when (s.type) {
            SceneType.TEXT_MASK -> textMask(k, c, s, t, d)
            SceneType.FILMSTRIP -> filmstrip(k, c, s, t, d)
            SceneType.MAGNIFY -> magnify(k, c, s, t, d)
            SceneType.NEWS_FLASH -> news(k, c, s, t, d)
            SceneType.CHAT_CARD -> chat(k, c, s, t, d)
            SceneType.SEARCH_BAR -> search(k, c, s, t, d)
            SceneType.CIRCLE_REVEAL -> circleReveal(k, c, s, t, d)
            SceneType.SPLIT_DIAGONAL -> diagonal(k, c, s, t, d)
            SceneType.WORD_CLOUD -> cloud(k, c, s, t, d)
            SceneType.GAUGE -> gauge(k, c, s, t, d)
            SceneType.STEPS -> steps(k, c, s, t, d)
            SceneType.STICKY_NOTES -> sticky(k, c, s, t, d)
            else -> return false
        }
        return true
    }

    private fun title(s: Scene, words: Int) = s.title.ifBlank { ScriptAnalyzer.phrase(s.text, words).uppercase() }

    // ------------------------------------------------------------------ IMAGE INSIDE TEXT
    private fun textMask(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val bmp = k.images.get(s.imageIndex)
        Fx.backdrop(k, c, s, t)
        val word = s.title.ifBlank { s.emphasis.firstOrNull()?.uppercase() ?: ScriptAnalyzer.phrase(s.text, 2).uppercase() }
        val size = k.fitSize(word, k.fonts.condensed, k.w * 0.9f, if (k.portrait) 230f * k.u else 300f * k.u)
        k.setText(k.fonts.condensed, size, Color.WHITE, word); k.text.textAlign = Paint.Align.CENTER
        val p = k.outBack(k.seg(t, 0f, 0.5f))
        c.save(); c.scale(k.lerp(0.6f, 1f, p), k.lerp(0.6f, 1f, p), k.cx, k.cy)
        k.text.setShadowLayer(30f * k.u, 0f, 10f * k.u, 0xCC000000.toInt())
        k.text.color = Color.BLACK; c.drawText(word, k.cx, k.cy + size * 0.36f, k.text)
        k.text.clearShadowLayer()
        if (bmp != null) {
            val m = Matrix()
            val sc = max(k.w.toFloat() / bmp.width, k.h.toFloat() / bmp.height) * k.lerp(1.15f, 1.3f, k.clamp01(t / d))
            m.postScale(sc, sc); m.postTranslate(k.cx - bmp.width * sc / 2 + sin(t * 0.4f) * 30f * k.u, k.cy - bmp.height * sc / 2)
            k.text.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            k.text.color = Color.WHITE
        } else k.text.color = k.theme.accent
        c.drawText(word, k.cx, k.cy + size * 0.36f, k.text)
        k.text.shader = null
        k.text.style = Paint.Style.STROKE; k.text.strokeWidth = 3f * k.u; k.text.color = k.theme.accent
        c.drawText(word, k.cx, k.cy + size * 0.36f, k.text)
        k.text.style = Paint.Style.FILL
        c.restore()
        if (s.lines.isNotEmpty()) {
            val sub = s.lines.first()
            k.setText(k.fonts.bold, 30f * k.u, Color.WHITE, sub); k.text.textAlign = Paint.Align.CENTER
            Fx.text(k, c, sub, k.cx, k.cy + size * 0.36f + 70f * k.u, k.seg(t, 0.5f, 1.1f), s.fx)
        }
    }

    // ------------------------------------------------------------------ FILM STRIP
    private fun filmstrip(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        Fx.backdrop(k, c, s, t)
        val n = max(1, k.images.count)
        val fh = if (k.portrait) k.h * 0.3f else k.h * 0.5f
        val fw = fh * 16f / 9f
        val gap = 26f * k.u
        val stripH = fh + 70f * k.u
        val top = k.cy - stripH / 2
        val slide = k.lerp(0f, 1f, k.inOutCubic(k.clamp01(t / d)))
        k.fill.color = 0xFF111111.toInt()
        c.drawRect(0f, top, k.w.toFloat(), top + stripH, k.fill)
        val off = -slide * (fw + gap)
        for (j in -2..3) {
            val idx = ((s.imageIndex + j) % n + n) % n
            val bmp = k.images.get(idx) ?: continue
            val x = k.cx - fw / 2 + j * (fw + gap) + off
            if (x > k.w || x + fw < 0) continue
            k.rect.set(x, top + 35f * k.u, x + fw, top + 35f * k.u + fh)
            k.cover(c, bmp, k.rect, 1.05f)
            val focus = 1f - min(1f, kotlin.math.abs(x + fw / 2 - k.cx) / (fw + gap))
            k.fill.color = k.alpha(Color.BLACK, 0.55f * (1 - focus)); c.drawRect(k.rect, k.fill)
        }
        // sprocket holes
        k.fill.color = 0xFFEDE6D6.toInt()
        val hs = 14f * k.u
        var x = -((slide * (fw + gap)) % (hs * 3))
        while (x < k.w) {
            c.drawRoundRect(RectF(x, top + 10f * k.u, x + hs, top + 10f * k.u + hs), 3f * k.u, 3f * k.u, k.fill)
            c.drawRoundRect(RectF(x, top + stripH - 10f * k.u - hs, x + hs, top + stripH - 10f * k.u), 3f * k.u, 3f * k.u, k.fill)
            x += hs * 3
        }
        val ttl = title(s, 5)
        if (ttl.isNotBlank()) k.banner(c, ttl, 40f * k.u, max(30f * k.u, top - 90f * k.u), k.w * 0.7f, 32f * k.u, k.seg(t, 0.2f, 0.6f))
    }

    // ------------------------------------------------------------------ MAGNIFIER
    private fun magnify(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val bmp = k.images.get(s.imageIndex) ?: return
        k.rect.set(0f, 0f, k.w.toFloat(), k.h.toFloat())
        k.cover(c, bmp, k.rect, 1.08f)
        c.drawColor(0x66000000)
        val r = k.rnd(s, 21)
        val e = k.inOutCubic(k.seg(t, 0.2f, max(1f, d * 0.7f)))
        val x0 = k.w * (0.25f + r.nextFloat() * 0.15f); val y0 = k.h * (0.3f + r.nextFloat() * 0.2f)
        val x1 = k.w * (0.55f + r.nextFloat() * 0.2f); val y1 = k.h * (0.35f + r.nextFloat() * 0.25f)
        val lx = k.lerp(x0, x1, e); val ly = k.lerp(y0, y1, e)
        val rad = min(k.w, k.h) * 0.22f
        // lens: same picture, 2.2x zoom around the lens centre
        c.save()
        val clip = Path().apply { addCircle(lx, ly, rad, Path.Direction.CW) }
        c.clipPath(clip)
        val sc = max(k.w.toFloat() / bmp.width, k.h.toFloat() / bmp.height) * 1.08f
        val m = Matrix(); m.postScale(sc * 2.2f, sc * 2.2f)
        val bw = bmp.width * sc; val bh = bmp.height * sc
        val ox = (k.w - bw) / 2; val oy = (k.h - bh) / 2
        m.postTranslate(lx - (lx - ox) * 2.2f, ly - (ly - oy) * 2.2f)
        c.drawBitmap(bmp, m, k.bmpPaint)
        c.restore()
        k.stroke.color = 0xFF2A2A2A.toInt(); k.stroke.strokeWidth = 16f * k.u; c.drawCircle(lx, ly, rad, k.stroke)
        k.stroke.color = k.theme.accent; k.stroke.strokeWidth = 4f * k.u; c.drawCircle(lx, ly, rad + 8f * k.u, k.stroke)
        k.stroke.color = 0xFF2A2A2A.toInt(); k.stroke.strokeWidth = 26f * k.u
        c.drawLine(lx + rad * 0.72f, ly + rad * 0.72f, lx + rad * 1.35f, ly + rad * 1.35f, k.stroke)
        val label = title(s, 4)
        k.banner(c, label, 40f * k.u, k.h - 120f * k.u, k.w * 0.6f, 32f * k.u, k.seg(t, 0.5f, 0.9f))
    }

    // ------------------------------------------------------------------ BREAKING NEWS
    private fun news(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val bmp = k.images.get(s.imageIndex)
        k.rect.set(0f, 0f, k.w.toFloat(), k.h.toFloat())
        if (bmp != null) k.cover(c, bmp, k.rect, k.lerp(1.05f, 1.15f, k.clamp01(t / d))) else Fx.backdrop(k, c, s, t)
        val barY = k.h * (if (k.portrait) 0.66f else 0.7f)
        val p = k.outCubic(k.seg(t, 0f, 0.4f))
        // red label
        k.setText(k.fonts.headline, 28f * k.u, Color.WHITE, "BREAKING")
        val lw = k.text.measureText("BREAKING") + 40f * k.u
        c.save(); c.translate((1 - p) * -k.w, 0f)
        k.fill.color = k.theme.accent2; c.drawRect(24f * k.u, barY, lw + 54f * k.u, barY + 52f * k.u, k.fill)
        val blink = if ((t * 2).toInt() % 2 == 0) 1f else 0.5f
        k.fill.color = k.alpha(Color.WHITE, blink); c.drawCircle(48f * k.u, barY + 26f * k.u, 7f * k.u, k.fill)
        c.drawText("BREAKING", 64f * k.u, barY + 36f * k.u, k.text)
        // headline bar
        val head = title(s, 7)
        k.fill.color = 0xF2FFFFFF.toInt(); c.drawRect(0f, barY + 52f * k.u, k.w.toFloat(), barY + 122f * k.u, k.fill)
        val hs = k.fitSize(head, k.fonts.heavy, k.w - 60f * k.u, 38f * k.u)
        k.setText(k.fonts.heavy, hs, 0xFF111111.toInt(), head)
        Fx.text(k, c, head, 30f * k.u, barY + 100f * k.u, k.seg(t, 0.3f, 0.9f), s.fx)
        // ticker
        k.fill.color = 0xFF111111.toInt(); c.drawRect(0f, barY + 122f * k.u, k.w.toFloat(), barY + 162f * k.u, k.fill)
        k.setText(k.fonts.body, 22f * k.u, Color.WHITE, s.text)
        val tick = "  ●  ${s.text}  ●  ${s.text}"
        val tw = k.text.measureText(tick)
        val tx = k.w - (t * 140f * k.u) % (tw + k.w)
        c.drawText(tick, tx, barY + 150f * k.u, k.text)
        c.restore()
        k.setText(k.fonts.bold, 20f * k.u, Color.WHITE, "LIVE")
        k.fill.color = k.theme.accent2; c.drawRoundRect(RectF(k.w - 110f * k.u, 30f * k.u, k.w - 30f * k.u, 66f * k.u), 6f * k.u, 6f * k.u, k.fill)
        k.text.textAlign = Paint.Align.CENTER; c.drawText("LIVE", k.w - 70f * k.u, 56f * k.u, k.text)
    }

    // ------------------------------------------------------------------ CHAT MESSAGE
    private fun chat(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        Fx.backdrop(k, c, s, t)
        val name = s.title.ifBlank { "Did you know?" }
        val msg = s.lines.firstOrNull() ?: s.text
        val cw = if (k.portrait) k.w * 0.86f else k.w * 0.56f
        val left = k.cx - cw / 2
        val p = k.outBack(k.seg(t, 0f, 0.4f))
        k.setText(k.fonts.body, (if (k.portrait) 34f else 30f) * k.u, 0xFF111111.toInt(), msg)
        val lines = k.wrap(msg, cw - 60f * k.u).take(6)
        val lh = k.text.textSize * 1.35f
        val ch = 110f * k.u + lines.size * lh + 30f * k.u
        val top = k.cy - ch / 2
        c.save(); c.scale(p, p, k.cx, k.cy)
        k.fill.color = 0x55000000; c.drawRoundRect(RectF(left + 8f * k.u, top + 12f * k.u, left + cw + 8f * k.u, top + ch + 12f * k.u), 26f * k.u, 26f * k.u, k.fill)
        k.fill.color = Color.WHITE; c.drawRoundRect(RectF(left, top, left + cw, top + ch), 26f * k.u, 26f * k.u, k.fill)
        // avatar + name
        k.fill.shader = SweepGradient(left + 60f * k.u, top + 56f * k.u, intArrayOf(k.theme.accent, k.theme.accent2, k.theme.accent), null)
        c.drawCircle(left + 60f * k.u, top + 56f * k.u, 30f * k.u, k.fill); k.fill.shader = null
        k.setText(k.fonts.headline, 30f * k.u, Color.WHITE, name.take(1)); k.text.textAlign = Paint.Align.CENTER
        c.drawText(name.take(1).uppercase(), left + 60f * k.u, top + 67f * k.u, k.text)
        k.setText(k.fonts.heavy, 26f * k.u, 0xFF111111.toInt(), name); c.drawText(name, left + 104f * k.u, top + 52f * k.u, k.text)
        k.setText(k.fonts.body, 18f * k.u, 0xFF7A7A7A.toInt()); c.drawText("just now", left + 104f * k.u, top + 78f * k.u, k.text)
        // typing dots, then the message types out
        val typeStart = 0.9f
        if (t < typeStart) {
            for (j in 0..2) {
                k.fill.color = k.alpha(0xFF999999.toInt(), 0.4f + 0.6f * (0.5f + 0.5f * sin(t * 10f - j)))
                c.drawCircle(left + 40f * k.u + j * 22f * k.u, top + 130f * k.u, 7f * k.u, k.fill)
            }
        } else {
            k.setText(k.fonts.body, (if (k.portrait) 34f else 30f) * k.u, 0xFF111111.toInt(), msg)
            val total = lines.sumOf { it.length }
            var shown = (total * k.seg(t, typeStart, typeStart + max(0.8f, d * 0.55f))).toInt()
            lines.forEachIndexed { i, l ->
                if (shown <= 0) return@forEachIndexed
                k.drawRich(c, l.take(shown), left + 34f * k.u, top + 128f * k.u + i * lh, s.emphasis, 0xFF111111.toInt(), k.theme.accent2)
                shown -= l.length
            }
        }
        // like burst at the end
        val lp = k.outBack(k.seg(t, d * 0.8f, d * 0.8f + 0.3f), 3f)
        if (lp > 0f) {
            k.setText(k.fonts.headline, 40f * k.u, k.theme.accent2, "♥"); k.text.textAlign = Paint.Align.CENTER
            c.save(); c.scale(lp, lp, left + cw - 40f * k.u, top + ch - 30f * k.u)
            c.drawText("♥", left + cw - 40f * k.u, top + ch - 16f * k.u, k.text); c.restore()
        }
        c.restore()
    }

    // ------------------------------------------------------------------ SEARCH BAR
    private fun search(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        Fx.backdrop(k, c, s, t)
        val q = s.title.ifBlank { s.text }.trim()
        val bw = if (k.portrait) k.w * 0.88f else k.w * 0.7f
        val left = k.cx - bw / 2
        val y = if (k.portrait) k.h * 0.3f else k.h * 0.26f
        val p = k.outCubic(k.seg(t, 0f, 0.35f))
        c.save(); c.translate(0f, (1 - p) * -80f * k.u)
        k.fill.color = 0x55000000; c.drawRoundRect(RectF(left + 6f * k.u, y + 8f * k.u, left + bw + 6f * k.u, y + 78f * k.u), 40f * k.u, 40f * k.u, k.fill)
        k.fill.color = Color.WHITE; c.drawRoundRect(RectF(left, y, left + bw, y + 70f * k.u), 35f * k.u, 35f * k.u, k.fill)
        k.magnifier(c, left + 38f * k.u, y + 32f * k.u, 11f * k.u, 0xFF777777.toInt())
        val size = k.fitSize(q, k.fonts.body, bw - 110f * k.u, 28f * k.u, 14f * k.u)
        k.setText(k.fonts.body, size, 0xFF222222.toInt(), q)
        val n = (q.length * k.seg(t, 0.35f, 0.35f + min(1.6f, q.length * 0.05f))).toInt()
        val shown = q.take(n)
        c.drawText(shown, left + 76f * k.u, y + 45f * k.u, k.text)
        if ((t * 2).toInt() % 2 == 0) { k.fill.color = k.theme.accent2; val cx0 = left + 78f * k.u + k.text.measureText(shown); c.drawRect(cx0, y + 20f * k.u, cx0 + 3f * k.u, y + 52f * k.u, k.fill) }
        c.restore()
        // result cards slide in after typing
        val res = s.lines.ifEmpty { ScriptAnalyzer.keywords(s.text, 3) }.take(3)
        val start = 0.4f + min(1.6f, q.length * 0.05f)
        res.forEachIndexed { i, rtext ->
            val rp = k.outCubic(k.seg(t, start + i * 0.25f, start + i * 0.25f + 0.4f))
            if (rp <= 0f) return@forEachIndexed
            val ry = y + 110f * k.u + i * 96f * k.u
            c.save(); c.translate((1 - rp) * 80f * k.u, 0f)
            k.fill.color = k.alpha(Color.WHITE, 0.1f * rp + 0.02f); c.drawRoundRect(RectF(left, ry, left + bw, ry + 80f * k.u), 16f * k.u, 16f * k.u, k.fill)
            k.fill.color = k.alpha(if (i == 0) k.theme.accent else k.theme.accent2, rp); c.drawRect(left, ry + 14f * k.u, left + 6f * k.u, ry + 66f * k.u, k.fill)
            val head = rtext.replaceFirstChar { it.uppercase() }
            k.setText(k.fonts.heavy, k.fitSize(head, k.fonts.heavy, bw - 50f * k.u, 26f * k.u, 13f * k.u), k.theme.accent, head); k.text.alpha = (255 * rp).toInt()
            c.drawText(head, left + 26f * k.u, ry + 34f * k.u, k.text)
            k.fill.color = k.alpha(Color.WHITE, 0.25f * rp)
            c.drawRoundRect(RectF(left + 26f * k.u, ry + 50f * k.u, left + bw * (0.5f + 0.15f * i), ry + 60f * k.u), 5f * k.u, 5f * k.u, k.fill)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ CIRCLE REVEAL
    private fun circleReveal(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        Fx.backdrop(k, c, s, t)
        val bmp = k.images.get(s.imageIndex) ?: return
        val ccx = if (k.portrait) k.cx else k.w * 0.34f
        val ccy = if (k.portrait) k.h * 0.36f else k.cy
        val rad = min(k.w, k.h) * 0.36f * k.outBack(k.seg(t, 0f, 0.6f), 1.2f)
        if (rad > 1f) {
            c.save(); c.clipPath(Path().apply { addCircle(ccx, ccy, rad, Path.Direction.CW) })
            k.rect.set(ccx - rad, ccy - rad, ccx + rad, ccy + rad)
            k.cover(c, bmp, k.rect, k.lerp(1.25f, 1.05f, k.clamp01(t / d)))
            c.restore()
            k.stroke.color = k.theme.accent; k.stroke.strokeWidth = 6f * k.u; c.drawCircle(ccx, ccy, rad + 10f * k.u, k.stroke)
            k.stroke.color = k.alpha(k.theme.accent2, 0.7f); k.stroke.strokeWidth = 3f * k.u
            k.rect.set(ccx - rad - 26f * k.u, ccy - rad - 26f * k.u, ccx + rad + 26f * k.u, ccy + rad + 26f * k.u)
            c.drawArc(k.rect, t * 90f, 120f, false, k.stroke); c.drawArc(k.rect, t * 90f + 180f, 120f, false, k.stroke)
        }
        val head = title(s, 6)
        k.setText(k.fonts.headline, 56f * k.u, Color.WHITE, head)
        val maxW = if (k.portrait) k.w * 0.86f else k.w * 0.4f
        val lines = k.wrap(head, maxW).take(3)
        val size = min(56f * k.u, lines.minOf { k.fitSize(it, k.fonts.headline, maxW, 56f * k.u) })
        k.setText(k.fonts.headline, size, Color.WHITE, head)
        val x = if (k.portrait) k.cx else k.w * 0.58f
        if (k.portrait) k.text.textAlign = Paint.Align.CENTER
        val y0 = (if (k.portrait) k.h * 0.72f else k.cy - (lines.size - 1) * size * 0.55f) + size * 0.35f
        lines.forEachIndexed { i, l ->
            k.text.color = if (i == lines.lastIndex) k.theme.accent else Color.WHITE
            Fx.text(k, c, l, x, y0 + i * size * 1.1f, k.seg(t, 0.4f + i * 0.15f, 0.9f + i * 0.15f), s.fx, i)
        }
    }

    // ------------------------------------------------------------------ DIAGONAL SPLIT
    private fun diagonal(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        Fx.backdrop(k, c, s, t)
        val bmp = k.images.get(s.imageIndex) ?: return
        val p = k.outCubic(k.seg(t, 0f, 0.5f))
        val cut = k.w * 0.56f
        val path = Path().apply {
            moveTo(k.w.toFloat(), 0f); lineTo(cut - k.w * 0.12f + (1 - p) * k.w, 0f)
            lineTo(cut - k.w * 0.28f + (1 - p) * k.w, k.h.toFloat()); lineTo(k.w.toFloat(), k.h.toFloat()); close()
        }
        c.save(); c.clipPath(path)
        k.rect.set(k.w * 0.25f, 0f, k.w.toFloat(), k.h.toFloat())
        k.cover(c, bmp, k.rect, k.lerp(1.1f, 1.2f, k.clamp01(t / d)))
        c.restore()
        k.stroke.color = k.theme.accent; k.stroke.strokeWidth = 8f * k.u
        c.drawLine(cut - k.w * 0.12f + (1 - p) * k.w, 0f, cut - k.w * 0.28f + (1 - p) * k.w, k.h.toFloat(), k.stroke)
        val head = title(s, 6)
        val maxW = k.w * 0.4f
        val bottom = k.banner(c, head, 44f * k.u, k.h * 0.22f, maxW, 34f * k.u, k.seg(t, 0.3f, 0.7f))
        val pts = s.lines.ifEmpty { s.emphasis }.take(3)
        pts.forEachIndexed { i, pt ->
            val ip = k.seg(t, 0.7f + i * 0.25f, 1.1f + i * 0.25f)
            k.setText(k.fonts.heavy, 28f * k.u, Color.WHITE, pt)
            k.fill.color = k.alpha(k.theme.accent, ip); c.drawCircle(56f * k.u, bottom + 56f * k.u + i * 56f * k.u - 9f * k.u, 8f * k.u, k.fill)
            Fx.text(k, c, pt.replaceFirstChar { it.uppercase() }, 78f * k.u, bottom + 56f * k.u + i * 56f * k.u, ip, s.fx, i)
        }
    }

    // ------------------------------------------------------------------ WORD CLOUD
    private fun cloud(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        Fx.backdrop(k, c, s, t)
        val words = s.lines.ifEmpty { ScriptAnalyzer.keywords(s.text, 7) }.take(8)
        if (words.isEmpty()) return
        val r = k.rnd(s, 31)
        val placed = ArrayList<RectF>()
        words.forEachIndexed { i, w ->
            val size = (if (i == 0) 90f else 58f - i * 4f) * k.u
            k.setText(if (i % 2 == 0) k.fonts.headline else k.fonts.heavy, size, Color.WHITE, w)
            val tw = k.text.measureText(w.uppercase())
            var x = 0f; var y = 0f; var ok = false
            for (attempt in 0 until 40) {
                val ang = attempt * 0.9f + r.nextFloat(); val rad = attempt * 14f * k.u
                x = k.cx + cos(ang) * rad * 1.6f - tw / 2; y = k.cy + sin(ang) * rad
                val box = RectF(x - 8f * k.u, y - size, x + tw + 8f * k.u, y + size * 0.25f)
                if (box.left > 10 && box.right < k.w - 10 && box.top > 10 && box.bottom < k.h - 10 && placed.none { RectF.intersects(it, box) }) { placed += box; ok = true; break }
            }
            if (!ok) return@forEachIndexed
            k.text.color = when (i % 3) { 0 -> k.theme.accent; 1 -> Color.WHITE; else -> k.theme.accent2 }
            val st = 0.15f + i * min(0.25f, d * 0.6f / words.size)
            val bob = sin(t * 1.2f + i) * 4f * k.u
            Fx.text(k, c, w.uppercase(), x, y + bob, k.seg(t, st, st + 0.4f), s.fx, i)
        }
    }

    // ------------------------------------------------------------------ GAUGE
    private fun gauge(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        Fx.backdrop(k, c, s, t)
        val value = s.lines.firstOrNull()?.toFloatOrNull() ?: 50f
        val p = k.outExpo(k.seg(t, 0.2f, min(1.8f, d * 0.6f)))
        val r = min(k.w, k.h) * 0.34f
        val gx = k.cx; val gy = k.cy + r * 0.35f
        k.rect.set(gx - r, gy - r, gx + r, gy + r)
        k.stroke.strokeCap = Paint.Cap.BUTT; k.stroke.strokeWidth = r * 0.18f
        k.stroke.color = k.alpha(Color.WHITE, 0.12f); c.drawArc(k.rect, 180f, 180f, false, k.stroke)
        k.stroke.shader = SweepGradient(gx, gy, intArrayOf(k.theme.accent, k.theme.accent, k.theme.accent2, k.theme.accent2), floatArrayOf(0f, 0.5f, 0.75f, 1f))
        c.drawArc(k.rect, 180f, 180f * value / 100f * p, false, k.stroke)
        k.stroke.shader = null; k.stroke.strokeCap = Paint.Cap.ROUND
        // ticks
        k.stroke.color = k.alpha(Color.WHITE, 0.5f); k.stroke.strokeWidth = 3f * k.u
        for (i in 0..10) {
            val a = (PI + PI * i / 10).toFloat()
            c.drawLine(gx + cos(a) * r * 0.78f, gy + sin(a) * r * 0.78f, gx + cos(a) * r * 0.86f, gy + sin(a) * r * 0.86f, k.stroke)
        }
        val wobble = if (p >= 0.99f) sin(t * 14f) * 1.5f * (1 - k.seg(t, d * 0.6f, d * 0.6f + 0.6f)) else 0f
        val na = (PI + PI * value / 100f * p).toFloat() + wobble * 0.02f
        k.stroke.color = Color.WHITE; k.stroke.strokeWidth = 7f * k.u
        c.drawLine(gx, gy, gx + cos(na) * r * 0.74f, gy + sin(na) * r * 0.74f, k.stroke)
        k.fill.color = k.theme.accent; c.drawCircle(gx, gy, 16f * k.u, k.fill)
        k.setText(k.fonts.headline, r * 0.42f, Color.WHITE, "${(value * p).toInt()}%"); k.text.textAlign = Paint.Align.CENTER
        k.glow(k.theme.accent, 14f * k.u)
        c.drawText("${(value * p).toInt()}%", gx, gy + r * 0.45f, k.text); k.text.clearShadowLayer()
        if (s.title.isNotBlank()) {
            val ts = k.fitSize(s.title, k.fonts.heavy, k.w * 0.8f, 40f * k.u)
            k.setText(k.fonts.heavy, ts, Color.WHITE, s.title); k.text.textAlign = Paint.Align.CENTER
            Fx.text(k, c, s.title, k.cx, gy - r - 40f * k.u, k.seg(t, 0f, 0.5f), s.fx)
        }
    }

    // ------------------------------------------------------------------ STEPS
    private fun steps(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        Fx.backdrop(k, c, s, t)
        val items = s.lines.ifEmpty { ScriptAnalyzer.keywords(s.text, 4) }.take(5)
        if (items.isEmpty()) return
        val n = items.size
        val stepW = (k.w - 120f * k.u) / n
        val baseY = k.h * 0.82f
        val stepH = (k.h * 0.5f) / n
        val span = max(0.9f, d * 0.75f)
        items.forEachIndexed { i, item ->
            val st = 0.2f + span * i / n
            val p = k.outBack(k.seg(t, st, st + 0.4f), 1.2f)
            if (p <= 0f) return@forEachIndexed
            val x = 60f * k.u + i * stepW
            val top = baseY - stepH * (i + 1)
            val col = k.mix(k.theme.accent, k.theme.accent2, i / max(1f, n - 1f))
            c.save(); c.scale(1f, p, x, baseY)
            k.fill.color = k.alpha(col, 0.9f); c.drawRoundRect(RectF(x + 6f * k.u, top, x + stepW - 6f * k.u, baseY), 12f * k.u, 12f * k.u, k.fill)
            k.fill.color = k.alpha(Color.BLACK, 0.18f); c.drawRect(x + 6f * k.u, top + 18f * k.u, x + stepW - 6f * k.u, baseY, k.fill)
            c.restore()
            k.setText(k.fonts.headline, min(64f * k.u, stepW * 0.4f), Color.WHITE, "${i + 1}"); k.text.textAlign = Paint.Align.CENTER
            k.text.alpha = (255 * k.clamp01(p)).toInt()
            c.drawText("${i + 1}", x + stepW / 2, top + min(64f * k.u, stepW * 0.4f) + 10f * k.u, k.text)
            val ls = k.fitSize(item, k.fonts.heavy, stepW - 16f * k.u, 26f * k.u, 11f * k.u)
            k.setText(k.fonts.heavy, ls, Color.WHITE, item); k.text.textAlign = Paint.Align.CENTER
            Fx.text(k, c, item.replaceFirstChar { it.uppercase() }, x + stepW / 2, top - 16f * k.u, k.seg(t, st + 0.2f, st + 0.6f), s.fx, i)
        }
        val head = title(s, 5)
        if (head.isNotBlank()) k.banner(c, head, 44f * k.u, 40f * k.u, k.w * 0.7f, 30f * k.u, k.seg(t, 0f, 0.4f))
    }

    // ------------------------------------------------------------------ STICKY NOTES
    private fun sticky(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        Fx.backdrop(k, c, s, t)
        val items = s.lines.ifEmpty { ScriptAnalyzer.keywords(s.text, 4) }.take(4)
        if (items.isEmpty()) return
        val r = Random(s.variant)
        val n = items.size
        val nw = if (k.portrait) k.w * 0.4f else min(k.w / (n + 0.6f), k.h * 0.5f)
        val colors = intArrayOf(0xFFFFE97A.toInt(), 0xFFFF9FB2.toInt(), 0xFF9FE6FF.toInt(), 0xFFB8F28B.toInt())
        val span = max(0.9f, d * 0.7f)
        items.forEachIndexed { i, item ->
            val st = 0.1f + span * i / n
            val p = k.seg(t, st, st + 0.35f)
            if (p <= 0f) return@forEachIndexed
            val col = i % 2; val row = i / 2
            val x = if (k.portrait) k.w * (0.27f + col * 0.46f) else k.w * (i + 0.8f) / (n + 0.6f)
            val y = if (k.portrait) k.h * (0.32f + row * 0.34f) else k.cy + (if (i % 2 == 0) -20f else 30f) * k.u
            val rot = (r.nextFloat() - 0.5f) * 12f
            val slap = if (p < 1f) k.lerp(1.6f, 1f, k.outBack(p, 2f)) else 1f
            c.save(); c.translate(x, y); c.rotate(rot); c.scale(slap, slap)
            k.fill.color = 0x55000000; c.drawRect(-nw / 2 + 8f * k.u, -nw / 2 + 10f * k.u, nw / 2 + 8f * k.u, nw / 2 + 10f * k.u, k.fill)
            k.fill.color = colors[i % colors.size]; c.drawRect(-nw / 2, -nw / 2, nw / 2, nw / 2, k.fill)
            k.fill.color = k.alpha(Color.BLACK, 0.08f); c.drawRect(-nw / 2, -nw / 2, nw / 2, -nw / 2 + 26f * k.u, k.fill)
            val size = k.fitSize(item, k.fonts.hand, nw * 0.85f, 44f * k.u)
            k.setText(k.fonts.hand, size, 0xFF222222.toInt(), item); k.text.textAlign = Paint.Align.CENTER
            val ls = k.wrap(item.replaceFirstChar { it.uppercase() }, nw * 0.85f).take(3)
            ls.forEachIndexed { li, l -> c.drawText(l, 0f, (li - (ls.size - 1) / 2f) * size * 1.15f + size * 0.35f, k.text) }
            c.restore()
        }
        val head = title(s, 5)
        if (head.isNotBlank()) k.banner(c, head, 44f * k.u, 36f * k.u, k.w * 0.7f, 28f * k.u, k.seg(t, 0f, 0.4f))
    }
}
