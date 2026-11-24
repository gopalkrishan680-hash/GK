package com.gk.facelessstudio.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.Scene
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

data class Theme(
    val id: String,
    val name: String,
    val bg: Int,
    val bg2: Int,
    val accent: Int,
    val accent2: Int,
    val text: Int = Color.WHITE,
    val card: Int = Color.WHITE,
    val ink: Int = 0xFF111111.toInt(),
    val paper: Int = 0xFFF3EFE6.toInt(),
    val desk: Int = 0xFF1F4FA8.toInt(),
)

object Themes {
    val all = listOf(
        Theme("neon", "Neon Green", 0xFF050805.toInt(), 0xFF0B1220.toInt(), 0xFF7CFF1F.toInt(), 0xFFE11D2E.toInt(), desk = 0xFF1F4FA8.toInt()),
        Theme("crimson", "Crimson Noir", 0xFF0A0506.toInt(), 0xFF160B0D.toInt(), 0xFFFF3B3B.toInt(), 0xFFFFC23B.toInt(), desk = 0xFF5A1620.toInt()),
        Theme("ocean", "Ocean Blue", 0xFF03080F.toInt(), 0xFF0A1830.toInt(), 0xFF2FB8FF.toInt(), 0xFFFF4D8D.toInt(), desk = 0xFF0E3B66.toInt()),
        Theme("gold", "Royal Gold", 0xFF0B0904.toInt(), 0xFF17130A.toInt(), 0xFFFFC23B.toInt(), 0xFFE11D2E.toInt(), desk = 0xFF3D2E12.toInt()),
        Theme("purple", "Purple Haze", 0xFF07040D.toInt(), 0xFF140C24.toInt(), 0xFFB45CFF.toInt(), 0xFF22E3C8.toInt(), desk = 0xFF2E1A55.toInt()),
        Theme("sunset", "Sunset Orange", 0xFF0C0603.toInt(), 0xFF1C0F08.toInt(), 0xFFFF7A1A.toInt(), 0xFF2F8CFF.toInt(), desk = 0xFF6B2E10.toInt()),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

class Fonts(assets: AssetManager?) {
    private fun load(a: AssetManager?, name: String, fallback: Typeface): Typeface =
        try { if (a == null) fallback else Typeface.createFromAsset(a, "fonts/$name") } catch (e: Exception) { fallback }

    val headline: Typeface = load(assets, "poppins_black_italic.ttf", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC))
    val heavy: Typeface = load(assets, "poppins_extrabold_italic.ttf", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC))
    val bold: Typeface = load(assets, "poppins_bold.ttf", Typeface.DEFAULT_BOLD)
    val body: Typeface = load(assets, "poppins_semibold.ttf", Typeface.DEFAULT)
    val condensed: Typeface = load(assets, "anton_regular.ttf", Typeface.DEFAULT_BOLD)
    val hand: Typeface = load(assets, "kalam_bold.ttf", Typeface.create(Typeface.SERIF, Typeface.ITALIC))
}

/** Loads project images scaled for the output size and caches them (thread-safe for reading). */
class ImageStore(private val paths: List<String>, private val maxEdge: Int) {
    val count get() = paths.size
    private val cache = object : LruCache<Int, Bitmap>(140 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val blurCache = LruCache<Int, Bitmap>(64)

    fun get(i: Int): Bitmap? {
        if (paths.isEmpty() || i < 0) return null
        val idx = i % paths.size
        cache.get(idx)?.let { return it }
        synchronized(this) {
            cache.get(idx)?.let { return it }
            val bmp = decode(paths[idx], maxEdge) ?: return null
            cache.put(idx, bmp)
            return bmp
        }
    }

    /** Cheap blur: shrink to a tiny bitmap, scale back up with filtering. */
    fun blurred(i: Int): Bitmap? {
        if (paths.isEmpty() || i < 0) return null
        val idx = i % paths.size
        blurCache.get(idx)?.let { return it }
        val src = get(idx) ?: return null
        val tiny = Bitmap.createScaledBitmap(src, 24, max(1, 24 * src.height / src.width), true)
        val mid = Bitmap.createScaledBitmap(tiny, 160, max(1, 160 * src.height / src.width), true)
        blurCache.put(idx, mid)
        return mid
    }

    companion object {
        fun decode(path: String, maxEdge: Int): Bitmap? = try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) { null }
    }
}

/** Shared drawing environment for one renderer thread. */
class Kit(
    val w: Int,
    val h: Int,
    val project: Project,
    val theme: Theme,
    val fonts: Fonts,
    val images: ImageStore,
) {
    val u = min(w, h) / 720f
    val landscape = w > h * 1.15f
    val portrait = h > w * 1.15f
    val cx = w / 2f
    val cy = h / 2f

    val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val rect = RectF()
    val rect2 = RectF()
    val path = Path()
    val matrix = Matrix()

    fun rnd(s: Scene, salt: Int = 0) = Random(s.variant * 7919L + salt)

    // ---------------- easing ----------------
    fun clamp01(v: Float) = v.coerceIn(0f, 1f)
    fun seg(t: Float, a: Float, b: Float) = clamp01((t - a) / max(0.0001f, b - a))
    fun outCubic(x: Float) = 1f - (1f - x).pow(3)
    fun inOutCubic(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    fun outBack(x: Float, s: Float = 1.70158f): Float { val c3 = s + 1f; val y = x - 1f; return 1f + c3 * y * y * y + s * y * y }
    fun outExpo(x: Float) = if (x >= 1f) 1f else 1f - 2f.pow(-10f * x)
    fun inOutExpo(x: Float) = when {
        x <= 0f -> 0f; x >= 1f -> 1f
        x < 0.5f -> 2f.pow(20f * x - 10f) / 2f
        else -> (2f - 2f.pow(-20f * x + 10f)) / 2f
    }
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun alpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((Color.alpha(color) * clamp01(a)).toInt() shl 24)
    fun mix(c1: Int, c2: Int, t: Float) = Color.argb(
        lerp(Color.alpha(c1).toFloat(), Color.alpha(c2).toFloat(), t).toInt(),
        lerp(Color.red(c1).toFloat(), Color.red(c2).toFloat(), t).toInt(),
        lerp(Color.green(c1).toFloat(), Color.green(c2).toFloat(), t).toInt(),
        lerp(Color.blue(c1).toFloat(), Color.blue(c2).toFloat(), t).toInt(),
    )

    // ---------------- text ----------------
    private fun isLatin(s: String) = s.all { it.code < 0x250 || it in "₹€£—–’‘“”…✓" }
    /** Anton/Poppins cover Latin; Poppins + Kalam also cover Devanagari, Anton does not. */
    fun safe(tf: Typeface, s: String) = if (tf == fonts.condensed && !isLatin(s)) fonts.heavy else tf

    fun setText(tf: Typeface, size: Float, color: Int, s: String = "") {
        text.reset(); text.isAntiAlias = true; text.isSubpixelText = true
        text.textAlign = Paint.Align.LEFT; text.letterSpacing = 0f; text.style = Paint.Style.FILL
        text.clearShadowLayer(); text.alpha = 255
        text.typeface = safe(tf, s); text.textSize = size; text.color = color
    }

    fun fitSize(s: String, tf: Typeface, maxW: Float, start: Float, min: Float = 14f * u): Float {
        text.typeface = safe(tf, s); text.textSize = start
        val wid = text.measureText(s)
        return if (wid <= maxW) start else max(min, start * maxW / wid)
    }

    fun wrap(s: String, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in s.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(trial) > maxW && line.isNotEmpty()) { out += line.toString(); line = StringBuilder(word) }
            else { line = StringBuilder(trial) }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    /** Draws a line word by word, painting emphasised words in [hi]. */
    fun drawRich(c: Canvas, line: String, x: Float, y: Float, emphasis: Collection<String>, base: Int, hi: Int, align: Paint.Align = Paint.Align.LEFT) {
        val total = text.measureText(line)
        var cx0 = when (align) { Paint.Align.CENTER -> x - total / 2; Paint.Align.RIGHT -> x - total; else -> x }
        val oldAlign = text.textAlign
        text.textAlign = Paint.Align.LEFT
        val space = text.measureText(" ")
        val em = emphasis.map { it.lowercase() }.toSet()
        for (word in line.split(" ")) {
            val clean = com.gk.facelessstudio.engine.ScriptAnalyzer.cleanWord(word).lowercase()
            text.color = if (clean.isNotEmpty() && clean in em) hi else base
            c.drawText(word, cx0, y, text)
            cx0 += text.measureText(word) + space
        }
        text.textAlign = oldAlign
    }

    fun glow(color: Int, radius: Float = 18f * u) = text.setShadowLayer(radius, 0f, 0f, alpha(color, 0.85f))
    fun shadow() = text.setShadowLayer(10f * u, 0f, 4f * u, 0xAA000000.toInt())

    // ---------------- images ----------------
    /** Draws [bmp] covering [dst] with Ken-Burns zoom/pan (pan in -1..1). */
    fun cover(c: Canvas, bmp: Bitmap, dst: RectF, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f, rot: Float = 0f, alpha: Float = 1f) {
        val s = max(dst.width() / bmp.width, dst.height() / bmp.height) * zoom
        val bw = bmp.width * s; val bh = bmp.height * s
        val ex = (bw - dst.width()) / 2f; val ey = (bh - dst.height()) / 2f
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate(dst.centerX() - bw / 2f + panX * ex, dst.centerY() - bh / 2f + panY * ey)
        if (rot != 0f) matrix.postRotate(rot, dst.centerX(), dst.centerY())
        c.save()
        c.clipRect(dst)
        bmpPaint.alpha = (255 * clamp01(alpha)).toInt()
        c.drawBitmap(bmp, matrix, bmpPaint)
        c.restore()
        bmpPaint.alpha = 255
    }

    fun coverRound(c: Canvas, bmp: Bitmap, dst: RectF, radius: Float, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f) {
        path.reset(); path.addRoundRect(dst, radius, radius, Path.Direction.CW)
        c.save(); c.clipPath(path); cover(c, bmp, dst, zoom, panX, panY); c.restore()
    }

    // ---------------- backgrounds ----------------
    fun bgBlob(c: Canvas, t: Float, seed: Int, color: Int = theme.accent) {
        c.drawColor(theme.bg)
        val r = Random(seed)
        repeat(3) { i ->
            val sp = 0.18f + r.nextFloat() * 0.25f
            val ph = r.nextFloat() * 6.28f
            val x = cx + cos(t * sp + ph) * w * 0.38f
            val y = cy + sin(t * sp * 1.3f + ph * 2) * h * 0.38f
            val rad = max(w, h) * (0.32f + 0.12f * i)
            fill.shader = RadialGradient(x, y, rad, intArrayOf(alpha(color, 0.55f - i * 0.12f), alpha(color, 0f)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        }
        fill.shader = null
    }

    fun bgGrid(c: Canvas, t: Float, spot: Boolean = true) {
        c.drawColor(theme.bg2)
        if (spot) {
            fill.shader = RadialGradient(cx, -h * 0.1f, max(w, h) * 0.9f, intArrayOf(alpha(theme.accent, 0.20f), 0), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill); fill.shader = null
        }
        stroke.shader = null
        stroke.color = alpha(Color.WHITE, 0.06f); stroke.strokeWidth = 1.5f * u
        val step = 60f * u
        val off = (t * 6f * u) % step
        var x = -off; while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), stroke); x += step }
        var y = -off; while (y < h) { c.drawLine(0f, y, w.toFloat(), y, stroke); y += step }
    }

    fun bgGlow(c: Canvas, t: Float) {
        c.drawColor(theme.bg)
        val pulse = 1f + 0.04f * sin(t * 1.6f)
        fill.shader = RadialGradient(cx, cy, max(w, h) * 0.75f * pulse,
            intArrayOf(theme.accent, mix(theme.accent, theme.bg, 0.55f), theme.bg), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill); fill.shader = null
        stroke.color = alpha(Color.BLACK, 0.10f); stroke.strokeWidth = 2f * u
        val step = 46f * u
        var x = 0f; while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), stroke); x += step }
        var y = 0f; while (y < h) { c.drawLine(0f, y, w.toFloat(), y, stroke); y += step }
    }

    fun bgDesk(c: Canvas) {
        fill.shader = RadialGradient(cx, cy * 0.8f, max(w, h) * 0.8f, intArrayOf(mix(theme.desk, Color.WHITE, 0.12f), mix(theme.desk, Color.BLACK, 0.55f)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill); fill.shader = null
    }

    fun bgImageBlur(c: Canvas, idx: Int, t: Float, dark: Float = 0.6f): Boolean {
        val b = images.blurred(idx) ?: return false
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        cover(c, b, rect, 1.15f + 0.03f * sin(t * 0.5f))
        c.drawColor(alpha(Color.BLACK, dark))
        return true
    }

    fun fadeEdge(c: Canvas, x0: Float, x1: Float, color: Int) {
        fill.shader = LinearGradient(x0, 0f, x1, 0f, color, alpha(color, 0f), Shader.TileMode.CLAMP)
        c.drawRect(min(x0, x1) - 1, 0f, max(x0, x1) + 1, h.toFloat(), fill); fill.shader = null
    }

    fun fadeEdgeV(c: Canvas, y0: Float, y1: Float, color: Int) {
        fill.shader = LinearGradient(0f, y0, 0f, y1, color, alpha(color, 0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, min(y0, y1) - 1, w.toFloat(), max(y0, y1) + 1, fill); fill.shader = null
    }

    /** Animated tick mark; progress 0..1 draws the stroke. */
    fun check(c: Canvas, x: Float, y: Float, size: Float, p: Float, color: Int) {
        if (p <= 0f) return
        stroke.color = color; stroke.strokeWidth = size * 0.2f
        val ax = x; val ay = y + size * 0.1f
        val bx = x + size * 0.35f; val by = y + size * 0.45f
        val cx1 = x + size; val cy1 = y - size * 0.45f
        val p1 = clamp01(p / 0.35f); val p2 = clamp01((p - 0.35f) / 0.65f)
        c.drawLine(ax, ay, lerp(ax, bx, p1), lerp(ay, by, p1), stroke)
        if (p2 > 0) c.drawLine(bx, by, lerp(bx, cx1, p2), lerp(by, cy1, p2), stroke)
    }

    fun magnifier(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = r * 0.32f
        c.drawCircle(x, y, r, stroke)
        c.drawLine(x + r * 0.7f, y + r * 0.7f, x + r * 1.6f, y + r * 1.6f, stroke)
    }

    fun particles(c: Canvas, t: Float, seed: Int, n: Int = 40, color: Int = Color.WHITE) {
        val r = Random(seed)
        repeat(n) {
            val x0 = r.nextFloat() * w; val y0 = r.nextFloat() * h
            val sp = (8f + r.nextFloat() * 22f) * u
            val y = (y0 - t * sp).mod(h.toFloat())
            val x = x0 + sin(t * 0.7f + it) * 10f * u
            fill.color = alpha(color, 0.15f + 0.35f * r.nextFloat())
            c.drawCircle(x, y, (1f + r.nextFloat() * 2.4f) * u, fill)
        }
    }

    fun rays(c: Canvas, x: Float, y: Float, t: Float, color: Int, n: Int = 14) {
        val len = max(w, h).toFloat()
        val rot = t * 12f
        fill.color = alpha(color, 0.10f)
        for (i in 0 until n) {
            val a0 = ((i * 360f / n + rot) * PI / 180).toFloat()
            val a1 = (((i + 0.5f) * 360f / n + rot) * PI / 180).toFloat()
            path.reset(); path.moveTo(x, y)
            path.lineTo(x + cos(a0) * len, y + sin(a0) * len)
            path.lineTo(x + cos(a1) * len, y + sin(a1) * len); path.close()
            c.drawPath(path, fill)
        }
    }

    /** Red/accent title banner like the reference "HIDDEN WORLD OF DARK WEB" box. */
    fun banner(c: Canvas, title: String, x: Float, y: Float, maxW: Float, size: Float, p: Float, color: Int = theme.accent2): Float {
        if (title.isBlank() || p <= 0f) return y
        setText(fonts.headline, size, Color.WHITE, title)
        val lines = wrap(title, maxW - size * 0.8f).take(2)
        val lh = size * 1.15f
        val bw = lines.maxOf { text.measureText(it) } + size * 0.8f
        val bh = lh * lines.size + size * 0.45f
        val off = (1f - outCubic(p)) * -(x + bw + 20f)
        rect.set(x + off, y, x + off + bw, y + bh)
        fill.color = color
        c.drawRoundRect(rect, 6f * u, 6f * u, fill)
        lines.forEachIndexed { i, l -> c.drawText(l, rect.left + size * 0.4f, y + size * 0.2f + lh * (i + 0.8f), text) }
        return y + bh
    }
}
