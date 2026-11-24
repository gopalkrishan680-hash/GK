package com.gk.facelessstudio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
 * Whiteboard doodle style. A board holds 1–3 narration lines; for every line its picture is drawn naturally —
 * outlines grow along the sketch's own lines, then colour is painted in one of several orders — and the line is
 * handwritten beneath it. No hand graphic.
 */
object Doodle {
    const val INK = 0xFF1B1B1B.toInt()
    const val RED = 0xFFD8342C.toInt()
    const val BLUE = 0xFF1F5FC8.toInt()
    private const val PAPER = 0xFFFBFAF5.toInt()

    fun isDoodle(t: SceneType) = t.name.startsWith("DOODLE_")

    fun draw(k: Kit, c: Canvas, s: Scene, t: Float, d: Float): Boolean {
        if (!isDoodle(s.type)) return false
        paper(k, c)
        when (s.type) {
            SceneType.DOODLE_CENTER -> center(k, c, s, t, d)
            SceneType.DOODLE_LEFT -> side(k, c, s, t, d, pictureLeft = true)
            SceneType.DOODLE_RIGHT -> side(k, c, s, t, d, pictureLeft = false)
            SceneType.DOODLE_TOP -> top(k, c, s, t, d)
            SceneType.DOODLE_NUMBER -> number(k, c, s, t, d)
            SceneType.DOODLE_DUO, SceneType.DOODLE_TRIO -> board(k, c, s, t, d)
            else -> handText(k, c, s, t, d)
        }
        return true
    }

    private fun paper(k: Kit, c: Canvas) {
        c.drawColor(PAPER)
        k.particles(c, 0f, 5, 140, 0xFFB9B2A4.toInt())
        k.fill.shader = RadialGradient(k.cx, k.cy, max(k.w, k.h) * 0.75f, intArrayOf(0, 0x22000000), floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
    }

    // ------------------------------------------------------------------ natural picture drawing
    private val dstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    /** Per-thread reusable mask. */
    private val maskPx = ThreadLocal.withInitial { IntArray(DrawOrder.W * DrawOrder.H) }
    private val maskBmp = ThreadLocal.withInitial { Bitmap.createBitmap(DrawOrder.W, DrawOrder.H, Bitmap.Config.ARGB_8888) }

    private fun mask(order: FloatArray, threshold: Float, soft: Float): Bitmap {
        val px = maskPx.get()!!
        for (i in px.indices) {
            val a = ((threshold - order[i]) / soft).coerceIn(0f, 1f)
            px[i] = ((a * 255).toInt() shl 24) or 0xFFFFFF
        }
        val b = maskBmp.get()!!
        b.setPixels(px, 0, DrawOrder.W, 0, 0, DrawOrder.W, DrawOrder.H)
        return b
    }

    private fun layer(k: Kit, c: Canvas, img: Bitmap, r: RectF, order: FloatArray, p: Float) {
        if (p <= 0f) return
        if (p >= 1f) { k.cover(c, img, r, 1.0f); return }
        val save = c.saveLayer(r, null)
        k.cover(c, img, r, 1.0f)
        c.drawBitmap(mask(order, p * 1.08f, 0.07f), null, r, dstIn)
        c.restoreToCount(save)
    }

    /** Draws picture [imageIndex] into [r] between [start] and start+[dur]: outlines first, then colour. */
    private fun picture(k: Kit, c: Canvas, imageIndex: Int, seed: Int, r: RectF, t: Float, start: Float, dur: Float) {
        val color = k.images.get(imageIndex) ?: return
        val sketch = k.images.sketch(imageIndex) ?: color
        val p = k.seg(t, start, start + dur)
        if (p <= 0f) return
        val order = DrawOrder.of(sketch, "${k.project.images.getOrNull(imageIndex)}", seed)
        // outline phase is a bit slower at the start (the pen finds its way), colour washes in after
        val sp = k.clamp01(p / 0.58f).let { it * (0.6f + 0.4f * it) }
        val cp = k.inOutCubic(k.clamp01((p - 0.5f) / 0.5f))
        layer(k, c, sketch, r, order.outline, sp)
        layer(k, c, color, r, order.colour, cp)
        if (p >= 1f) wobblyRect(k, c, r, k.seg(t, start + dur, start + dur + 0.6f), seed)
    }

    // ------------------------------------------------------------------ handwriting
    private fun write(k: Kit, c: Canvas, s: Scene, text: String, x: Float, y: Float, maxW: Float, size: Float, t: Float, start: Float,
                      cps: Float, align: Paint.Align = Paint.Align.LEFT, color: Int = INK, maxLines: Int = 5) {
        if (text.isBlank() || t < start) return
        k.setText(k.fonts.hand, size, color, text)
        val lines = k.wrap(text, maxW).take(maxLines)
        val total = lines.sumOf { it.length }
        val shown = ((t - start) * cps).toInt().coerceIn(0, total)
        if (shown <= 0) return
        val lh = size * 1.25f
        val em = s.emphasis.map { it.lowercase() }.toSet()
        var left = shown
        lines.forEachIndexed { i, l ->
            if (left <= 0) return@forEachIndexed
            val lw = k.text.measureText(l)
            val lx = when (align) { Paint.Align.CENTER -> x - lw / 2; Paint.Align.RIGHT -> x - lw; else -> x }
            k.drawRich(c, l.take(left), lx, y + i * lh, em, color, RED)
            left -= l.length
        }
        if (shown >= total && em.isNotEmpty()) {
            val l0 = lines.first(); val lw = k.text.measureText(l0)
            val lx = when (align) { Paint.Align.CENTER -> x - lw / 2; Paint.Align.RIGHT -> x - lw; else -> x }
            scribbleLine(k, c, lx, y + size * 0.25f, lx + lw, y + size * 0.3f, k.seg(t, start + total / cps, start + total / cps + 0.5f), RED, s.variant)
        }
    }

    private fun duration(text: String, cps: Float) = text.length / cps
    /** Writing speed so the text is done in [budget] seconds, never frantic, never sluggish. */
    private fun cps(text: String, budget: Float) = (text.length / max(0.8f, budget)).coerceIn(9f, 28f)

    /** Drawing time for one picture: unhurried, but finished well before the line ends. */
    private fun drawTime(lineSec: Float) = (lineSec * 0.62f).coerceIn(1.6f, 5.5f)

    // ------------------------------------------------------------------ single-line layouts
    private fun center(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val draw = drawTime(d)
        val pw = if (k.portrait) k.w * 0.86f else k.w * 0.6f
        val ph = pw * 9f / 16f
        val r = RectF(k.cx - pw / 2, if (k.portrait) k.h * 0.14f else k.h * 0.07f, k.cx + pw / 2, 0f).also { it.bottom = it.top + ph }
        picture(k, c, s.imageIndex, s.variant, r, t, 0.15f, draw)
        val cap = s.title.ifBlank { ScriptAnalyzer.phrase(s.text, 12) }
        write(k, c, s, cap, k.cx, r.bottom + 70f * k.u, k.w * 0.86f, (if (k.portrait) 44f else 40f) * k.u, t, 0.15f + draw * 0.7f,
            cps(cap, d - draw * 0.7f - 0.6f), Paint.Align.CENTER)
    }

    private fun side(k: Kit, c: Canvas, s: Scene, t: Float, d: Float, pictureLeft: Boolean) {
        if (k.portrait) return top(k, c, s, t, d)
        val draw = drawTime(d)
        val pw = k.w * 0.5f; val ph = pw * 9f / 16f
        val px = if (pictureLeft) k.w * 0.04f else k.w * 0.46f
        val r = RectF(px, k.cy - ph / 2 - 10f * k.u, px + pw, k.cy + ph / 2 - 10f * k.u)
        picture(k, c, s.imageIndex, s.variant, r, t, 0.15f, draw)
        val tx = if (pictureLeft) k.w * 0.58f else k.w * 0.05f
        val speed = cps(s.text, d - draw * 0.6f - 0.6f)
        val ws = 0.15f + draw * 0.6f
        write(k, c, s, s.text, tx, k.h * 0.3f, k.w * 0.38f, 40f * k.u, t, ws, speed)
        val ap = k.seg(t, ws + duration(s.text, speed), ws + duration(s.text, speed) + 0.6f)
        if (ap > 0f) {
            val ax = if (pictureLeft) k.w * 0.6f else k.w * 0.4f
            arrow(k, c, ax, k.h * 0.8f, if (pictureLeft) r.right - 20f * k.u else r.left + 20f * k.u, r.bottom - 10f * k.u, ap, BLUE, s.variant)
        }
    }

    private fun top(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val title = s.title.ifBlank { ScriptAnalyzer.phrase(s.text, 6) }
        val tSpeed = cps(title, min(1.6f, d * 0.25f))
        write(k, c, s, title, k.cx, k.h * 0.13f, k.w * 0.86f, (if (k.portrait) 50f else 46f) * k.u, t, 0.1f, tSpeed, Paint.Align.CENTER, BLUE, 2)
        val start = 0.2f + duration(title, tSpeed) * 0.6f
        val draw = drawTime(d - start)
        val pw = if (k.portrait) k.w * 0.86f else k.w * 0.58f
        val ph = pw * 9f / 16f
        val r = RectF(k.cx - pw / 2, k.h * 0.2f, k.cx + pw / 2, k.h * 0.2f + ph)
        if (r.bottom > k.h - 30f * k.u) r.offset(0f, k.h - 30f * k.u - r.bottom)
        picture(k, c, s.imageIndex, s.variant, r, t, start, draw)
    }

    private fun handText(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        // several lines on one board are written one after another, each when it is spoken
        val texts = s.lines.ifEmpty { listOf(s.text) }
        val starts = s.lineStarts.takeIf { it.size == texts.size } ?: texts.indices.map { it / texts.size.toFloat() }
        val size = (if (k.portrait) 50f else 46f) * k.u
        var y = k.h * (if (texts.size == 1) 0.42f else 0.2f)
        texts.forEachIndexed { i, text ->
            val st = starts[i] * d + 0.1f
            val en = if (i + 1 < texts.size) starts[i + 1] * d else d
            k.setText(k.fonts.hand, size, INK, text)
            val n = k.wrap(text, k.w * 0.8f).take(4).size
            write(k, c, s, text, k.cx, y, k.w * 0.8f, size, t, st, cps(text, (en - st) * 0.75f), Paint.Align.CENTER, if (i % 2 == 1) BLUE else INK, 4)
            y += n * size * 1.25f + size * 0.6f
        }
        val after = d * 0.8f
        val r = Random(s.variant)
        star(k, c, k.w * (0.08f + r.nextFloat() * 0.06f), k.h * 0.14f, 24f * k.u, k.seg(t, after, after + 0.4f), RED)
        star(k, c, k.w * (0.86f + r.nextFloat() * 0.05f), k.h * 0.84f, 20f * k.u, k.seg(t, after + 0.2f, after + 0.6f), BLUE)
    }

    private fun number(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val big = s.title.ifBlank { ScriptAnalyzer.strongWords(s.text, 1).firstOrNull() ?: "!" }
        val size = min(220f * k.u, k.fitSize(big, k.fonts.hand, k.w * 0.7f, 220f * k.u))
        val speed = cps(big, 1.2f)
        write(k, c, s, big, k.cx, k.cy + size * 0.1f, k.w * 0.9f, size, t, 0.15f, speed, Paint.Align.CENTER, RED)
        val done = 0.15f + duration(big, speed)
        k.setText(k.fonts.hand, size, RED, big)
        val bw = k.text.measureText(big)
        scribbleCircle(k, c, RectF(k.cx - bw / 2 - 40f * k.u, k.cy - size * 0.75f, k.cx + bw / 2 + 40f * k.u, k.cy + size * 0.35f), k.seg(t, done, done + 0.8f), INK, s.variant)
        val label = s.lines.firstOrNull() ?: ScriptAnalyzer.phrase(s.text, 9)
        write(k, c, s, label, k.cx, k.cy + size * 0.35f + 70f * k.u, k.w * 0.86f, 40f * k.u, t, done + 0.7f, cps(label, d - done - 1.2f), Paint.Align.CENTER)
    }

    // ------------------------------------------------------------------ boards with 2-3 lines
    /** Each line gets its own picture + handwritten caption, drawn when that line is spoken. */
    private fun board(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val texts = s.lines.ifEmpty { listOf(s.text) }
        val n = texts.size
        val imgs = s.images.ifEmpty { List(n) { s.imageIndex } }
        val starts = s.lineStarts.takeIf { it.size == n } ?: texts.indices.map { it / n.toFloat() }
        val slots = slots(k, n, s.variant)
        texts.forEachIndexed { i, text ->
            val st = starts[i] * d + 0.1f
            val en = if (i + 1 < n) starts[i + 1] * d else d
            val (r, capY) = slots[i]
            val img = imgs.getOrElse(i) { -1 }
            val draw = drawTime(en - st)
            val size = (if (n == 3 && !k.portrait) 28f else 34f) * k.u
            if (img >= 0 && k.images.get(img) != null) {
                picture(k, c, img, s.variant + i * 7919, r, t, st, draw)
                val cap = ScriptAnalyzer.phrase(text, if (n == 3) 10 else 15)
                write(k, c, s, cap, r.centerX(), capY, r.width() + 20f * k.u, size, t, st + draw * 0.65f,
                    cps(cap, (en - st - draw * 0.65f) * 0.85f), Paint.Align.CENTER, if (i % 2 == 1) BLUE else INK, 2)
            } else {
                // a line without a picture is written big in its slot
                write(k, c, s, text, r.centerX(), r.centerY(), r.width(), size * 1.3f, t, st, cps(text, (en - st) * 0.8f), Paint.Align.CENTER, INK, 4)
            }
            // arrow from this picture to the next one, once both have been spoken
            if (i + 1 < n && !k.portrait) {
                val next = slots[i + 1].first
                val ap = k.seg(t, starts[i + 1] * d - 0.1f, starts[i + 1] * d + 0.5f)
                if (ap > 0f && n == 2) arrow(k, c, r.right - 30f * k.u, r.top - 16f * k.u, next.left + 30f * k.u, next.top - 16f * k.u, ap, RED, s.variant + i)
            }
        }
    }

    /** Picture rectangles (16:9) and caption baselines for 2 or 3 lines; arrangement varies per board. */
    private fun slots(k: Kit, n: Int, variant: Int): List<Pair<RectF, Float>> {
        val out = ArrayList<Pair<RectF, Float>>()
        if (k.portrait) {
            val pw = k.w * 0.8f; val ph = pw * 9f / 16f
            val gap = (k.h - n * (ph + 90f * k.u)) / (n + 1)
            for (i in 0 until n) {
                val top = gap + i * (ph + 90f * k.u + gap)
                val r = RectF(k.cx - pw / 2, top, k.cx + pw / 2, top + ph)
                out += Pair(r, r.bottom + 50f * k.u)
            }
            return out
        }
        if (n == 2) {
            val pw = k.w * 0.42f; val ph = pw * 9f / 16f
            val stagger = if (variant % 2 == 0) 50f * k.u else -50f * k.u
            for (i in 0 until 2) {
                val left = if (i == 0) k.w * 0.05f else k.w * 0.53f
                val top = k.cy - ph / 2 - 30f * k.u + (if (i == 0) -stagger else stagger) / 2
                val r = RectF(left, top, left + pw, top + ph)
                out += Pair(r, r.bottom + 50f * k.u)
            }
        } else {
            if (variant % 2 == 0) { // one big picture on the left, two stacked on the right
                val bw = k.w * 0.5f; val bh = bw * 9f / 16f
                val big = RectF(k.w * 0.04f, k.cy - bh / 2 - 30f * k.u, k.w * 0.04f + bw, k.cy + bh / 2 - 30f * k.u)
                out += Pair(big, big.bottom + 48f * k.u)
                val sw = k.w * 0.34f; val sh = sw * 9f / 16f
                for (j in 0 until 2) {
                    val top = k.h * 0.06f + j * (sh + 76f * k.u)
                    val r = RectF(k.w * 0.61f, top, k.w * 0.61f + sw, top + sh)
                    out += Pair(r, r.bottom + 38f * k.u)
                }
            } else { // three in a row
                val pw = k.w * 0.29f; val ph = pw * 9f / 16f
                for (i in 0 until 3) {
                    val left = k.w * (0.04f + i * 0.32f)
                    val top = k.cy - ph / 2 - 40f * k.u + (if (i == 1) 30f else 0f) * k.u
                    val r = RectF(left, top, left + pw, top + ph)
                    out += Pair(r, r.bottom + 44f * k.u)
                }
            }
        }
        return out
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
        if (p <= 0f) return
        val rnd = Random(seed)
        val path = Path().apply { moveTo(x0, y0); quadTo((x0 + x1) / 2 + (rnd.nextFloat() - 0.5f) * 60f * k.u, min(y0, y1) - 50f * k.u, x1, y1) }
        wobbly(path, p, k, c, color, 5f * k.u)
        if (p >= 1f) {
            val pm = PathMeasure(path, false); val pos = FloatArray(2); val tan = FloatArray(2)
            pm.getPosTan(pm.length, pos, tan)
            val ang = kotlin.math.atan2(tan[1], tan[0])
            k.stroke.color = color
            for (sg in listOf(-1f, 1f)) {
                val a = ang + PI.toFloat() + sg * 0.5f
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
