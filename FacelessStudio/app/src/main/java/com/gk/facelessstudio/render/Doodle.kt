package com.gk.facelessstudio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.gk.facelessstudio.engine.ScriptAnalyzer
import com.gk.facelessstudio.model.Scene
import com.gk.facelessstudio.model.SceneType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Whiteboard doodle style: on a white board each picture is first sketched in pencil, then coloured in,
 * and the text is written in handwriting — like InstaDoodle / whiteboard explainer videos (no hand graphic).
 */
object Doodle {
    const val INK = 0xFF1B1B1B.toInt()
    const val RED = 0xFFD8342C.toInt()
    const val BLUE = 0xFF1F5FC8.toInt()
    private const val PAPER = 0xFFFBFAF5.toInt()

    fun isDoodle(t: SceneType) = t.name.startsWith("DOODLE_")

    fun draw(k: Kit, c: Canvas, s: Scene, t: Float, d: Float): Boolean {
        if (!isDoodle(s.type)) return false
        paper(k, c, s)
        when (s.type) {
            SceneType.DOODLE_CENTER -> center(k, c, s, t, d)
            SceneType.DOODLE_LEFT -> side(k, c, s, t, d, pictureLeft = true)
            SceneType.DOODLE_RIGHT -> side(k, c, s, t, d, pictureLeft = false)
            SceneType.DOODLE_TOP -> top(k, c, s, t, d)
            SceneType.DOODLE_NUMBER -> number(k, c, s, t, d)
            else -> handText(k, c, s, t, d)
        }
        return true
    }

    // ------------------------------------------------------------------ board
    @Suppress("UNUSED_PARAMETER")
    private fun paper(k: Kit, c: Canvas, s: Scene) {
        c.drawColor(PAPER)
        k.particles(c, 0f, 5, 140, 0xFFB9B2A4.toInt())
        k.fill.shader = RadialGradient(k.cx, k.cy, max(k.w, k.h) * 0.75f, intArrayOf(0, 0x22000000), floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
    }

    // ------------------------------------------------------------------ picture: sketch, then colour
    /** Zig-zag brush reveal like a hand shading row by row. Returns the clip path and the brush tip. */
    private fun zigzag(r: RectF, p: Float, rows: Int): Pair<Path, Pair<Float, Float>> {
        val path = Path()
        val rh = r.height() / rows
        val total = p * rows
        val done = total.toInt().coerceAtMost(rows)
        if (done > 0) path.addRect(r.left, r.top, r.right, r.top + done * rh, Path.Direction.CW)
        var tip = Pair(r.left, r.top)
        if (done < rows) {
            val f = total - done
            val y0 = r.top + done * rh
            val ltr = done % 2 == 0
            val xEnd = if (ltr) r.left + r.width() * f else r.right - r.width() * f
            if (ltr) path.addRect(r.left, y0, xEnd, y0 + rh, Path.Direction.CW) else path.addRect(xEnd, y0, r.right, y0 + rh, Path.Direction.CW)
            tip = Pair(xEnd, y0 + rh * (0.5f + 0.35f * sin(f * 40f)))
        } else tip = Pair(r.right, r.bottom)
        return Pair(path, tip)
    }

    /** Draws the scene picture into [r]; drawing happens between [start] and start+[dur]. Returns the hand tip while drawing. */
    private fun picture(k: Kit, c: Canvas, s: Scene, r: RectF, t: Float, start: Float, dur: Float): Pair<Float, Float>? {
        val color = k.images.get(s.imageIndex) ?: return null
        val sketch = k.images.sketch(s.imageIndex) ?: color
        val p = k.seg(t, start, start + dur)
        if (p <= 0f) return null
        val sp = k.clamp01(p / 0.6f); val cp = k.clamp01((p - 0.6f) / 0.4f)
        val zoom = 1.02f + 0.03f * k.clamp01((t - start - dur) / 4f)
        var tip: Pair<Float, Float>? = null
        // pencil sketch
        val (clipS, tipS) = zigzag(r, sp, 7)
        c.save(); c.clipPath(clipS); drawImg(k, c, sketch, r, zoom); c.restore()
        if (sp < 1f) tip = tipS
        // colouring in
        if (cp > 0f) {
            val (clipC, tipC) = zigzag(r, cp, 5)
            c.save(); c.clipPath(clipC); drawImg(k, c, color, r, zoom); c.restore()
            if (cp < 1f) tip = tipC
        }
        // hand-drawn frame once finished
        if (p >= 1f) wobblyRect(k, c, r, k.seg(t, start + dur, start + dur + 0.6f), s.variant)
        return tip
    }

    private fun drawImg(k: Kit, c: Canvas, b: Bitmap, r: RectF, zoom: Float) {
        // the colour filter of the main paint is not wanted here
        k.cover(c, b, r, zoom)
    }

    // ------------------------------------------------------------------ handwriting
    /** Writes [text] by hand from [start]; returns the pen tip while writing. */
    private fun write(k: Kit, c: Canvas, s: Scene, text: String, x: Float, y: Float, maxW: Float, size: Float, t: Float, start: Float,
                      cps: Float, align: Paint.Align = Paint.Align.LEFT, color: Int = INK): Pair<Float, Float>? {
        if (text.isBlank()) return null
        k.setText(k.fonts.hand, size, color, text)
        val lines = k.wrap(text, maxW).take(5)
        val total = lines.sumOf { it.length }
        val shown = ((t - start) * cps).toInt().coerceIn(0, total)
        if (shown <= 0) return if (t >= start) Pair(x, y) else null
        val lh = size * 1.25f
        val em = s.emphasis.map { it.lowercase() }.toSet()
        var left = shown
        var tip: Pair<Float, Float>? = null
        lines.forEachIndexed { i, l ->
            if (left <= 0) return@forEachIndexed
            val part = l.take(left)
            val lw = k.text.measureText(l)
            val lx = when (align) { Paint.Align.CENTER -> x - lw / 2; Paint.Align.RIGHT -> x - lw; else -> x }
            k.drawRich(c, part, lx, y + i * lh, em, color, RED)
            if (left < l.length || (i == lines.lastIndex && shown < total)) tip = Pair(lx + k.text.measureText(part), y + i * lh - size * 0.2f)
            left -= l.length
        }
        // underline the first emphasised word once the line is written
        if (shown >= total && em.isNotEmpty()) {
            val l0 = lines.first()
            val lw = k.text.measureText(l0)
            val lx = when (align) { Paint.Align.CENTER -> x - lw / 2; Paint.Align.RIGHT -> x - lw; else -> x }
            scribbleLine(k, c, lx, y + size * 0.25f, lx + lw, y + size * 0.3f, k.seg(t, start + total / cps, start + total / cps + 0.5f), RED, s.variant)
        }
        return tip
    }

    private fun duration(text: String, cps: Float) = text.length / cps

    private fun cps(text: String, budget: Float) = max(14f, text.length / max(0.6f, budget))

    // ------------------------------------------------------------------ layouts
    private fun center(k: Kit, c: Canvas, s: Scene, t: Float, d: Float): Pair<Float, Float>? {
        val draw = min(3.2f, d * 0.55f)
        val pw = if (k.portrait) k.w * 0.86f else k.w * 0.6f
        val ph = pw * 9f / 16f
        val r = RectF(k.cx - pw / 2, if (k.portrait) k.h * 0.14f else k.h * 0.07f, k.cx + pw / 2, 0f).also { it.bottom = it.top + ph }
        val tipP = picture(k, c, s, r, t, 0.1f, draw)
        val cap = s.title.ifBlank { ScriptAnalyzer.phrase(s.text, 12) }
        val speed = cps(cap, d - draw - 0.4f)
        val tipW = write(k, c, s, cap, k.cx, r.bottom + 70f * k.u, k.w * 0.86f, (if (k.portrait) 44f else 40f) * k.u, t, 0.15f + draw, speed, Paint.Align.CENTER)
        return tipP ?: tipW
    }

    private fun side(k: Kit, c: Canvas, s: Scene, t: Float, d: Float, pictureLeft: Boolean): Pair<Float, Float>? {
        if (k.portrait) return top(k, c, s, t, d)
        val draw = min(3.0f, d * 0.5f)
        val pw = k.w * 0.5f; val ph = pw * 9f / 16f
        val px = if (pictureLeft) k.w * 0.04f else k.w * 0.46f
        val r = RectF(px, k.cy - ph / 2 - 10f * k.u, px + pw, k.cy + ph / 2 - 10f * k.u)
        val tipP = picture(k, c, s, r, t, 0.1f, draw)
        val tx = if (pictureLeft) k.w * 0.58f else k.w * 0.05f
        val text = s.text
        val speed = cps(text, d - draw - 0.4f)
        val tipW = write(k, c, s, text, tx, k.h * 0.3f, k.w * 0.38f, 40f * k.u, t, 0.15f + draw, speed)
        // doodle arrow from the text towards the picture
        val ap = k.seg(t, 0.15f + draw + duration(text, speed), 0.15f + draw + duration(text, speed) + 0.6f)
        if (ap > 0f) {
            val ax = if (pictureLeft) k.w * 0.6f else k.w * 0.4f
            arrow(k, c, ax, k.h * 0.78f, if (pictureLeft) r.right - 20f * k.u else r.left + 20f * k.u, r.bottom - 10f * k.u, ap, BLUE, s.variant)
        }
        return tipP ?: tipW
    }

    private fun top(k: Kit, c: Canvas, s: Scene, t: Float, d: Float): Pair<Float, Float>? {
        val title = s.title.ifBlank { ScriptAnalyzer.phrase(s.text, 6) }
        val tSpeed = cps(title, min(1.4f, d * 0.25f))
        val tipT = write(k, c, s, title, k.cx, k.h * 0.13f, k.w * 0.86f, (if (k.portrait) 50f else 46f) * k.u, t, 0.1f, tSpeed, Paint.Align.CENTER, BLUE)
        val start = 0.2f + duration(title, tSpeed)
        val draw = min(3.0f, (d - start) * 0.8f)
        val pw = if (k.portrait) k.w * 0.86f else k.w * 0.58f
        val ph = pw * 9f / 16f
        val r = RectF(k.cx - pw / 2, k.h * 0.2f, k.cx + pw / 2, k.h * 0.2f + ph)
        if (r.bottom > k.h - 30f * k.u) r.offset(0f, k.h - 30f * k.u - r.bottom)
        val tipP = picture(k, c, s, r, t, start, draw)
        return tipT ?: tipP
    }

    private fun handText(k: Kit, c: Canvas, s: Scene, t: Float, d: Float): Pair<Float, Float>? {
        val text = s.text
        val size = (if (k.portrait) 56f else 50f) * k.u
        k.setText(k.fonts.hand, size, INK, text)
        val lines = k.wrap(text, k.w * 0.8f).take(5)
        val y0 = k.cy - (lines.size - 1) * size * 0.62f + size * 0.3f
        val speed = cps(text, d * 0.7f)
        val tip = write(k, c, s, text, k.cx, y0, k.w * 0.8f, size, t, 0.1f, speed, Paint.Align.CENTER)
        // a few doodles around once written
        val after = 0.1f + duration(text, speed)
        val r = Random(s.variant)
        star(k, c, k.w * (0.08f + r.nextFloat() * 0.06f), k.h * 0.2f, 24f * k.u, k.seg(t, after, after + 0.4f), RED)
        star(k, c, k.w * (0.86f + r.nextFloat() * 0.05f), k.h * 0.78f, 20f * k.u, k.seg(t, after + 0.2f, after + 0.6f), BLUE)
        return tip
    }

    private fun number(k: Kit, c: Canvas, s: Scene, t: Float, d: Float): Pair<Float, Float>? {
        val big = s.title.ifBlank { ScriptAnalyzer.strongWords(s.text, 1).firstOrNull() ?: "!" }
        val size = min(220f * k.u, k.fitSize(big, k.fonts.hand, k.w * 0.7f, 220f * k.u))
        val speed = cps(big, 0.9f)
        val tip = write(k, c, s, big, k.cx, k.cy + size * 0.1f, k.w * 0.9f, size, t, 0.1f, speed, Paint.Align.CENTER, RED)
        val done = 0.1f + duration(big, speed)
        k.setText(k.fonts.hand, size, RED, big)
        val bw = k.text.measureText(big)
        scribbleCircle(k, c, RectF(k.cx - bw / 2 - 40f * k.u, k.cy - size * 0.75f, k.cx + bw / 2 + 40f * k.u, k.cy + size * 0.35f), k.seg(t, done, done + 0.7f), INK, s.variant)
        val label = s.lines.firstOrNull() ?: ScriptAnalyzer.phrase(s.text, 9)
        val tipL = write(k, c, s, label, k.cx, k.cy + size * 0.35f + 70f * k.u, k.w * 0.86f, 40f * k.u, t, done + 0.6f, cps(label, d - done - 1f), Paint.Align.CENTER)
        return tip ?: tipL
    }

    // ------------------------------------------------------------------ marker doodles
    private fun wobbly(path: Path, p: Float, k: Kit, c: Canvas, color: Int, width: Float) {
        val pm = PathMeasure(path, false)
        val seg = Path(); pm.getSegment(0f, pm.length * k.clamp01(p), seg, true)
        k.stroke.color = color; k.stroke.strokeWidth = width
        c.drawPath(seg, k.stroke)
    }

    private fun wobblyRect(k: Kit, c: Canvas, r: RectF, p: Float, seed: Int) {
        if (p <= 0f) return
        val rnd = Random(seed); val j = { (rnd.nextFloat() - 0.5f) * 6f * k.u }
        val path = Path().apply {
            moveTo(r.left + j(), r.top + j()); lineTo(r.right + j(), r.top + j()); lineTo(r.right + j(), r.bottom + j())
            lineTo(r.left + j(), r.bottom + j()); lineTo(r.left + j(), r.top + j() + 3f * k.u)
        }
        wobbly(path, p, k, c, INK, 4f * k.u)
    }

    private fun scribbleLine(k: Kit, c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, p: Float, color: Int, seed: Int) {
        if (p <= 0f) return
        val rnd = Random(seed)
        val path = Path().apply { moveTo(x0, y0); quadTo((x0 + x1) / 2, (y0 + y1) / 2 + (rnd.nextFloat() - 0.3f) * 14f * k.u, x1, y1) }
        wobbly(path, p, k, c, color, 5f * k.u)
    }

    private fun scribbleCircle(k: Kit, c: Canvas, r: RectF, p: Float, color: Int, seed: Int) {
        if (p <= 0f) return
        val rnd = Random(seed)
        val path = Path()
        val steps = 60
        for (i in 0..steps) {
            val a = (-PI / 2 + i * 2.25 * PI / steps).toFloat()
            val wob = 1f + (rnd.nextFloat() - 0.5f) * 0.04f + i * 0.0015f
            val x = r.centerX() + cos(a) * r.width() / 2 * wob; val y = r.centerY() + sin(a) * r.height() / 2 * wob
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        wobbly(path, p, k, c, color, 5f * k.u)
    }

    private fun arrow(k: Kit, c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, p: Float, color: Int, seed: Int) {
        val rnd = Random(seed)
        val path = Path().apply { moveTo(x0, y0); quadTo((x0 + x1) / 2 + (rnd.nextFloat() - 0.5f) * 60f * k.u, max(y0, y1) + 50f * k.u, x1, y1) }
        wobbly(path, p, k, c, color, 5f * k.u)
        if (p >= 1f) {
            val pm = PathMeasure(path, false); val pos = FloatArray(2); val tan = FloatArray(2)
            pm.getPosTan(pm.length, pos, tan)
            val ang = kotlin.math.atan2(tan[1], tan[0])
            k.stroke.color = color
            for (s in listOf(-1f, 1f)) {
                val a = ang + PI.toFloat() + s * 0.5f
                c.drawLine(pos[0], pos[1], pos[0] + cos(a) * 26f * k.u, pos[1] + sin(a) * 26f * k.u, k.stroke)
            }
        }
    }

    private fun star(k: Kit, c: Canvas, x: Float, y: Float, r: Float, p: Float, color: Int) {
        if (p <= 0f) return
        val path = Path()
        for (i in 0..10) {
            val a = (-PI / 2 + i * PI / 5).toFloat()
            val rad = if (i % 2 == 0) r else r * 0.45f
            val px = x + cos(a) * rad; val py = y + sin(a) * rad
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        wobbly(path, p, k, c, color, 4f * k.u)
    }

}
