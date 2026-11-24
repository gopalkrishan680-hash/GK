package com.gk.facelessstudio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
 * Whiteboard doodle style. Every board is a combination of
 *   layout × frame × draw method × text style × doodle × camera × arrangement
 * chosen by the variety planner, so boards don't look alike. Pictures are drawn naturally (outlines grow along
 * the sketch's own lines, colour is painted in varying orders); lines are handwritten. No hand graphic.
 */
object Doodle {
    const val INK = 0xFF1B1B1B.toInt()
    const val RED = 0xFFD8342C.toInt()
    const val BLUE = 0xFF1F5FC8.toInt()
    const val GREEN = 0xFF2E8B3E.toInt()
    private const val PAPER = 0xFFFBFAF5.toInt()
    private const val STICKY = 0xFFFFE97A.toInt()
    private const val HIGHLIGHT = 0xFFFFE45C.toInt()

    // frames
    private const val F_BOX = 0; private const val F_CUTOUT = 1; private const val F_POLAROID = 2; private const val F_CIRCLE = 3; private const val F_TORN = 4
    // draw methods
    private const val D_TRACE = 0; private const val D_PENCIL = 1; private const val D_BRUSH = 2; private const val D_INK = 3; private const val D_DISSOLVE = 4
    // text styles
    private const val T_PLAIN = 0; private const val T_HIGHLIGHT = 1; private const val T_BOX = 2; private const val T_BUBBLE = 3; private const val T_STICKY = 4
    // cameras
    private const val C_STILL = 0; private const val C_FOLLOW = 1; private const val C_PULLBACK = 2; private const val C_DRIFT = 3

    class Look(val frame: Int, val draw: Int, val text: Int, val deco: Int, val cam: Int, val arr: Int)

    private fun look(s: Scene): Look {
        val l = s.look
        val r = Random(s.variant)
        return Look(l.getOrElse(0) { r.nextInt(5) }, l.getOrElse(1) { r.nextInt(5) }, l.getOrElse(2) { r.nextInt(5) },
            l.getOrElse(3) { r.nextInt(7) }, l.getOrElse(4) { r.nextInt(4) }, l.getOrElse(5) { r.nextInt(3) })
    }

    fun isDoodle(t: SceneType) = t.name.startsWith("DOODLE_")

    /** One thing that appears on the board: a picture and/or a piece of handwriting, with its own timing. */
    private class Item(
        val rect: RectF?, val image: Int, val text: String, val tx: Float, val ty: Float, val tw: Float, val size: Float,
        val align: Paint.Align, val color: Int, val start: Float, val end: Float, val textStart: Float, val maxLines: Int = 4,
        val boxed: Boolean = true,
    )

    fun draw(k: Kit, c: Canvas, s: Scene, t: Float, d: Float): Boolean {
        if (!isDoodle(s.type)) return false
        val lk = look(s)
        c.drawColor(PAPER)
        k.particles(c, 0f, 5, 140, 0xFFB9B2A4.toInt())
        if (s.type == SceneType.DOODLE_NUMBER) { number(k, c, s, lk, t, d); vignette(k, c); return true }
        val items = items(k, s, lk, d)
        c.save()
        camera(k, c, lk, items, t)
        items.forEachIndexed { i, it -> drawItem(k, c, s, lk, it, i, t) }
        // one doodle around the main picture once it is done
        items.firstOrNull { it.rect != null }?.let { main -> doodle(k, c, s, lk, main, t) }
        c.restore()
        vignette(k, c)
        return true
    }

    private fun vignette(k: Kit, c: Canvas) {
        k.fill.shader = RadialGradient(k.cx, k.cy, max(k.w, k.h) * 0.75f, intArrayOf(0, 0x22000000), floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
    }

    // ------------------------------------------------------------------ layouts -> items
    private fun drawTime(sec: Float) = (sec * 0.62f).coerceIn(1.6f, 5.5f)
    private fun rect16x9(cx: Float, top: Float, w: Float) = RectF(cx - w / 2, top, cx + w / 2, top + w * 9f / 16f)

    private fun items(k: Kit, s: Scene, lk: Look, d: Float): List<Item> {
        val out = ArrayList<Item>()
        val cap = { tx: String, n: Int -> ScriptAnalyzer.phrase(tx, n) }
        when (s.type) {
            SceneType.DOODLE_CENTER -> {
                val r = rect16x9(k.cx, if (k.portrait) k.h * 0.14f else k.h * 0.07f, if (k.portrait) k.w * 0.86f else k.w * 0.6f)
                val dr = drawTime(d)
                out += Item(r, s.imageIndex, s.title.ifBlank { cap(s.text, 14) }, k.cx, r.bottom + 72f * k.u, k.w * 0.82f,
                    (if (k.portrait) 44f else 40f) * k.u, Paint.Align.CENTER, INK, 0.15f, d, 0.15f + dr * 0.7f)
            }
            SceneType.DOODLE_LEFT, SceneType.DOODLE_RIGHT -> {
                val left = s.type == SceneType.DOODLE_LEFT
                if (k.portrait) return items(k, s.copy(type = SceneType.DOODLE_CENTER), lk, d)
                val pw = k.w * 0.5f
                val r = RectF(if (left) k.w * 0.04f else k.w * 0.46f, 0f, 0f, 0f).also { it.right = it.left + pw; it.top = k.cy - pw * 9f / 32f - 10f * k.u; it.bottom = it.top + pw * 9f / 16f }
                val dr = drawTime(d)
                out += Item(r, s.imageIndex, s.text, if (left) k.w * 0.58f else k.w * 0.05f, k.h * 0.3f, k.w * 0.37f, 38f * k.u,
                    Paint.Align.LEFT, INK, 0.15f, d, 0.15f + dr * 0.6f, 6)
            }
            SceneType.DOODLE_TOP -> {
                val title = s.title.ifBlank { cap(s.text, 6) }
                val titleSec = min(1.6f, d * 0.25f)
                out += Item(null, -1, title, k.cx, k.h * 0.13f, k.w * 0.86f, (if (k.portrait) 50f else 46f) * k.u, Paint.Align.CENTER, BLUE, 0.1f, 0.1f + titleSec, 0.1f, 2, boxed = false)
                val r = rect16x9(k.cx, k.h * 0.2f, if (k.portrait) k.w * 0.86f else k.w * 0.56f)
                out += Item(r, s.imageIndex, "", 0f, 0f, 0f, 0f, Paint.Align.CENTER, INK, 0.2f + titleSec * 0.6f, d, d)
            }
            SceneType.DOODLE_FULL -> {
                val r = rect16x9(k.cx, k.h * 0.05f, if (k.portrait) k.w * 0.92f else k.w * 0.74f)
                val dr = drawTime(d)
                out += Item(r, s.imageIndex, s.title.ifBlank { cap(s.text, 12) }, k.cx, min(k.h - 40f * k.u, r.bottom + 60f * k.u), k.w * 0.86f,
                    38f * k.u, Paint.Align.CENTER, INK, 0.15f, d, 0.15f + dr * 0.75f, 2)
            }
            SceneType.DOODLE_DUO, SceneType.DOODLE_TRIO -> {
                val texts = s.lines.ifEmpty { listOf(s.text) }
                val n = texts.size
                val imgs = s.images.ifEmpty { List(n) { s.imageIndex } }
                val starts = s.lineStarts.takeIf { it.size == n } ?: texts.indices.map { it / n.toFloat() }
                val slots = slots(k, n, lk.arr)
                texts.forEachIndexed { i, text ->
                    val st = starts[i] * d + 0.1f
                    val en = if (i + 1 < n) starts[i + 1] * d else d
                    val (r, capY) = slots[i]
                    val img = imgs.getOrElse(i) { -1 }
                    val size = (if (n == 3 && !k.portrait) 27f else 33f) * k.u
                    val dr = drawTime(en - st)
                    out += if (img >= 0 && k.images.get(img) != null)
                        Item(r, img, cap(text, if (n == 3) 10 else 15), r.centerX(), capY, r.width() + 20f * k.u, size, Paint.Align.CENTER,
                            if (i % 2 == 1) BLUE else INK, st, en, st + dr * 0.65f, 2)
                    else Item(null, -1, text, r.centerX(), r.centerY(), r.width(), size * 1.3f, Paint.Align.CENTER, INK, st, en, st, 4)
                }
            }
            else -> { // DOODLE_TEXT: lines written one after another, each when spoken
                val texts = s.lines.ifEmpty { listOf(s.text) }
                val starts = s.lineStarts.takeIf { it.size == texts.size } ?: texts.indices.map { it / texts.size.toFloat() }
                val size = (if (k.portrait) 50f else 46f) * k.u
                var y = k.h * (if (texts.size == 1) 0.42f else 0.22f)
                texts.forEachIndexed { i, text ->
                    k.setText(k.fonts.hand, size, INK, text)
                    val lines = k.wrap(text, k.w * 0.74f).take(4).size
                    val st = starts[i] * d + 0.1f
                    val en = if (i + 1 < texts.size) starts[i + 1] * d else d
                    out += Item(null, -1, text, k.cx, y, k.w * 0.74f, size, Paint.Align.CENTER, if (i % 2 == 1) BLUE else INK, st, en, st, 4)
                    y += lines * size * 1.25f + size * 0.9f
                }
            }
        }
        return out
    }

    /** Picture rectangles (16:9) and caption baselines for 2 or 3 lines in three arrangements each. */
    private fun slots(k: Kit, n: Int, arr: Int): List<Pair<RectF, Float>> {
        val out = ArrayList<Pair<RectF, Float>>()
        if (k.portrait) {
            val pw = k.w * 0.8f; val ph = pw * 9f / 16f
            val gap = (k.h - n * (ph + 90f * k.u)) / (n + 1)
            for (i in 0 until n) {
                val shift = if (arr == 1) (if (i % 2 == 0) -1 else 1) * k.w * 0.04f else 0f
                val top = gap + i * (ph + 90f * k.u + gap)
                val r = RectF(k.cx - pw / 2 + shift, top, k.cx + pw / 2 + shift, top + ph)
                out += Pair(r, r.bottom + 50f * k.u)
            }
            return out
        }
        if (n == 2) when (arr) {
            0 -> for (i in 0 until 2) { // side by side, gently staggered
                val pw = k.w * 0.42f; val ph = pw * 9f / 16f
                val left = if (i == 0) k.w * 0.05f else k.w * 0.53f
                val top = k.cy - ph / 2 - 40f * k.u + (if (i == 0) -25f else 25f) * k.u
                val r = RectF(left, top, left + pw, top + ph); out += Pair(r, r.bottom + 50f * k.u)
            }
            1 -> { // diagonal: top-left then bottom-right, captions beside
                val pw = k.w * 0.4f; val ph = pw * 9f / 16f
                val a = RectF(k.w * 0.05f, k.h * 0.05f, k.w * 0.05f + pw, k.h * 0.05f + ph)
                val b = RectF(k.w * 0.55f, k.h * 0.95f - ph - 60f * k.u, k.w * 0.55f + pw, k.h * 0.95f - 60f * k.u)
                out += Pair(a, a.bottom + 48f * k.u); out += Pair(b, b.bottom + 48f * k.u)
            }
            else -> { // one big on the right, the first small on the left
                val sw = k.w * 0.36f; val sh = sw * 9f / 16f
                val bw = k.w * 0.52f; val bh = bw * 9f / 16f
                val a = RectF(k.w * 0.04f, k.cy - sh / 2 - 60f * k.u, k.w * 0.04f + sw, k.cy + sh / 2 - 60f * k.u)
                val b = RectF(k.w * 0.44f, k.cy - bh / 2 - 30f * k.u, k.w * 0.44f + bw, k.cy + bh / 2 - 30f * k.u)
                out += Pair(a, a.bottom + 46f * k.u); out += Pair(b, b.bottom + 48f * k.u)
            }
        } else when (arr) {
            0 -> { // big left, two stacked right
                val bw = k.w * 0.5f; val bh = bw * 9f / 16f
                val big = RectF(k.w * 0.04f, k.cy - bh / 2 - 30f * k.u, k.w * 0.04f + bw, k.cy + bh / 2 - 30f * k.u)
                out += Pair(big, big.bottom + 48f * k.u)
                val sw = k.w * 0.34f; val sh = sw * 9f / 16f
                for (j in 0 until 2) { val top = k.h * 0.06f + j * (sh + 76f * k.u); val r = RectF(k.w * 0.61f, top, k.w * 0.61f + sw, top + sh); out += Pair(r, r.bottom + 38f * k.u) }
            }
            1 -> for (i in 0 until 3) { // row with the middle one lower
                val pw = k.w * 0.29f; val ph = pw * 9f / 16f
                val left = k.w * (0.04f + i * 0.32f); val top = k.cy - ph / 2 - 40f * k.u + (if (i == 1) 34f else 0f) * k.u
                val r = RectF(left, top, left + pw, top + ph); out += Pair(r, r.bottom + 44f * k.u)
            }
            else -> { // triangle: two on top, one centred below
                val pw = k.w * 0.32f; val ph = pw * 9f / 16f
                val a = RectF(k.w * 0.12f, k.h * 0.05f, k.w * 0.12f + pw, k.h * 0.05f + ph)
                val b = RectF(k.w * 0.56f, k.h * 0.05f, k.w * 0.56f + pw, k.h * 0.05f + ph)
                val cc = RectF(k.cx - pw / 2, k.h * 0.53f, k.cx + pw / 2, k.h * 0.53f + ph)
                out += Pair(a, a.bottom + 40f * k.u); out += Pair(b, b.bottom + 40f * k.u); out += Pair(cc, cc.bottom + 40f * k.u)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ camera
    private fun camera(k: Kit, c: Canvas, lk: Look, items: List<Item>, t: Float) {
        val pics = items.filter { it.rect != null }
        if (pics.isEmpty()) return
        fun focusOn(r: RectF, f: Float) {
            if (f <= 0f) return
            val z = 1f + (min(k.w * 0.8f / r.width(), k.h * 0.8f / r.height()).coerceIn(1f, 1.45f) - 1f) * f
            val fx = k.lerp(k.cx, r.centerX(), f); val fy = k.lerp(k.cy, r.centerY(), f)
            c.translate(k.cx, k.cy); c.scale(z, z); c.translate(-fx, -fy)
        }
        when (lk.cam) {
            C_FOLLOW -> {
                // zoom in on the picture being drawn, back out once it is finished
                val active = pics.lastOrNull { t >= it.start } ?: return
                val drawEnd = active.start + drawTime(active.end - active.start)
                val f = k.inOutCubic(k.seg(t, active.start, active.start + 0.6f)) * (1f - k.inOutCubic(k.seg(t, drawEnd, drawEnd + 0.7f)))
                focusOn(active.rect!!, f * 0.85f)
            }
            C_PULLBACK -> {
                val first = pics.first()
                val f = 1f - k.inOutCubic(k.seg(t, first.start + drawTime(first.end - first.start) * 0.8f, first.start + drawTime(first.end - first.start) * 0.8f + 1.2f))
                focusOn(first.rect!!, f)
            }
            C_DRIFT -> { c.scale(1.04f, 1.04f, k.cx, k.cy); c.translate(sin(t * 0.25f) * 12f * k.u, cos(t * 0.2f) * 6f * k.u) }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ pictures
    private val dstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val multiply = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }
    private val maskPx = ThreadLocal.withInitial { IntArray(DrawOrder.W * DrawOrder.H) }
    private val maskBmp = ThreadLocal.withInitial { Bitmap.createBitmap(DrawOrder.W, DrawOrder.H, Bitmap.Config.ARGB_8888) }

    private fun mask(order: FloatArray, threshold: Float, soft: Float): Bitmap {
        val px = maskPx.get()!!
        for (i in px.indices) { val a = ((threshold - order[i]) / soft).coerceIn(0f, 1f); px[i] = ((a * 255).toInt() shl 24) or 0xFFFFFF }
        val b = maskBmp.get()!!
        b.setPixels(px, 0, DrawOrder.W, 0, 0, DrawOrder.W, DrawOrder.H)
        return b
    }

    private fun layer(k: Kit, c: Canvas, img: Bitmap, r: RectF, order: FloatArray, p: Float, soft: Float = 0.07f, blend: Paint? = null) {
        if (p <= 0f) return
        val save = c.saveLayer(r, blend)
        k.cover(c, img, r, 1.0f)
        if (p < 1f) c.drawBitmap(mask(order, p * (1f + soft), soft), null, r, dstIn)
        c.restoreToCount(save)
    }

    private fun clipFor(frame: Int, r: RectF, seed: Int, k: Kit): Path? = when (frame) {
        F_CIRCLE -> Path().apply { addOval(RectF(r.centerX() - r.height() * 0.62f, r.top - r.height() * 0.05f, r.centerX() + r.height() * 0.62f, r.bottom + r.height() * 0.05f), Path.Direction.CW) }
        F_TORN -> Path().apply {
            val rnd = Random(seed); val j = { (rnd.nextFloat() - 0.5f) * 10f * k.u }
            moveTo(r.left, r.top + j())
            var x = r.left; while (x < r.right) { x += 18f * k.u; lineTo(min(x, r.right), r.top + j()) }
            var y = r.top; while (y < r.bottom) { y += 18f * k.u; lineTo(r.right + j(), min(y, r.bottom)) }
            x = r.right; while (x > r.left) { x -= 18f * k.u; lineTo(max(x, r.left), r.bottom + j()) }
            y = r.bottom; while (y > r.top) { y -= 18f * k.u; lineTo(r.left + j(), max(y, r.top)) }
            close()
        }
        else -> null
    }

    private fun picture(k: Kit, c: Canvas, s: Scene, lk: Look, it: Item, idx: Int, t: Float) {
        val r = it.rect ?: return
        val color = k.images.get(it.image) ?: return
        val p = k.seg(t, it.start, it.start + drawTime(it.end - it.start))
        if (p <= 0f) return
        val seed = s.variant + idx * 7919
        // subjects on white are drawn straight onto the board, never boxed
        val frame = if (k.images.isWhiteBackground(it.image) && (lk.frame == F_BOX || lk.frame == F_TORN || lk.frame == F_POLAROID)) F_CUTOUT else lk.frame
        val sketch = k.images.sketch(it.image) ?: color
        val order = DrawOrder.of(sketch, "${k.project.images.getOrNull(it.image)}", seed)
        c.save()
        if (frame == F_POLAROID) {
            val rot = if (idx % 2 == 0) -2.5f else 2.5f
            c.rotate(rot * k.outBack(k.seg(t, it.start, it.start + 0.4f)), r.centerX(), r.centerY())
            val card = RectF(r.left - 14f * k.u, r.top - 14f * k.u, r.right + 14f * k.u, r.bottom + 40f * k.u)
            val pop = k.outBack(k.seg(t, it.start - 0.1f, it.start + 0.3f))
            c.save(); c.scale(pop, pop, card.centerX(), card.centerY())
            k.fill.color = 0x30000000; c.drawRect(card.left + 6f * k.u, card.top + 8f * k.u, card.right + 6f * k.u, card.bottom + 8f * k.u, k.fill)
            k.fill.color = Color.WHITE; c.drawRect(card, k.fill)
            k.fill.color = 0x88E8DFC0.toInt()
            c.save(); c.rotate(-12f, card.left + 30f * k.u, card.top); c.drawRect(card.left, card.top - 12f * k.u, card.left + 70f * k.u, card.top + 12f * k.u, k.fill); c.restore()
            c.save(); c.rotate(12f, card.right - 30f * k.u, card.top); c.drawRect(card.right - 70f * k.u, card.top - 12f * k.u, card.right, card.top + 12f * k.u, k.fill); c.restore()
            c.restore()
        }
        clipFor(frame, r, seed, k)?.let { c.clipPath(it) }
        val blend = if (frame == F_CUTOUT) multiply else null
        when (lk.draw) {
            D_PENCIL -> {
                layer(k, c, sketch, r, order.outline, k.clamp01(p / 0.75f), blend = blend)
                layer(k, c, color, r, order.colour, k.inOutCubic(k.clamp01((p - 0.78f) / 0.22f)), 0.12f, blend)
            }
            D_BRUSH -> layer(k, c, color, r, order.colour, k.inOutCubic(p), 0.1f, blend)
            D_INK -> {
                val ink = k.images.ink(it.image) ?: sketch
                layer(k, c, ink, r, order.outline, k.clamp01(p / 0.5f), blend = blend ?: multiply)
                layer(k, c, color, r, order.colour, k.inOutCubic(k.clamp01((p - 0.45f) / 0.5f)), 0.08f, blend)
                if (p > 0.45f) layer(k, c, ink, r, order.outline, 1f, blend = multiply)    // keep the ink lines on top of the colour
            }
            D_DISSOLVE -> {
                layer(k, c, sketch, r, order.outline, k.clamp01(p / 0.45f), blend = blend)
                layer(k, c, color, r, order.colour, k.clamp01((p - 0.4f) / 0.6f), 0.3f, blend)
            }
            else -> {
                layer(k, c, sketch, r, order.outline, k.clamp01(p / 0.58f).let { it * (0.6f + 0.4f * it) }, blend = blend)
                layer(k, c, color, r, order.colour, k.inOutCubic(k.clamp01((p - 0.5f) / 0.5f)), blend = blend)
            }
        }
        c.restore()
        // the frame is drawn once the picture is finished
        val fp = k.seg(t, it.start + drawTime(it.end - it.start), it.start + drawTime(it.end - it.start) + 0.6f)
        when (frame) {
            F_BOX -> wobblyRect(k, c, r, fp, seed, INK)
            F_CIRCLE -> scribbleCircle(k, c, RectF(r.centerX() - r.height() * 0.64f, r.top - r.height() * 0.07f, r.centerX() + r.height() * 0.64f, r.bottom + r.height() * 0.07f), fp, INK, seed, 1.05f)
            F_TORN -> { k.stroke.color = 0x40000000; k.stroke.strokeWidth = 2f * k.u; clipFor(F_TORN, r, seed, k)?.let { pth -> if (fp > 0f) c.drawPath(pth, k.stroke) } }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ handwriting
    private fun cps(text: String, budget: Float) = (text.length / max(0.8f, budget)).coerceIn(9f, 28f)

    private fun drawItem(k: Kit, c: Canvas, s: Scene, lk: Look, it: Item, idx: Int, t: Float) {
        picture(k, c, s, lk, it, idx, t)
        if (it.text.isBlank() || t < it.textStart) return
        val style = if (!it.boxed) T_PLAIN else lk.text
        val speed = cps(it.text, (it.end - it.textStart) * 0.8f)
        k.setText(k.fonts.hand, it.size, it.color, it.text)
        val lines = k.wrap(it.text, it.tw).take(it.maxLines)
        val lh = it.size * 1.25f
        val bw = lines.maxOf { k.text.measureText(it) }
        val left = when (it.align) { Paint.Align.CENTER -> it.tx - bw / 2; Paint.Align.RIGHT -> it.tx - bw; else -> it.tx }
        val block = RectF(left - 22f * k.u, it.ty - it.size * 1.05f, left + bw + 22f * k.u, it.ty + (lines.size - 1) * lh + it.size * 0.45f)
        val appear = k.outBack(k.seg(t, it.textStart - 0.15f, it.textStart + 0.25f))
        // background of the text (before writing)
        when (style) {
            T_STICKY -> {
                c.save(); c.rotate(if (idx % 2 == 0) -2f else 2f, block.centerX(), block.centerY()); c.scale(appear, appear, block.centerX(), block.centerY())
                k.fill.color = 0x33000000; c.drawRect(block.left + 6f * k.u, block.top + 8f * k.u, block.right + 6f * k.u, block.bottom + 8f * k.u, k.fill)
                k.fill.color = STICKY; c.drawRect(block, k.fill)
                c.restore()
            }
            T_BUBBLE -> bubble(k, c, block, it.rect, appear, s.variant + idx)
            else -> {}
        }
        // the writing itself
        val total = lines.sumOf { l -> l.length }
        var left2 = ((t - it.textStart) * speed).toInt().coerceIn(0, total)
        val doneAt = it.textStart + total / speed
        val em = s.emphasis.map { e -> e.lowercase() }.toSet()
        lines.forEachIndexed { i, l ->
            if (left2 <= 0) return@forEachIndexed
            val lw = k.text.measureText(l)
            val lx = when (it.align) { Paint.Align.CENTER -> it.tx - lw / 2; Paint.Align.RIGHT -> it.tx - lw; else -> it.tx }
            k.drawRich(c, l.take(left2), lx, it.ty + i * lh, em, it.color, RED)
            left2 -= l.length
        }
        val after = k.seg(t, doneAt, doneAt + 0.5f)
        when (style) {
            T_HIGHLIGHT -> lines.forEachIndexed { i, l -> // marker highlight over emphasised words (multiply keeps the ink black)
                val lw = k.text.measureText(l)
                var x = when (it.align) { Paint.Align.CENTER -> it.tx - lw / 2; Paint.Align.RIGHT -> it.tx - lw; else -> it.tx }
                val space = k.text.measureText(" ")
                for (wd in l.split(" ")) {
                    val ww = k.text.measureText(wd)
                    if (ScriptAnalyzer.cleanWord(wd).lowercase() in em && after > 0f) {
                        multiply.color = HIGHLIGHT
                        c.drawRect(x - 4f * k.u, it.ty + i * lh - it.size * 0.75f, x - 4f * k.u + (ww + 8f * k.u) * after, it.ty + i * lh + it.size * 0.2f, multiply)
                    }
                    x += ww + space
                }
            }
            T_BOX -> wobblyRect(k, c, block, after, s.variant + idx, BLUE, 12f * k.u)
            T_PLAIN -> if (em.isNotEmpty() && after > 0f) {
                val l0 = lines.first(); val lw = k.text.measureText(l0)
                val lx = when (it.align) { Paint.Align.CENTER -> it.tx - lw / 2; Paint.Align.RIGHT -> it.tx - lw; else -> it.tx }
                scribbleLine(k, c, lx, it.ty + it.size * 0.25f, lx + lw, it.ty + it.size * 0.3f, after, RED, s.variant)
            }
            else -> {}
        }
    }

    private fun bubble(k: Kit, c: Canvas, block: RectF, target: RectF?, p: Float, seed: Int) {
        if (p <= 0f) return
        val r = RectF(block.left - 10f * k.u, block.top - 8f * k.u, block.right + 10f * k.u, block.bottom + 8f * k.u)
        c.save(); c.scale(p, p, r.centerX(), r.centerY())
        k.fill.color = Color.WHITE; c.drawRoundRect(r, 30f * k.u, 30f * k.u, k.fill)
        val path = Path().apply { addRoundRect(r, 30f * k.u, 30f * k.u, Path.Direction.CW) }
        wobbly(path, 1f, k, c, INK, 3.5f * k.u)
        // tail towards the picture (or downwards)
        val tx = target?.centerX() ?: r.centerX(); val ty = target?.centerY() ?: (r.bottom + 60f * k.u)
        val bx = r.centerX().coerceIn(r.left + 40f * k.u, r.right - 40f * k.u)
        val by = if (ty < r.top) r.top else r.bottom
        val tip = Pair(bx + (tx - bx) * 0.25f, by + (if (ty < r.top) -40f else 40f) * k.u)
        val tail = Path().apply { moveTo(bx - 18f * k.u, by); lineTo(tip.first, tip.second); lineTo(bx + 18f * k.u, by) }
        c.drawPath(tail, k.fill); wobbly(tail, 1f, k, c, INK, 3.5f * k.u)
        c.restore()
        @Suppress("UNUSED_VARIABLE") val unused = seed
    }

    // ------------------------------------------------------------------ doodles around the main picture
    private fun doodle(k: Kit, c: Canvas, s: Scene, lk: Look, main: Item, t: Float) {
        val r = main.rect ?: return
        val done = main.start + drawTime(main.end - main.start)
        val p = k.seg(t, done + 0.2f, done + 0.9f)
        if (p <= 0f) return
        val rnd = Random(s.variant + 3)
        when (lk.deco) {
            1 -> { star(k, c, r.left - 26f * k.u, r.top + 10f * k.u, 22f * k.u, p, RED); star(k, c, r.right + 24f * k.u, r.bottom - 20f * k.u, 18f * k.u, k.seg(t, done + 0.5f, done + 1.1f), BLUE) }
            2 -> { // shine lines around the picture
                k.stroke.color = 0xFFE0A100.toInt(); k.stroke.strokeWidth = 4f * k.u
                for (i in 0 until 10) {
                    val a = (i * 2 * PI / 10 + 0.3).toFloat()
                    val rx = r.width() / 2 + 18f * k.u; val ry = r.height() / 2 + 18f * k.u
                    val x0 = r.centerX() + cos(a) * rx; val y0 = r.centerY() + sin(a) * ry
                    val len = 26f * k.u * k.seg(p, i * 0.06f, i * 0.06f + 0.4f)
                    c.drawLine(x0, y0, x0 + cos(a) * len, y0 + sin(a) * len, k.stroke)
                }
            }
            3 -> arrow(k, c, if (r.centerX() > k.cx) k.w * 0.08f else k.w * 0.92f, k.h * 0.9f, if (r.centerX() > k.cx) r.left - 10f * k.u else r.right + 10f * k.u, r.bottom - 20f * k.u, p, RED, s.variant)
            4 -> swirl(k, c, if (r.centerX() > k.cx) r.left - 60f * k.u else r.right + 60f * k.u, r.top + 40f * k.u, 34f * k.u, p, BLUE)
            5 -> { // ? or ! next to the picture
                val mark = if (s.text.trim().endsWith("?")) "?" else "!"
                k.setText(k.fonts.hand, 110f * k.u, RED, mark); k.text.textAlign = Paint.Align.CENTER
                val x = if (r.centerX() > k.cx) r.left - 55f * k.u else r.right + 55f * k.u
                c.save(); c.scale(k.outBack(p), k.outBack(p), x, r.top + 70f * k.u); c.drawText(mark, x, r.top + 110f * k.u, k.text); c.restore()
            }
            6 -> { // tick next to the caption
                val x = (main.tx + main.tw / 2).coerceAtMost(k.w - 70f * k.u); val y = main.ty - 20f * k.u
                k.check(c, x, y, 34f * k.u, p, GREEN)
            }
            else -> {}
        }
        @Suppress("UNUSED_VARIABLE") val unused = rnd
    }

    // ------------------------------------------------------------------ numbers
    private fun number(k: Kit, c: Canvas, s: Scene, lk: Look, t: Float, d: Float) {
        val big = s.title.ifBlank { ScriptAnalyzer.strongWords(s.text, 1).firstOrNull() ?: "!" }
        val size = min(220f * k.u, k.fitSize(big, k.fonts.hand, k.w * 0.7f, 220f * k.u))
        val speed = cps(big, 1.2f)
        val done = 0.15f + big.length / speed
        k.setText(k.fonts.hand, size, RED, big); k.text.textAlign = Paint.Align.CENTER
        val shown = big.take(((t - 0.15f) * speed).toInt().coerceIn(0, big.length))
        val bw = k.text.measureText(big)
        val y = k.cy + size * 0.1f
        c.drawText(shown, k.cx - (bw - k.text.measureText(shown)) / 2, y, k.text)
        val ring = RectF(k.cx - bw / 2 - 40f * k.u, k.cy - size * 0.75f, k.cx + bw / 2 + 40f * k.u, k.cy + size * 0.35f)
        val p = k.seg(t, done, done + 0.8f)
        when (lk.frame % 3) {
            0 -> scribbleCircle(k, c, ring, p, INK, s.variant)
            1 -> wobblyRect(k, c, ring, p, s.variant, BLUE)
            else -> { scribbleLine(k, c, ring.left, ring.bottom, ring.right, ring.bottom + 6f * k.u, p, INK, s.variant); scribbleLine(k, c, ring.left + 20f * k.u, ring.bottom + 18f * k.u, ring.right - 20f * k.u, ring.bottom + 22f * k.u, k.seg(t, done + 0.3f, done + 1f), INK, s.variant + 1) }
        }
        if (lk.deco % 2 == 0) { star(k, c, ring.left - 30f * k.u, ring.top, 22f * k.u, k.seg(t, done + 0.6f, done + 1f), BLUE); star(k, c, ring.right + 30f * k.u, ring.bottom, 18f * k.u, k.seg(t, done + 0.8f, done + 1.2f), RED) }
        val label = s.lines.firstOrNull() ?: ScriptAnalyzer.phrase(s.text, 9)
        drawItem(k, c, s, lk, Item(null, -1, label, k.cx, k.cy + size * 0.35f + 80f * k.u, k.w * 0.8f, 40f * k.u, Paint.Align.CENTER, INK,
            done + 0.6f, d, done + 0.7f, 2), 1, t)
    }

    // ------------------------------------------------------------------ marker shapes
    private fun wobbly(path: Path, p: Float, k: Kit, c: Canvas, color: Int, width: Float) {
        val pm = PathMeasure(path, false)
        val seg = Path(); pm.getSegment(0f, pm.length * k.clamp01(p), seg, true)
        k.stroke.color = color; k.stroke.strokeWidth = width
        c.drawPath(seg, k.stroke)
    }

    private fun wobblyRect(k: Kit, c: Canvas, r: RectF, p: Float, seed: Int, color: Int = INK, corner: Float = 0f) {
        if (p <= 0f) return
        val rnd = Random(seed); val j = { (rnd.nextFloat() - 0.5f) * 6f * k.u }
        val path = if (corner > 0f) Path().apply { addRoundRect(r, corner, corner, Path.Direction.CW) } else Path().apply {
            moveTo(r.left + j(), r.top + j()); lineTo(r.right + j(), r.top + j()); lineTo(r.right + j(), r.bottom + j())
            lineTo(r.left + j(), r.bottom + j()); lineTo(r.left + j(), r.top + j() + 3f * k.u)
        }
        wobbly(path, p, k, c, color, 4f * k.u)
    }

    private fun scribbleLine(k: Kit, c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, p: Float, color: Int, seed: Int) {
        if (p <= 0f) return
        val rnd = Random(seed)
        val path = Path().apply { moveTo(x0, y0); quadTo((x0 + x1) / 2, (y0 + y1) / 2 + (rnd.nextFloat() - 0.3f) * 14f * k.u, x1, y1) }
        wobbly(path, p, k, c, color, 5f * k.u)
    }

    private fun scribbleCircle(k: Kit, c: Canvas, r: RectF, p: Float, color: Int, seed: Int, turns: Float = 1.12f) {
        if (p <= 0f) return
        val rnd = Random(seed)
        val path = Path()
        val steps = 64
        for (i in 0..steps) {
            val a = (-PI / 2 + i * 2 * turns * PI / steps).toFloat()
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

    private fun swirl(k: Kit, c: Canvas, x: Float, y: Float, r: Float, p: Float, color: Int) {
        if (p <= 0f) return
        val path = Path()
        for (i in 0..60) {
            val a = i * 0.32f; val rad = r * i / 60f
            val px = x + cos(a) * rad; val py = y + sin(a) * rad
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        wobbly(path, p, k, c, color, 4f * k.u)
    }
}
