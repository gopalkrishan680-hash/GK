package com.gk.facelessstudio.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.gk.facelessstudio.engine.ScriptAnalyzer
import com.gk.facelessstudio.model.Scene
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.model.StoryStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Story-mode looks. Each style redraws the picture shots (Ken Burns / headline scenes) its own way,
 * grades every image, and adds frame-level effects (scratches, rain, paper, halftone...).
 */
object StoryStyles {

    // ------------------------------------------------------------------ colour grades
    fun grade(style: StoryStyle?): ColorMatrix? = when (style) {
        StoryStyle.VINTAGE -> ColorMatrix(floatArrayOf(
            0.42f, 0.74f, 0.18f, 0f, 8f,
            0.36f, 0.66f, 0.16f, 0f, 2f,
            0.26f, 0.50f, 0.12f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f))
        StoryStyle.NOIR -> ColorMatrix(floatArrayOf(        // cold steel-blue monochrome, crushed blacks
            0.27f, 0.53f, 0.10f, 0f, -34f,
            0.33f, 0.65f, 0.12f, 0f, -30f,
            0.38f, 0.75f, 0.14f, 0f, -20f,
            0f, 0f, 0f, 1f, 0f))
        StoryStyle.COMIC -> ColorMatrix().apply {
            setSaturation(1.45f)
            postConcat(ColorMatrix(floatArrayOf(1.15f, 0f, 0f, 0f, -14f, 0f, 1.15f, 0f, 0f, -14f, 0f, 0f, 1.15f, 0f, -14f, 0f, 0f, 0f, 1f, 0f)))
        }
        StoryStyle.DOCUMENTARY -> ColorMatrix().apply {
            setSaturation(0.78f)
            postConcat(ColorMatrix(floatArrayOf(1.06f, 0f, 0f, 0f, 6f, 0f, 1.0f, 0f, 0f, 0f, 0f, 0f, 0.9f, 0f, -6f, 0f, 0f, 0f, 1f, 0f)))
        }
        StoryStyle.STORYBOOK -> ColorMatrix().apply {
            setSaturation(0.92f)
            postConcat(ColorMatrix(floatArrayOf(1.04f, 0f, 0f, 0f, 10f, 0f, 1.02f, 0f, 0f, 6f, 0f, 0f, 0.94f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        }
        else -> null
    }

    /** Styles that draw their own text on picture shots (so the generic captions stay off). */
    fun ownsText(style: StoryStyle?) = style == StoryStyle.COMIC || style == StoryStyle.STORYBOOK || style == StoryStyle.MYSTERY ||
        style == StoryStyle.DOCUMENTARY || style == StoryStyle.VINTAGE

    fun letterbox(style: StoryStyle?) = style == StoryStyle.CINEMATIC || style == StoryStyle.NOIR

    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private fun line(s: Scene) = s.title.ifBlank { ScriptAnalyzer.phrase(s.text, 12) }

    /** Draws a picture shot in the active style. Returns false to fall back to the default template. */
    fun shot(k: Kit, c: Canvas, s: Scene, t: Float, d: Float): Boolean {
        if (s.type != SceneType.KEN_BURNS && s.type != SceneType.HEADLINE_IMAGE) return false
        val bmp = k.images.get(s.imageIndex) ?: return false
        return when (k.style) {
            StoryStyle.DOCUMENTARY -> { documentary(k, c, s, bmp, t, d); true }
            StoryStyle.COMIC -> { comic(k, c, s, bmp, t, d); true }
            StoryStyle.VINTAGE -> { vintage(k, c, s, bmp, t, d); true }
            StoryStyle.STORYBOOK -> { storybook(k, c, s, bmp, t, d); true }
            StoryStyle.MYSTERY -> { mystery(k, c, s, bmp, t, d); true }
            StoryStyle.NOIR -> { noir(k, c, s, bmp, t, d); true }
            else -> false
        }
    }

    private fun camera(k: Kit, c: Canvas, s: Scene, bmp: Bitmap, dst: RectF, t: Float, d: Float, strength: Float = 1f) {
        val r = k.rnd(s, 3)
        val e = k.inOutCubic(k.clamp01((t + 0.3f) / (d + 0.6f)))
        val fx = (r.nextFloat() - 0.5f) * 1.2f; val fy = (r.nextFloat() - 0.5f) * 0.8f
        when (r.nextInt(4)) {
            0 -> k.cover(c, bmp, dst, k.lerp(1.04f, 1.04f + 0.16f * strength, e), fx * e, fy * e)
            1 -> k.cover(c, bmp, dst, k.lerp(1.04f + 0.16f * strength, 1.04f, e), fx * (1 - e), fy * (1 - e))
            2 -> k.cover(c, bmp, dst, 1.12f, k.lerp(-0.7f, 0.7f, e) * strength, fy * 0.3f)
            else -> k.cover(c, bmp, dst, 1.12f, k.lerp(0.7f, -0.7f, e) * strength, fy * 0.3f)
        }
    }

    // ------------------------------------------------------------------ DOCUMENTARY
    private fun documentary(k: Kit, c: Canvas, s: Scene, bmp: Bitmap, t: Float, d: Float) {
        k.rect.set(0f, 0f, k.w.toFloat(), k.h.toFloat())
        camera(k, c, s, bmp, k.rect, t, d, 0.8f)
        k.fadeEdgeV(c, k.h.toFloat(), k.h * 0.55f, 0xB0000000.toInt())
        val name = s.emphasis.firstOrNull()
        val gold = 0xFFD9B25F.toInt()
        if (s.type == SceneType.HEADLINE_IMAGE) {
            val title = s.title.ifBlank { line(s) }.uppercase()
            val p = k.outCubic(k.seg(t, 0.2f, 1.0f))
            k.setText(k.fonts.heavy, 54f * k.u, Color.WHITE, title)
            k.text.letterSpacing = 0.25f - 0.1f * p
            val lines = k.wrap(title, k.w * 0.8f).take(2)
            k.text.textAlign = Paint.Align.CENTER; k.text.alpha = (255 * p).toInt()
            k.text.setShadowLayer(10f * k.u, 0f, 3f * k.u, 0xCC000000.toInt())
            val y0 = k.h * 0.7f - (lines.size - 1) * 64f * k.u
            lines.forEachIndexed { i, l -> c.drawText(l, k.cx, y0 + i * 64f * k.u, k.text) }
            k.text.clearShadowLayer()
            k.fill.color = gold
            val lw = 140f * k.u * p
            c.drawRect(k.cx - lw, y0 + (lines.size - 1) * 64f * k.u + 26f * k.u, k.cx + lw, y0 + (lines.size - 1) * 64f * k.u + 29f * k.u, k.fill)
        } else if (name != null) {
            // lower-third name card: NAME + who they are (from the character sheet)
            val p = k.outCubic(k.seg(t, 0.3f, 0.8f)) * (1f - k.seg(t, d - 0.4f, d))
            val role = k.characterInfo[name.lowercase()]?.split(',')?.take(2)?.joinToString(",")?.trim() ?: ""
            val x = 60f * k.u; val y = k.h * 0.78f
            k.fill.color = k.alpha(gold, p)
            c.drawRect(x, y - 52f * k.u, x + 5f * k.u, y + (if (role.isNotBlank()) 30f else 6f) * k.u, k.fill)
            k.setText(k.fonts.heavy, 44f * k.u, Color.WHITE, name)
            k.text.letterSpacing = 0.12f; k.text.alpha = (255 * p).toInt()
            c.drawText(name.uppercase(), x + 22f * k.u - (1 - p) * 30f * k.u, y, k.text)
            if (role.isNotBlank()) {
                k.setText(k.fonts.body, 22f * k.u, gold, role); k.text.alpha = (230 * p).toInt()
                c.drawText(role, x + 22f * k.u - (1 - p) * 30f * k.u, y + 30f * k.u, k.text)
            }
        } else if (s.title.isNotBlank()) k.subtitle(c, s.title, t, d)
    }

    // ------------------------------------------------------------------ COMIC
    private fun halftone(k: Kit): Paint = k.halftonePaint ?: run {
        val n = max(6, (9 * k.u).toInt())
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        Canvas(b).drawCircle(n / 2f, n / 2f, n * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 })
        Paint().apply { shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }.also { k.halftonePaint = it }
    }

    private fun comicPanel(k: Kit, c: Canvas, bmp: Bitmap, r: RectF, rot: Float, zoom: Float, pan: Float, pin: Float) {
        if (pin <= 0f) return
        c.save()
        c.rotate(rot, r.centerX(), r.centerY())
        c.translate(0f, (1f - k.outBack(pin, 1.3f)) * k.h * 0.6f)
        k.fill.color = Color.BLACK
        k.rect2.set(r.left - 7f * k.u, r.top - 7f * k.u, r.right + 7f * k.u, r.bottom + 7f * k.u)
        c.drawRect(k.rect2, k.fill)
        k.cover(c, bmp, r, zoom, pan)
        c.drawRect(r, halftone(k))
        c.restore()
    }

    private fun comic(k: Kit, c: Canvas, s: Scene, bmp: Bitmap, t: Float, d: Float) {
        val r = k.rnd(s, 7)
        c.drawColor(0xFFF6E7C1.toInt())
        c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), halftone(k))
        val e = k.clamp01(t / d)
        val m = 26f * k.u
        when (if (s.type == SceneType.HEADLINE_IMAGE) 0 else r.nextInt(3)) {
            0 -> comicPanel(k, c, bmp, RectF(m, m, k.w - m, k.h - m), (r.nextFloat() - 0.5f) * 2f, k.lerp(1.05f, 1.18f, e), 0f, k.seg(t, 0f, 0.35f))
            1 -> { // two panels: wide + close-up of the same picture
                val mid = k.w * (0.48f + (r.nextFloat() - 0.5f) * 0.1f)
                comicPanel(k, c, bmp, RectF(m, m, mid - m / 2, k.h - m), -1.5f, k.lerp(1.1f, 1.2f, e), -0.6f, k.seg(t, 0f, 0.35f))
                comicPanel(k, c, bmp, RectF(mid + m / 2, m, k.w - m, k.h - m), 1.5f, k.lerp(1.7f, 1.85f, e), 0.6f, k.seg(t, 0.25f, 0.6f))
            }
            else -> { // wide top + two small bottom
                val midY = k.h * 0.55f
                comicPanel(k, c, bmp, RectF(m, m, k.w - m, midY - m / 2), -0.8f, k.lerp(1.1f, 1.2f, e), 0f, k.seg(t, 0f, 0.35f))
                comicPanel(k, c, bmp, RectF(m, midY + m / 2, k.cx - m / 2, k.h - m), 1f, 1.9f, -0.7f, k.seg(t, 0.25f, 0.6f))
                comicPanel(k, c, bmp, RectF(k.cx + m / 2, midY + m / 2, k.w - m, k.h - m), -1f, 1.9f, 0.7f, k.seg(t, 0.45f, 0.8f))
            }
        }
        // narration box
        val text = line(s).uppercase()
        val bp = k.outBack(k.seg(t, 0.35f, 0.65f))
        if (bp > 0f && text.isNotBlank()) {
            val size = if (s.type == SceneType.HEADLINE_IMAGE) 44f * k.u else 26f * k.u
            k.setText(k.fonts.heavy, size, 0xFF111111.toInt(), text)
            val lines = k.wrap(text, k.w * (if (k.portrait) 0.78f else 0.46f)).take(3)
            val bw = lines.maxOf { k.text.measureText(it) } + 36f * k.u
            val bh = lines.size * size * 1.25f + 24f * k.u
            c.save(); c.scale(bp, bp, m + 20f * k.u, m + 20f * k.u); c.rotate(-1.5f, m, m)
            k.rect.set(m + 12f * k.u, m + 12f * k.u, m + 12f * k.u + bw, m + 12f * k.u + bh)
            k.fill.color = 0xFFFFE14D.toInt(); c.drawRect(k.rect, k.fill)
            k.stroke.color = Color.BLACK; k.stroke.strokeWidth = 4f * k.u; c.drawRect(k.rect, k.stroke)
            lines.forEachIndexed { i, l -> c.drawText(l, k.rect.left + 18f * k.u, k.rect.top + 12f * k.u + size * (1.05f + i * 1.25f), k.text) }
            c.restore()
        }
        // name burst
        val name = s.emphasis.firstOrNull()?.uppercase()
        val sp = k.outBack(k.seg(t, 0.7f, 1.0f), 2.5f)
        if (name != null && sp > 0f) {
            val bx = k.w * 0.78f; val by = k.h * 0.78f
            val size = k.fitSize(name, k.fonts.headline, 220f * k.u, 46f * k.u)
            c.save(); c.scale(sp, sp, bx, by); c.rotate(-8f + sin(t * 3f) * 2f, bx, by)
            k.path.reset()
            val spikes = 14
            for (i in 0 until spikes * 2) {
                val a = (i * PI / spikes).toFloat()
                val rad = (if (i % 2 == 0) 150f else 105f) * k.u
                val x = bx + cos(a) * rad * 1.15f; val y = by + sin(a) * rad * 0.7f
                if (i == 0) k.path.moveTo(x, y) else k.path.lineTo(x, y)
            }
            k.path.close()
            k.fill.color = k.theme.accent2; c.drawPath(k.path, k.fill)
            k.stroke.color = Color.BLACK; k.stroke.strokeWidth = 5f * k.u; c.drawPath(k.path, k.stroke)
            k.setText(k.fonts.headline, size, 0xFFFFE14D.toInt(), name); k.text.textAlign = Paint.Align.CENTER
            k.text.style = Paint.Style.STROKE; k.text.strokeWidth = 6f * k.u; k.text.color = Color.BLACK
            c.drawText(name, bx, by + size * 0.35f, k.text)
            k.text.style = Paint.Style.FILL; k.text.color = 0xFFFFE14D.toInt()
            c.drawText(name, bx, by + size * 0.35f, k.text)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ VINTAGE
    private fun vintage(k: Kit, c: Canvas, s: Scene, bmp: Bitmap, t: Float, d: Float) {
        k.rect.set(0f, 0f, k.w.toFloat(), k.h.toFloat())
        if (s.type == SceneType.HEADLINE_IMAGE) {
            // silent-film title card over a dimmed picture
            camera(k, c, s, bmp, k.rect, t, d, 0.5f)
            c.drawColor(0xC0100C08.toInt())
            val inset = 60f * k.u
            k.stroke.color = 0xFFE8DCC0.toInt(); k.stroke.strokeWidth = 3f * k.u
            k.rect.set(inset, inset, k.w - inset, k.h - inset); c.drawRect(k.rect, k.stroke)
            k.stroke.strokeWidth = 1.5f * k.u
            k.rect.set(inset + 12f * k.u, inset + 12f * k.u, k.w - inset - 12f * k.u, k.h - inset - 12f * k.u); c.drawRect(k.rect, k.stroke)
            val title = s.title.ifBlank { line(s) }
            k.setText(k.fonts.hand, 58f * k.u, 0xFFF1E6CC.toInt(), title); k.text.textAlign = Paint.Align.CENTER
            val lines = k.wrap(title, k.w * 0.7f).take(3)
            val p = k.seg(t, 0.2f, 0.8f); k.text.alpha = (255 * p).toInt()
            val y0 = k.cy - (lines.size - 1) * 36f * k.u + 18f * k.u
            lines.forEachIndexed { i, l -> c.drawText(l, k.cx, y0 + i * 72f * k.u, k.text) }
            return
        }
        camera(k, c, s, bmp, k.rect, t, d, 0.9f)
        // typewriter subtitle
        val text = s.title.ifBlank { "" }
        if (text.isNotBlank()) {
            val n = (text.length * k.seg(t, 0.3f, 0.3f + text.length * 0.045f)).toInt()
            val shown = text.take(n)
            val size = 28f * k.u
            k.text.reset(); k.text.isAntiAlias = true; k.text.typeface = mono; k.text.textSize = size; k.text.color = 0xFFF4EBD6.toInt()
            val lines = k.wrap(shown, k.w * 0.82f).take(2)
            val y0 = k.h * 0.86f - (lines.size - 1) * size * 1.3f
            k.fill.color = 0x99000000.toInt()
            if (lines.isNotEmpty()) c.drawRect(0f, y0 - size * 1.3f, k.w.toFloat(), y0 + (lines.size - 1) * size * 1.3f + size * 0.6f, k.fill)
            k.text.textAlign = Paint.Align.CENTER
            lines.forEachIndexed { i, l -> c.drawText(l, k.cx, y0 + i * size * 1.3f, k.text) }
        }
    }

    // ------------------------------------------------------------------ STORYBOOK
    private fun storybook(k: Kit, c: Canvas, s: Scene, bmp: Bitmap, t: Float, d: Float) {
        c.drawColor(0xFFF2E6CE.toInt())
        k.fill.shader = RadialGradient(k.cx, k.cy, max(k.w, k.h) * 0.75f, intArrayOf(0, 0x55704A1E), floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
        k.particles(c, 0f, s.variant, 120, 0xFF8A6A40.toInt())
        val r = k.rnd(s, 9)
        val portrait = k.portrait
        val iw = if (portrait) k.w * 0.84f else k.w * 0.64f
        val ih = iw * k.h / k.w * (if (portrait) 0.8f else 1f)
        val left = k.cx - iw / 2; val top = if (portrait) k.h * 0.12f else k.h * 0.07f
        val tilt = (r.nextFloat() - 0.5f) * 2.4f
        val pin = k.outCubic(k.seg(t, 0f, 0.5f))
        c.save(); c.rotate(tilt * pin, k.cx, top + ih / 2)
        k.fill.color = 0x40000000
        k.rect.set(left + 8f * k.u, top + 12f * k.u, left + iw + 8f * k.u, top + ih + 12f * k.u); c.drawRect(k.rect, k.fill)
        k.fill.color = 0xFFFFFBF2.toInt()
        k.rect.set(left - 10f * k.u, top - 10f * k.u, left + iw + 10f * k.u, top + ih + 10f * k.u); c.drawRect(k.rect, k.fill)
        k.rect.set(left, top, left + iw, top + ih)
        camera(k, c, s, bmp, RectF(k.rect), t, d, 0.6f)
        // ornament corners
        k.stroke.color = 0xFFB0863F.toInt(); k.stroke.strokeWidth = 2.5f * k.u
        for ((x, y) in listOf(left to top, left + iw to top, left to top + ih, left + iw to top + ih)) {
            c.drawCircle(x, y, 9f * k.u, k.stroke)
            k.fill.color = 0xFFB0863F.toInt(); c.drawCircle(x, y, 3.5f * k.u, k.fill)
        }
        c.restore()
        // handwritten line on the page
        val text = line(s)
        val size = (if (s.type == SceneType.HEADLINE_IMAGE) 46f else 34f) * k.u
        k.setText(k.fonts.hand, size, 0xFF3B2A18.toInt(), text)
        val lines = k.wrap(text, k.w * 0.82f).take(2)
        val words = lines.flatMap { it.split(" ") }
        val span = max(0.8f, d * 0.7f)
        var wi = 0
        val y0 = top + ih + (k.h - top - ih) / 2 - (lines.size - 1) * size * 0.6f + size * 0.3f
        val space = k.text.measureText(" ")
        val em = s.emphasis.map { it.lowercase() }.toSet()
        lines.forEachIndexed { li, l ->
            var x = k.cx - k.text.measureText(l) / 2
            for (wd in l.split(" ")) {
                val st = 0.3f + span * wi / max(1, words.size)
                val a = k.seg(t, st, st + 0.3f)
                k.text.color = if (ScriptAnalyzer.cleanWord(wd).lowercase() in em) 0xFF9C2A1A.toInt() else 0xFF3B2A18.toInt()
                k.text.alpha = (255 * a).toInt()
                c.drawText(wd, x, y0 + li * size * 1.2f, k.text)
                x += k.text.measureText(wd) + space
                wi++
            }
        }
    }

    // ------------------------------------------------------------------ MYSTERY BOARD
    private fun boardSpot(k: Kit, i: Int): Pair<Float, Float> {
        val r = Random(i * 31 + 7)
        // photos laid out on a big board, ~1.6 screens wide
        val col = i % 3; val row = (i / 3) % 2
        return Pair(k.w * (0.25f + col * 0.55f) + (r.nextFloat() - 0.5f) * k.w * 0.12f, k.h * (0.32f + row * 0.62f) + (r.nextFloat() - 0.5f) * k.h * 0.1f)
    }

    private fun polaroid(k: Kit, c: Canvas, bmp: Bitmap?, x: Float, y: Float, wdt: Float, rot: Float, label: String, dim: Boolean) {
        val hgt = wdt * 0.62f
        c.save(); c.rotate(rot, x, y)
        k.fill.color = 0x66000000
        k.rect.set(x - wdt / 2 + 8f * k.u, y - hgt / 2 + 12f * k.u, x + wdt / 2 + 8f * k.u, y + hgt / 2 + 60f * k.u); c.drawRect(k.rect, k.fill)
        k.fill.color = 0xFFF7F3EA.toInt()
        k.rect.set(x - wdt / 2 - 12f * k.u, y - hgt / 2 - 12f * k.u, x + wdt / 2 + 12f * k.u, y + hgt / 2 + 52f * k.u); c.drawRect(k.rect, k.fill)
        if (bmp != null) { k.rect2.set(x - wdt / 2, y - hgt / 2, x + wdt / 2, y + hgt / 2); k.cover(c, bmp, k.rect2, 1.05f) }
        if (label.isNotBlank()) {
            val size = k.fitSize(label, k.fonts.hand, wdt * 0.9f, 32f * k.u)
            k.setText(k.fonts.hand, size, 0xFF2A2A2A.toInt(), label); k.text.textAlign = Paint.Align.CENTER
            c.drawText(label, x, y + hgt / 2 + 38f * k.u, k.text)
        }
        if (dim) { k.fill.color = 0x55000000; k.rect.set(x - wdt / 2 - 12f * k.u, y - hgt / 2 - 12f * k.u, x + wdt / 2 + 12f * k.u, y + hgt / 2 + 52f * k.u); c.drawRect(k.rect, k.fill) }
        // pin
        k.fill.color = 0xFFC62828.toInt(); c.drawCircle(x, y - hgt / 2 - 2f * k.u, 11f * k.u, k.fill)
        k.fill.color = 0x99FFFFFF.toInt(); c.drawCircle(x - 3f * k.u, y - hgt / 2 - 5f * k.u, 3.5f * k.u, k.fill)
        c.restore()
    }

    private fun mystery(k: Kit, c: Canvas, s: Scene, bmp: Bitmap, t: Float, d: Float) {
        val scenes = k.project.scenes
        val idx = scenes.indexOfFirst { it.startMs == s.startMs }.coerceAtLeast(0)
        val prev = scenes.getOrNull(idx - 1)
        val (cx1, cy1) = boardSpot(k, idx)
        val (cx0, cy0) = if (prev != null) boardSpot(k, idx - 1) else Pair(cx1 - k.w * 0.3f, cy1)
        val move = k.inOutCubic(k.seg(t, 0f, min(1.1f, d * 0.4f)))
        val camX = k.lerp(cx0, cx1, move); val camY = k.lerp(cy0, cy1, move)
        val zoom = k.lerp(1.0f, 1.12f, k.clamp01(t / d))
        // cork board
        c.drawColor(0xFF4A3322.toInt())
        c.save()
        c.translate(k.cx, k.cy); c.scale(zoom, zoom); c.translate(-camX, -camY)
        k.particles(c, 0f, 77, 260, 0xFF2E1E12.toInt())
        val pw = if (k.portrait) k.w * 0.7f else k.w * 0.42f
        if (prev != null) polaroid(k, c, k.images.get(prev.imageIndex), cx0, cy0, pw, -4f, prev.emphasis.firstOrNull() ?: "", true)
        polaroid(k, c, bmp, cx1, cy1, pw, if (idx % 2 == 0) 3f else -3f, s.emphasis.firstOrNull() ?: "", false)
        // red string from the last clue to this one
        if (prev != null) {
            val sp = k.seg(t, 0.2f, 0.9f)
            val ax = cx0; val ay = cy0 - pw * 0.31f; val bx = cx1; val by = cy1 - pw * 0.31f
            k.stroke.color = 0xFFD32F2F.toInt(); k.stroke.strokeWidth = 3.5f * k.u
            val ex = k.lerp(ax, bx, sp); val ey = k.lerp(ay, by, sp) + sin(sp * PI.toFloat()) * 40f * k.u
            k.path.reset(); k.path.moveTo(ax, ay); k.path.quadTo((ax + ex) / 2, max(ay, ey) + 50f * k.u, ex, ey)
            c.drawPath(k.path, k.stroke)
        }
        c.restore()
        // sticky note with the line
        val text = s.title.ifBlank { if (s.type == SceneType.HEADLINE_IMAGE) line(s) else "" }
        val np = k.outBack(k.seg(t, 0.8f, 1.15f))
        if (text.isNotBlank() && np > 0f) {
            val nw = if (k.portrait) k.w * 0.7f else k.w * 0.3f
            k.setText(k.fonts.hand, 30f * k.u, 0xFF222222.toInt(), text)
            val lines = k.wrap(text, nw - 30f * k.u).take(4)
            val nh = lines.size * 38f * k.u + 34f * k.u
            val nx = k.w - nw - 40f * k.u; val ny = k.h - nh - 50f * k.u
            c.save(); c.scale(np, np, nx + nw / 2, ny + nh / 2); c.rotate(-3f, nx + nw / 2, ny + nh / 2)
            k.fill.color = 0x55000000; c.drawRect(nx + 6f * k.u, ny + 8f * k.u, nx + nw + 6f * k.u, ny + nh + 8f * k.u, k.fill)
            k.fill.color = 0xFFFFE97A.toInt(); c.drawRect(nx, ny, nx + nw, ny + nh, k.fill)
            lines.forEachIndexed { i, l -> c.drawText(l, nx + 15f * k.u, ny + 40f * k.u + i * 38f * k.u, k.text) }
            c.restore()
        }
    }

    // ------------------------------------------------------------------ NOIR
    private fun noir(k: Kit, c: Canvas, s: Scene, bmp: Bitmap, t: Float, d: Float) {
        k.rect.set(0f, 0f, k.w.toFloat(), k.h.toFloat())
        camera(k, c, s, bmp, k.rect, t, d, 1f)
        val r = k.rnd(s, 11)
        // lightning on some shots
        if (r.nextInt(3) == 0) {
            val f = k.seg(t, 0.4f, 0.48f) * (1f - k.seg(t, 0.5f, 0.75f))
            if (f > 0f) c.drawColor(k.alpha(Color.WHITE, f * 0.55f))
        }
        if (s.type == SceneType.HEADLINE_IMAGE) {
            val title = s.title.ifBlank { line(s) }.uppercase()
            val size = k.fitSize(title, k.fonts.condensed, k.w * 0.86f, 120f * k.u)
            k.setText(k.fonts.condensed, size, Color.WHITE, title); k.text.textAlign = Paint.Align.CENTER
            val p = k.seg(t, 0.1f, 0.5f)
            val flick = if (t < 0.6f && (t * 20).toInt() % 3 == 0) 0.4f else 1f
            k.text.alpha = (255 * p * flick).toInt()
            k.text.setShadowLayer(20f * k.u, 0f, 0f, k.alpha(k.theme.accent, 0.9f))
            c.drawText(title, k.cx, k.cy + size * 0.35f, k.text)
            k.text.clearShadowLayer()
        } else k.subtitle(c, s.title, t, d)
    }

    // ------------------------------------------------------------------ frame-level effects
    fun frameFx(k: Kit, c: Canvas, tMs: Long) {
        when (k.style) {
            StoryStyle.VINTAGE -> {
                val r = Random(tMs / 42)
                // flicker
                c.drawColor(k.alpha(Color.BLACK, 0.02f + r.nextFloat() * 0.07f))
                // scratches
                k.stroke.strokeCap = Paint.Cap.BUTT
                repeat(r.nextInt(3)) {
                    val x = r.nextFloat() * k.w
                    k.stroke.color = k.alpha(if (r.nextBoolean()) Color.WHITE else Color.BLACK, 0.25f + r.nextFloat() * 0.3f)
                    k.stroke.strokeWidth = (0.8f + r.nextFloat() * 1.6f) * k.u
                    c.drawLine(x, 0f, x + (r.nextFloat() - 0.5f) * 20f * k.u, k.h.toFloat(), k.stroke)
                }
                // dust
                repeat(6 + r.nextInt(10)) {
                    k.fill.color = k.alpha(if (r.nextBoolean()) Color.BLACK else 0xFFF0E6D0.toInt(), 0.5f)
                    c.drawCircle(r.nextFloat() * k.w, r.nextFloat() * k.h, (0.8f + r.nextFloat() * 2.5f) * k.u, k.fill)
                }
                k.stroke.strokeCap = Paint.Cap.ROUND
                // oval vignette + rounded film gate
                k.fill.shader = RadialGradient(k.cx, k.cy, max(k.w, k.h) * 0.62f, intArrayOf(0, 0, 0xE0000000.toInt()), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
                val gate = Path().apply { addRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), Path.Direction.CW); addRoundRect(RectF(14f * k.u, 14f * k.u, k.w - 14f * k.u, k.h - 14f * k.u), 40f * k.u, 40f * k.u, Path.Direction.CCW) }
                k.fill.color = 0xFF0B0805.toInt(); c.drawPath(gate, k.fill)
            }
            StoryStyle.NOIR -> {
                val r = Random(tMs / 33)
                k.stroke.strokeCap = Paint.Cap.ROUND
                k.stroke.color = k.alpha(0xFFB8C8D8.toInt(), 0.22f); k.stroke.strokeWidth = 1.4f * k.u
                repeat(70) {
                    val x = r.nextFloat() * k.w * 1.2f; val y = r.nextFloat() * k.h
                    c.drawLine(x, y, x - 10f * k.u, y + 34f * k.u, k.stroke)
                }
                k.fill.shader = RadialGradient(k.cx, k.cy, max(k.w, k.h) * 0.6f, intArrayOf(0, 0xD0000000.toInt()), floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
            }
            StoryStyle.DOCUMENTARY -> {
                k.stroke.color = k.alpha(0xFFD9B25F.toInt(), 0.55f); k.stroke.strokeWidth = 2f * k.u
                c.drawRect(18f * k.u, 18f * k.u, k.w - 18f * k.u, k.h - 18f * k.u, k.stroke)
            }
            StoryStyle.COMIC -> {
                k.stroke.color = Color.BLACK; k.stroke.strokeWidth = 10f * k.u
                c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.stroke)
            }
            else -> {}
        }
    }

    /** Vintage film gate weave: tiny frame jitter. */
    fun weave(k: Kit, c: Canvas, tMs: Long) {
        if (k.style != StoryStyle.VINTAGE) return
        val r = Random(tMs / 42 + 5)
        c.translate((r.nextFloat() - 0.5f) * 2.5f * k.u, (r.nextFloat() - 0.5f) * 3.5f * k.u)
    }
}
