package com.kahani.studio3d.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import com.kahani.studio3d.data.CaptionPosition
import com.kahani.studio3d.data.CaptionStyle
import com.kahani.studio3d.data.ColorFilter
import com.kahani.studio3d.data.Motion
import com.kahani.studio3d.data.Project
import com.kahani.studio3d.data.Transition
import java.io.File
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws one frame of the video at time [timeMs] onto a canvas of size [w]x[h].
 * Same code is used by the live preview and by the MP4 exporter, so
 * "what you see is what you export".
 */
class FrameRenderer(
    private var project: Project,
    val w: Int,
    val h: Int,
) {
    private var starts = LongArray(project.scenes.size)
    private val camera = Camera().apply { setLocation(0f, 0f, -40f) }
    private val tmpM = Matrix()
    private val m3d = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val vignettePaint = Paint()
    private val grainPaint = Paint()
    private var grain: Bitmap? = null
    private val rnd = Random(7)

    // Cover-size target with headroom for zoom/pan/3D rotation.
    private val targetW = (w * 1.15f).toInt()
    private val targetH = (h * 1.15f).toInt()
    private val cache = object : LruCache<String, Bitmap>(5) {}

    private val wordCache = HashMap<Int, Pair<List<String>, LongArray>>()

    init { setProject(project) }

    private var applied: Project? = null

    fun setProject(p: Project) {
        if (applied === p) return
        applied = p
        project = p
        if (starts.size != p.scenes.size) starts = LongArray(p.scenes.size)
        wordCache.clear()
        var acc = 0L
        p.scenes.forEachIndexed { i, s -> starts[i] = acc; acc += s.durationMs }
        bmpPaint.colorFilter = colorFilter(p.colorFilter)
        vignettePaint.shader = RadialGradient(
            w / 2f, h / 2f, max(w, h) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        if (p.filmGrain && grain == null) grain = makeGrain()
    }

    val durationMs: Long get() = max(project.totalDurationMs, project.voiceDurationMs)

    fun release() { cache.evictAll() }

    // ----------------------------------------------------------------- bitmaps

    private fun bitmapFor(i: Int): Bitmap? {
        val path = project.scenes.getOrNull(i)?.imagePath ?: return null
        val key = path + "@" + File(path).lastModified()
        cache.get(key)?.let { return it }
        val b = decodeCover(path, targetW, targetH) ?: return null
        cache.put(key, b)
        return b
    }

    /** Preload upcoming images so export never stalls. */
    fun prefetch(i: Int) { bitmapFor(i); bitmapFor(i + 1) }

    // ----------------------------------------------------------------- frame

    fun draw(c: Canvas, timeMs: Long) {
        val scenes = project.scenes
        c.drawColor(Color.BLACK)
        if (scenes.isEmpty()) return
        val t = timeMs.coerceIn(0, durationMs)
        var idx = scenes.lastIndex
        for (i in scenes.indices) if (t < starts[i] + scenes[i].durationMs) { idx = i; break }
        val local = t - starts[idx]
        val trans = scenes[idx].transition ?: project.transition
        val tMs = min(project.transitionMs, scenes[idx].durationMs / 2)

        if (idx > 0 && local < tMs && trans != Transition.NONE) {
            val p = ease(local.toFloat() / tMs)
            val prev = idx - 1
            val prevProg = (scenes[prev].durationMs + local).toFloat() / scenes[prev].durationMs
            drawTransition(c, trans, p,
                { drawScene(c, prev, prevProg) },
                { drawScene(c, idx, local.toFloat() / scenes[idx].durationMs) })
        } else {
            drawScene(c, idx, local.toFloat() / scenes[idx].durationMs)
        }

        // overlays
        if (project.vignette) c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vignettePaint)
        if (project.filmGrain) drawGrain(c)
        if (project.letterbox) {
            fillPaint.shader = null; fillPaint.color = Color.BLACK
            val bar = h * 0.075f
            c.drawRect(0f, 0f, w.toFloat(), bar, fillPaint)
            c.drawRect(0f, h - bar, w.toFloat(), h.toFloat(), fillPaint)
        }
        drawCaption(c, idx, local)
        drawWatermark(c)
    }

    private inline fun drawTransition(c: Canvas, tr: Transition, p: Float, a: () -> Unit, b: () -> Unit) {
        when (tr) {
            Transition.CROSSFADE, Transition.NONE -> {
                a()
                c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (p * 255).toInt())
                b(); c.restore()
            }
            Transition.SLIDE -> {
                c.save(); c.translate(-p * w, 0f); a(); c.restore()
                c.save(); c.translate((1 - p) * w, 0f); b(); c.restore()
            }
            Transition.ZOOM_THROUGH -> {
                b()
                c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), ((1 - p) * 255).toInt())
                val s = 1f + p * 0.8f
                c.scale(s, s, w / 2f, h / 2f); a(); c.restore()
            }
            Transition.FLASH, Transition.DIP_BLACK -> {
                if (p < 0.5f) a() else b()
                val alpha = (1f - kotlin.math.abs(p - 0.5f) * 2f)
                fillPaint.shader = null
                fillPaint.color = if (tr == Transition.FLASH) Color.WHITE else Color.BLACK
                fillPaint.alpha = (alpha * 255).toInt()
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fillPaint)
                fillPaint.alpha = 255
            }
            Transition.CUBE_3D -> {
                c.save(); c.concat(cubeMatrix(-90f * p, w.toFloat(), -p * w)); a(); c.restore()
                c.save(); c.concat(cubeMatrix(90f * (1 - p), 0f, (1 - p) * w)); b(); c.restore()
            }
        }
    }

    private fun cubeMatrix(deg: Float, pivotX: Float, shift: Float): Matrix {
        camera.save(); camera.rotateY(deg); camera.getMatrix(m3d); camera.restore()
        m3d.preTranslate(-pivotX, -h / 2f)
        m3d.postTranslate(pivotX + shift, h / 2f)
        return m3d
    }

    private fun drawScene(c: Canvas, i: Int, progressRaw: Float) {
        val b = bitmapFor(i)
        if (b == null) {
            // placeholder: gradient + scene number so the user sees what is missing
            fillPaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                Color.rgb(40, 24, 90), Color.rgb(120, 30, 90), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fillPaint)
            fillPaint.shader = null
            textPaint.color = Color.argb(160, 255, 255, 255)
            textPaint.textSize = min(w, h) * 0.08f
            textPaint.textAlign = Paint.Align.CENTER
            c.drawText("Scene ${i + 1} — image missing", w / 2f, h * 0.4f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            return
        }
        val scene = project.scenes[i]
        val motion = if (scene.motion == Motion.AUTO) Motion.autoCycle[i % Motion.autoCycle.size] else scene.motion
        val p = progressRaw.coerceIn(0f, 1.2f)
        val e = ease(p.coerceAtMost(1f)) + (p - p.coerceAtMost(1f)) * 0.3f
        val k = project.motionIntensity

        var zoom = 1.08f
        var dx = 0f; var dy = 0f; var rot = 0f; var rx = 0f; var ry = 0f
        when (motion) {
            Motion.ZOOM_IN -> zoom = 1.05f + 0.25f * k * e
            Motion.ZOOM_OUT -> zoom = 1.05f + 0.25f * k * (1 - e)
            Motion.PAN_LEFT -> { zoom = 1.12f + 0.12f * k; dx = (0.5f - e) * 2f }
            Motion.PAN_RIGHT -> { zoom = 1.12f + 0.12f * k; dx = (e - 0.5f) * 2f }
            Motion.PAN_UP -> { zoom = 1.12f + 0.12f * k; dy = (0.5f - e) * 2f }
            Motion.PAN_DOWN -> { zoom = 1.12f + 0.12f * k; dy = (e - 0.5f) * 2f }
            Motion.TILT_3D -> { zoom = 1.28f + 0.08f * e; ry = (e - 0.5f) * 16f * k; rx = (0.5f - e) * 5f * k }
            Motion.ORBIT_3D -> {
                zoom = 1.3f + 0.1f * e
                ry = sin(e * PI.toFloat() * 2f - PI.toFloat() / 2f) * 10f * k
                rx = sin(e * PI.toFloat()) * 6f * k
            }
            Motion.DOLLY_ROTATE -> { zoom = 1.12f + 0.25f * k * e; rot = (e - 0.5f) * 5f * k }
            Motion.SHAKE -> {
                zoom = 1.15f
                val tt = p * 40f
                dx = (sin(tt * 1.3f) * 0.5f + sin(tt * 2.7f) * 0.3f) * 0.25f * k
                dy = (sin(tt * 1.7f) * 0.5f + sin(tt * 3.1f) * 0.3f) * 0.25f * k
                rot = sin(tt * 0.9f) * 0.8f * k
            }
            Motion.STATIC -> zoom = 1.02f + 0.03f * e
            Motion.AUTO -> {}
        }
        val base = max(w.toFloat() / b.width, h.toFloat() / b.height)
        val s = base * zoom
        // maximum pan that still keeps the image covering the frame
        val maxDx = ((b.width * s - w) / 2f).coerceAtLeast(0f)
        val maxDy = ((b.height * s - h) / 2f).coerceAtLeast(0f)

        tmpM.reset()
        tmpM.postTranslate(-b.width / 2f, -b.height / 2f)
        tmpM.postScale(s, s)
        if (rot != 0f) tmpM.postRotate(rot)
        if (rx != 0f || ry != 0f) {
            camera.save(); camera.rotateX(rx); camera.rotateY(ry); camera.getMatrix(m3d); camera.restore()
            tmpM.postConcat(m3d)
        }
        tmpM.postTranslate(w / 2f + dx * maxDx * 0.9f, h / 2f + dy * maxDy * 0.9f)
        c.drawBitmap(b, tmpM, bmpPaint)
    }

    // ----------------------------------------------------------------- captions

    /** Word list + start offset (ms) of every word inside the scene. */
    private fun words(i: Int): Pair<List<String>, LongArray> = wordCache.getOrPut(i) {
        val sc = project.scenes[i]
        val ws = sc.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val weights = ws.map { it.length + 2f }
        val total = weights.sum().coerceAtLeast(1f)
        val st = LongArray(ws.size)
        var acc = 0f
        ws.indices.forEach { k -> st[k] = (acc / total * sc.durationMs).toLong(); acc += weights[k] }
        ws to st
    }

    fun invalidateCaptions() = wordCache.clear()

    private fun drawCaption(c: Canvas, i: Int, local: Long) {
        val style = project.captionStyle
        if (style == CaptionStyle.NONE) return
        val (ws, st) = words(i)
        if (ws.isEmpty()) return
        var cur = 0
        for (k in ws.indices) if (local >= st[k]) cur = k
        val base = min(w, h) * 0.072f * project.captionSize
        val y = when (project.captionPosition) {
            CaptionPosition.BOTTOM -> h * (if (h > w) 0.74f else 0.80f)
            CaptionPosition.CENTER -> h * 0.5f
            CaptionPosition.TOP -> h * 0.18f
        }
        when (style) {
            CaptionStyle.POP_WORD -> {
                val since = local - st[cur]
                val pop = 1f + 0.35f * (1f - (since / 140f).coerceIn(0f, 1f))
                textPaint.textSize = base * 1.6f * pop
                strokePaint.textSize = textPaint.textSize
                strokePaint.strokeWidth = textPaint.textSize * 0.16f
                val word = ws[cur].uppercase()
                val tw = textPaint.measureText(word)
                val x = (w - tw) / 2f
                c.drawText(word, x, y, strokePaint)
                textPaint.color = if (cur % 3 == 0) Color.rgb(255, 230, 40) else if (cur % 3 == 1) Color.WHITE else Color.rgb(120, 255, 170)
                c.drawText(word, x, y, textPaint)
            }
            else -> {
                val per = when (style) {
                    CaptionStyle.CLASSIC -> if (h > w) 7 else 10
                    else -> if (h > w) 4 else 6
                }
                val group = cur / per
                val from = group * per
                val to = min(ws.size, from + per)
                val size = if (style == CaptionStyle.CLASSIC) base * 0.78f else base * 1.08f
                textPaint.textSize = size
                strokePaint.textSize = size
                strokePaint.strokeWidth = size * 0.15f
                val space = textPaint.measureText(" ")
                val maxW = w * 0.86f
                // wrap words into lines
                val lines = ArrayList<MutableList<Int>>()
                var line = ArrayList<Int>(); var lw = 0f
                for (k in from until to) {
                    val ww = textPaint.measureText(ws[k])
                    if (line.isNotEmpty() && lw + space + ww > maxW) { lines += line; line = ArrayList(); lw = 0f }
                    lw += (if (line.isEmpty()) 0f else space) + ww
                    line.add(k)
                }
                if (line.isNotEmpty()) lines += line
                val lh = size * 1.25f
                val top = y - (lines.size - 1) * lh / 2f
                // fade-in of a new caption group
                val groupStart = st[from]
                val appear = ((local - groupStart) / 120f).coerceIn(0f, 1f)
                lines.forEachIndexed { li, ln ->
                    val lineW = ln.sumOf { textPaint.measureText(ws[it]).toDouble() }.toFloat() + space * (ln.size - 1)
                    var x = (w - lineW) / 2f
                    val by = top + li * lh
                    if (style == CaptionStyle.BOLD_BOX) {
                        fillPaint.shader = null
                        fillPaint.color = Color.argb((200 * appear).toInt(), 15, 10, 30)
                        c.drawRoundRect(RectF(x - size * 0.4f, by - size * 1.0f, x + lineW + size * 0.4f, by + size * 0.32f), size * 0.3f, size * 0.3f, fillPaint)
                    }
                    for (k in ln) {
                        val word = ws[k]
                        val ww = textPaint.measureText(word)
                        val active = k == cur && style != CaptionStyle.CLASSIC
                        if (style == CaptionStyle.BOLD_BOX && active) {
                            fillPaint.color = Color.rgb(124, 77, 255)
                            c.drawRoundRect(RectF(x - size * 0.12f, by - size * 0.92f, x + ww + size * 0.12f, by + size * 0.24f), size * 0.2f, size * 0.2f, fillPaint)
                        }
                        if (style != CaptionStyle.BOLD_BOX) {
                            strokePaint.alpha = (255 * appear).toInt()
                            c.drawText(word, x, by, strokePaint)
                        }
                        textPaint.color = when {
                            style == CaptionStyle.KARAOKE_YELLOW && active -> Color.rgb(255, 222, 0)
                            else -> Color.WHITE
                        }
                        textPaint.alpha = (255 * appear).toInt()
                        c.drawText(word, x, by, textPaint)
                        x += ww + space
                    }
                }
                textPaint.alpha = 255; strokePaint.alpha = 255
            }
        }
    }

    private fun drawWatermark(c: Canvas) {
        val wm = project.watermark.trim()
        if (wm.isEmpty()) return
        textPaint.textSize = min(w, h) * 0.035f
        textPaint.color = Color.argb(170, 255, 255, 255)
        val tw = textPaint.measureText(wm)
        val y = if (project.letterbox) h * 0.075f + textPaint.textSize * 1.6f else textPaint.textSize * 2.2f
        c.drawText(wm, w - tw - textPaint.textSize, y, textPaint)
    }

    private fun drawGrain(c: Canvas) {
        val g = grain ?: return
        val shader = BitmapShader(g, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val m = Matrix(); m.setTranslate(rnd.nextInt(256).toFloat(), rnd.nextInt(256).toFloat())
        shader.setLocalMatrix(m)
        grainPaint.shader = shader
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grainPaint)
    }

    private fun makeGrain(): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val px = IntArray(256 * 256) {
            val v = rnd.nextInt(256)
            Color.argb(rnd.nextInt(10, 34), v, v, v)
        }
        b.setPixels(px, 0, 256, 0, 0, 256, 256)
        return b
    }

    companion object {
        fun ease(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

        fun colorFilter(f: ColorFilter): ColorMatrixColorFilter? {
            val cm = ColorMatrix()
            when (f) {
                ColorFilter.NONE -> return null
                ColorFilter.VIVID -> cm.setSaturation(1.35f)
                ColorFilter.WARM -> cm.set(floatArrayOf(1.1f, 0f, 0f, 0f, 12f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, 0.88f, 0f, -6f, 0f, 0f, 0f, 1f, 0f))
                ColorFilter.COOL -> cm.set(floatArrayOf(0.9f, 0f, 0f, 0f, -4f, 0f, 1.0f, 0f, 0f, 2f, 0f, 0f, 1.12f, 0f, 12f, 0f, 0f, 0f, 1f, 0f))
                ColorFilter.CINEMATIC -> {
                    cm.setSaturation(1.15f)
                    cm.postConcat(ColorMatrix(floatArrayOf(1.08f, 0.02f, -0.05f, 0f, 6f, 0f, 1.0f, 0.02f, 0f, 0f, -0.05f, 0.08f, 1.06f, 0f, 8f, 0f, 0f, 0f, 1f, 0f)))
                }
                ColorFilter.VINTAGE -> cm.set(floatArrayOf(0.9f, 0.3f, 0.1f, 0f, 10f, 0.25f, 0.8f, 0.1f, 0f, 6f, 0.2f, 0.25f, 0.55f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
                ColorFilter.BW -> { cm.setSaturation(0f); cm.postConcat(ColorMatrix(floatArrayOf(1.15f, 0f, 0f, 0f, -12f, 0f, 1.15f, 0f, 0f, -12f, 0f, 0f, 1.15f, 0f, -12f, 0f, 0f, 0f, 1f, 0f))) }
            }
            return ColorMatrixColorFilter(cm)
        }

        /** Decode an image so that it at least covers [tw]x[th] (no wasted memory). */
        fun decodeCover(path: String, tw: Int, th: Int): Bitmap? {
            if (!File(path).exists()) return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth <= 0) return null
            var sample = 1
            while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val raw = BitmapFactory.decodeFile(path, opts) ?: return null
            val s = max(tw.toFloat() / raw.width, th.toFloat() / raw.height)
            if (s < 1f && s > 0f) {
                val scaled = Bitmap.createScaledBitmap(raw, (raw.width * s).toInt().coerceAtLeast(1), (raw.height * s).toInt().coerceAtLeast(1), true)
                if (scaled != raw) raw.recycle()
                return scaled
            }
            return raw
        }
    }
}
