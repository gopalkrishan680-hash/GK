package com.gk.facelessstudio.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.gk.facelessstudio.engine.ScriptAnalyzer
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.Scene
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.model.Transition
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Draws any moment of the video onto a Canvas. Used both by the live preview and the exporter.
 * One instance per thread (it owns scratch bitmaps and paints).
 */
class FrameRenderer(
    val w: Int,
    val h: Int,
    project: Project,
    fonts: Fonts,
    images: ImageStore,
) {
    private val p = project
    private val k = Kit(w, h, project, Themes.byId(project.themeId), fonts, images)
    private val scenes = project.scenes
    private val trMs = (project.pacing.transitionSec * 1000).toLong()

    private var bufA: Bitmap? = null
    private var bufB: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val vignette = Paint().apply {
        shader = RadialGradient(w / 2f, h / 2f, hypot(w / 2f, h / 2f), intArrayOf(0, 0, 0xB0000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }
    private val grainBmp: Bitmap by lazy {
        val n = 128
        val r = Random(7)
        val px = IntArray(n * n) { val v = r.nextInt(256); Color.argb(255, v, v, v) }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }
    private val grainPaint by lazy {
        Paint().apply {
            shader = BitmapShader(grainBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            alpha = 22
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        }
    }

    fun sceneIndexAt(tMs: Long): Int {
        if (scenes.isEmpty()) return -1
        var lo = 0; var hi = scenes.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (scenes[mid].startMs <= tMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun draw(c: Canvas, tMs: Long) {
        if (scenes.isEmpty()) { c.drawColor(k.theme.bg); return }
        val i = sceneIndexAt(tMs)
        val cur = scenes[i]

        // transition window centred on each cut
        val next = scenes.getOrNull(i + 1)
        var drawn = false
        if (next != null && next.transition != Transition.CUT) {
            val half = halfWindow(cur, next)
            if (tMs >= next.startMs - half) { transition(c, cur, next, tMs, half); drawn = true }
        }
        if (!drawn && i > 0 && cur.transition != Transition.CUT) {
            val prev = scenes[i - 1]
            val half = halfWindow(prev, cur)
            if (tMs < cur.startMs + half) { transition(c, prev, cur, tMs, half); drawn = true }
        }
        if (!drawn) drawScene(c, cur, tMs)

        overlays(c, cur, tMs)
    }

    private fun halfWindow(a: Scene, b: Scene) = min(trMs / 2, min(a.durationMs, b.durationMs) / 3)

    private fun drawScene(c: Canvas, s: Scene, tMs: Long) {
        val t = (tMs - s.startMs) / 1000f
        val d = s.durationMs / 1000f
        c.save()
        // impact shake on punchy layouts
        if (s.type == SceneType.KINETIC || s.type == SceneType.BIG_STAT || s.type == SceneType.CHAPTER) {
            val amp = (1f - k.seg(t, 0f, 0.25f)) * 7f * k.u
            if (amp > 0.1f) {
                val r = Random(tMs / 33)
                c.translate((r.nextFloat() - 0.5f) * amp * 2, (r.nextFloat() - 0.5f) * amp * 2)
            }
        }
        Templates.draw(k, c, s, t, d)
        c.restore()
    }

    private fun buffers(): Pair<Bitmap, Bitmap> {
        val a = bufA ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bufA = it }
        val b = bufB ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bufB = it }
        return Pair(a, b)
    }

    private fun transition(c: Canvas, a: Scene, b: Scene, tMs: Long, half: Long) {
        val pr = k.clamp01((tMs - (b.startMs - half)).toFloat() / max(1L, half * 2))
        val (ba, bb) = buffers()
        val ca = Canvas(ba); val cb = Canvas(bb)
        val needA = pr < 1f; val needB = pr > 0f
        if (needA) drawScene(ca, a, tMs)
        if (needB) drawScene(cb, b, tMs)
        val e = k.inOutCubic(pr)
        paint.alpha = 255; paint.colorFilter = null
        when (b.transition) {
            Transition.FADE, Transition.CUT -> {
                c.drawBitmap(ba, 0f, 0f, paint)
                paint.alpha = (255 * e).toInt(); c.drawBitmap(bb, 0f, 0f, paint)
            }
            Transition.ZOOM_PUNCH -> {
                if (pr < 0.5f) {
                    val q = pr * 2
                    c.save(); val sc = 1f + k.inOutCubic(q) * 0.5f; c.scale(sc, sc, k.cx, k.cy)
                    c.drawBitmap(ba, 0f, 0f, paint); c.restore()
                } else {
                    val q = (pr - 0.5f) * 2
                    c.save(); val sc = 1.4f - k.outCubic(q) * 0.4f; c.scale(sc, sc, k.cx, k.cy)
                    c.drawBitmap(bb, 0f, 0f, paint); c.restore()
                }
                c.drawColor(k.alpha(Color.WHITE, (1f - abs(pr - 0.5f) * 2f) * 0.35f))
            }
            Transition.SLIDE_LEFT, Transition.WHIP -> {
                val ee = if (b.transition == Transition.WHIP) k.inOutExpo(pr) else e
                val off = ee * w
                c.drawBitmap(ba, -off, 0f, paint)
                c.drawBitmap(bb, w - off, 0f, paint)
                if (b.transition == Transition.WHIP) {
                    val blur = (1f - abs(pr - 0.5f) * 2f)
                    paint.alpha = (70 * blur).toInt()
                    for (j in 1..3) {
                        val dx = j * 40f * k.u * blur
                        c.drawBitmap(ba, -off + dx, 0f, paint); c.drawBitmap(bb, w - off + dx, 0f, paint)
                    }
                    paint.alpha = 255
                }
            }
            Transition.SLIDE_UP -> {
                val off = e * h
                c.drawBitmap(ba, 0f, -off, paint)
                c.drawBitmap(bb, 0f, h - off, paint)
            }
            Transition.DIP -> {
                c.drawBitmap(if (pr < 0.5f) ba else bb, 0f, 0f, paint)
                c.drawColor(k.alpha(Color.BLACK, 1f - abs(pr - 0.5f) * 2f))
            }
            Transition.FLASH -> {
                c.drawBitmap(if (pr < 0.5f) ba else bb, 0f, 0f, paint)
                c.drawColor(k.alpha(Color.WHITE, (1f - abs(pr - 0.5f) * 2f) * 0.95f))
            }
            Transition.GLITCH -> {
                val src = if (pr < 0.5f) ba else bb
                val inten = 1f - abs(pr - 0.5f) * 2f
                c.drawBitmap(src, 0f, 0f, paint)
                val r = Random(tMs / 40)
                val bands = 9
                for (j in 0 until bands) {
                    if (r.nextFloat() > 0.65f) continue
                    val y0 = r.nextFloat() * h; val bhh = (8f + r.nextFloat() * 60f) * k.u
                    val dx = (r.nextFloat() - 0.5f) * 120f * k.u * inten
                    c.save(); c.clipRect(0f, y0, w.toFloat(), y0 + bhh)
                    paint.colorFilter = LightingColorFilter(if (j % 2 == 0) 0xFFFF0000.toInt() else 0xFF00FFFF.toInt(), 0)
                    paint.alpha = 200
                    c.drawBitmap(src, dx, 0f, paint)
                    c.restore()
                }
                paint.colorFilter = null; paint.alpha = 255
            }
            Transition.CIRCLE -> {
                c.drawBitmap(ba, 0f, 0f, paint)
                val path = Path(); path.addCircle(k.cx, k.cy, hypot(k.cx, k.cy) * e, Path.Direction.CW)
                c.save(); c.clipPath(path); c.drawBitmap(bb, 0f, 0f, paint); c.restore()
            }
            Transition.SHUTTER -> {
                c.drawBitmap(ba, 0f, 0f, paint)
                val bars = 7; val bw = w / bars.toFloat()
                val path = Path()
                for (j in 0 until bars) {
                    val q = k.inOutCubic(k.clamp01(pr * 1.6f - j * 0.08f))
                    val x0 = j * bw + bw / 2
                    path.addRect(x0 - bw / 2 * q - 1, 0f, x0 + bw / 2 * q + 1, h.toFloat(), Path.Direction.CW)
                }
                c.save(); c.clipPath(path); c.drawBitmap(bb, 0f, 0f, paint); c.restore()
            }
            Transition.SPIN -> {
                c.save(); c.rotate(e * 25f, k.cx, k.cy); val sa = 1f + e * 0.6f; c.scale(sa, sa, k.cx, k.cy)
                paint.alpha = (255 * (1f - e)).toInt(); c.drawBitmap(ba, 0f, 0f, paint); c.restore()
                c.save(); c.rotate((e - 1f) * 25f, k.cx, k.cy); val sb = 1.6f - e * 0.6f; c.scale(sb, sb, k.cx, k.cy)
                paint.alpha = (255 * e).toInt(); c.drawBitmap(bb, 0f, 0f, paint); c.restore()
                paint.alpha = 255
            }
        }
    }

    private fun overlays(c: Canvas, s: Scene, tMs: Long) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignette)
        if (p.storyMode) { // cinematic letterbox
            val bar = h * 0.055f
            k.fill.color = Color.BLACK
            c.drawRect(0f, 0f, w.toFloat(), bar, k.fill); c.drawRect(0f, h - bar, w.toFloat(), h.toFloat(), k.fill)
        }
        val hasSubtitle = p.storyMode && s.type == SceneType.KEN_BURNS && s.title.isNotBlank()
        if (!s.type.showsOwnText && !hasSubtitle) when (p.captionMode) {
            com.gk.facelessstudio.model.CaptionMode.FULL -> captions(c, s, tMs)
            com.gk.facelessstudio.model.CaptionMode.KEYWORDS -> keywordPops(c, s, tMs)
            com.gk.facelessstudio.model.CaptionMode.OFF -> {}
        }
        if (p.grain) {
            val r = Random(tMs / 42)
            grainPaint.shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(r.nextFloat() * 128, r.nextFloat() * 128); postScale(k.u * 1.5f, k.u * 1.5f) })
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grainPaint)
        }
        if (p.progressBar && p.durationMs > 0) {
            val prog = k.clamp01(tMs.toFloat() / p.durationMs)
            val bh = 5f * k.u
            k.fill.color = k.alpha(Color.WHITE, 0.18f)
            c.drawRect(0f, h - bh, w.toFloat(), h.toFloat(), k.fill)
            k.fill.color = k.theme.accent2
            c.drawRect(0f, h - bh, w * prog, h.toFloat(), k.fill)
            c.drawCircle(w * prog, h - bh / 2, bh * 1.3f, k.fill)
        }
        if (p.handle.isNotBlank() && s.type != SceneType.OUTRO) {
            val handle = if (p.handle.startsWith("@")) p.handle else "@${p.handle}"
            k.setText(k.fonts.bold, 18f * k.u, k.alpha(Color.WHITE, 0.55f), handle)
            k.text.textAlign = Paint.Align.RIGHT
            c.drawText(handle, w - 24f * k.u, 38f * k.u, k.text)
        }
    }

    /** Only the key words pop up when they are spoken – keeps the screen for the visuals. */
    private fun keywordPops(c: Canvas, s: Scene, tMs: Long) {
        val words = s.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || s.emphasis.isEmpty()) return
        val t = (tMs - s.startMs) / 1000f
        val d = s.durationMs / 1000f * 0.95f
        val weights = words.map { it.length + 2f }
        val total = weights.sum()
        val starts = FloatArray(words.size).also { var a = 0f; for (i in words.indices) { it[i] = a / total * d; a += weights[i] } }
        val em = s.emphasis.map { it.lowercase() }
        val hits = words.indices.filter { ScriptAnalyzer.cleanWord(words[it]).lowercase() in em }.distinctBy { ScriptAnalyzer.cleanWord(words[it]).lowercase() }
        for ((n, wi) in hits.withIndex()) {
            val st = starts[wi]
            val life = 1.6f
            if (t < st || t > st + life) continue
            val pin = k.outBack(k.seg(t, st, st + 0.2f))
            val pout = k.seg(t, st + life - 0.2f, st + life)
            val word = ScriptAnalyzer.cleanWord(words[wi]).uppercase()
            val size = (if (k.portrait) 54f else 44f) * k.u
            k.setText(k.fonts.headline, size, 0xFF0A0A0A.toInt(), word)
            val tw = k.text.measureText(word)
            val x = k.cx + (if (n % 2 == 0) -1 else 1) * (if (k.portrait) 0f else w * 0.12f)
            val y = if (k.portrait) h * 0.76f else h * 0.84f
            c.save()
            val sc = pin * (1f - pout * 0.3f)
            c.scale(sc, sc, x, y); c.rotate(if (n % 2 == 0) -3f else 3f, x, y)
            k.fill.color = k.alpha(if (n % 2 == 0) k.theme.accent else k.theme.accent2, 1f - pout)
            k.rect.set(x - tw / 2 - 22f * k.u, y - size * 0.95f, x + tw / 2 + 22f * k.u, y + size * 0.35f)
            c.drawRoundRect(k.rect, 12f * k.u, 12f * k.u, k.fill)
            k.text.textAlign = Paint.Align.CENTER
            k.text.color = if (n % 2 == 0) 0xFF0A0A0A.toInt() else Color.WHITE
            k.text.alpha = (255 * (1f - pout)).toInt()
            c.drawText(word, x, y, k.text)
            c.restore()
        }
    }

    /** Word-by-word karaoke captions. */
    private fun captions(c: Canvas, s: Scene, tMs: Long) {
        val words = s.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val t = (tMs - s.startMs) / 1000f
        val d = s.durationMs / 1000f * 0.95f
        val weights = words.map { it.length + 2f }
        val total = weights.sum()
        var acc = 0f
        var cur = words.lastIndex
        for (i in words.indices) { if (acc / total * d > t) { cur = max(0, i - 1); break }; acc += weights[i] }
        val pageSize = if (k.portrait) 4 else 6
        val page = cur / pageSize
        val from = page * pageSize; val to = min(words.size, from + pageSize)
        val size = (if (k.portrait) 46f else 38f) * k.u
        k.setText(k.fonts.headline, size, Color.WHITE, s.text)
        val pageWords = words.subList(from, to)
        val lines = k.wrap(pageWords.joinToString(" "), w * 0.86f).take(2)
        val lh = size * 1.18f
        val baseY = if (k.portrait) h * 0.74f else h * 0.86f
        val y0 = baseY - (lines.size - 1) * lh
        val em = s.emphasis.map { it.lowercase() }.toSet()
        var wi = from
        val space = k.text.measureText(" ")
        val pageStart = run { var a = 0f; for (i in 0 until from) a += weights[i]; a / total * d }
        val pop = k.outBack(k.seg(t, pageStart, pageStart + 0.18f))
        c.save(); c.scale(0.85f + 0.15f * pop, 0.85f + 0.15f * pop, k.cx, baseY)
        lines.forEachIndexed { li, line ->
            val lw = k.text.measureText(line)
            var x = k.cx - lw / 2
            for (wd in line.split(" ")) {
                val y = y0 + li * lh
                val clean = ScriptAnalyzer.cleanWord(wd).lowercase()
                val active = wi == cur
                k.text.style = Paint.Style.STROKE; k.text.strokeWidth = size * 0.16f; k.text.color = Color.BLACK
                k.text.strokeJoin = Paint.Join.ROUND
                c.drawText(wd, x, y, k.text)
                k.text.style = Paint.Style.FILL
                k.text.color = when { active -> k.theme.accent; clean in em -> k.theme.accent2.let { k.mix(it, Color.WHITE, 0.25f) }; else -> Color.WHITE }
                c.drawText(wd, x, y, k.text)
                x += k.text.measureText(wd) + space
                wi++
            }
        }
        c.restore()
        k.text.style = Paint.Style.FILL
    }
}
