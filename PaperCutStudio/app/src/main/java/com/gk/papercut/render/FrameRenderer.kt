package com.gk.papercut.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import com.gk.papercut.data.CameraMove
import com.gk.papercut.data.EnterAnim
import com.gk.papercut.data.Layer
import com.gk.papercut.data.Project
import com.gk.papercut.data.ProjectRepository
import com.gk.papercut.data.Scene
import com.gk.papercut.data.Transition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws any moment of the video onto a Canvas. The same code drives the live preview and the MP4 export,
 * so what you preview is exactly what you export.
 */
class FrameRenderer(val project: Project, private val repo: ProjectRepository) {

    val outW = project.aspect.w
    val outH = project.aspect.h
    val duration: Double = max(project.audioDuration, project.scenes.lastOrNull()?.end ?: 0.0)

    private class PLayer(val layer: Layer, val styled: PaperStyle.Styled, val enterAt: Float, val reactAt: Float, val seed: Int)
    private class Prepared(val scene: Scene, val bg: Bitmap?, val layers: List<PLayer>)

    private val cache = object : LinkedHashMap<String, Prepared>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Prepared>?) = size > 3
    }

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paperColor = Color.rgb(246, 238, 220)

    // ------------------------------------------------------------------ public

    fun sceneIndexAt(t: Double): Int {
        val s = project.scenes
        if (s.isEmpty()) return -1
        for (i in s.indices.reversed()) if (t >= s[i].start) return i
        return 0
    }

    /** Loads the scene at time [t] and the next one so playback does not stutter. */
    fun prefetch(t: Double) {
        val i = sceneIndexAt(t)
        if (i < 0) return
        prepared(project.scenes[i])
        project.scenes.getOrNull(i + 1)?.let { prepared(it) }
    }

    fun render(canvas: Canvas, t: Double) {
        canvas.drawColor(paperColor)
        val scenes = project.scenes
        if (scenes.isEmpty()) return
        val i = sceneIndexAt(t)
        val cur = scenes[i]
        val tl = (t - cur.start).toFloat()
        val trDur = 0.7f
        if (i > 0 && tl < trDur && cur.transition != Transition.CUT) {
            val prev = scenes[i - 1]
            drawTransition(canvas, prev, cur, (tl / trDur).coerceIn(0f, 1f), tl)
        } else {
            drawScene(canvas, cur, tl)
        }
        if (project.captions) drawCaption(canvas, cur, t)
    }

    // ------------------------------------------------------------------ scenes

    private fun prepared(scene: Scene): Prepared = synchronized(cache) {
        val key = scene.hashCode().toString()
        cache[key]?.let { return it }
        val grain = project.paperTexture
        val bgRaw = repo.loadBitmap(project.id, scene.backgroundFile ?: scene.imageFile)
        val bg = bgRaw?.let { PaperStyle.styleBackground(it, grain).also { _ -> it.recycle() } }
        val visible = if (scene.backgroundFile != null) scene.layers.filter { it.visible } else emptyList()
        val dur = scene.duration.toFloat().coerceAtLeast(1f)
        val autoOrder = visible.filter { it.enter != EnterAnim.NONE }
        val stagger = min(0.2f, dur * 0.2f / max(1, autoOrder.size))
        val layers = visible.mapNotNull { l ->
            val bmp = repo.loadBitmap(project.id, l.file) ?: return@mapNotNull null
            // Entrances happen right at the scene start (while the transition is still moving), so the
            // repainted background behind an element is never left on screen waiting for a word.
            val enterAt = if (l.enter == EnterAnim.NONE) -10f else 0.05f + autoOrder.indexOf(l).coerceAtLeast(0) * stagger
            // The element's word makes it react (hop) exactly when it is spoken.
            val reactAt = if (l.keywordIndex >= 0 && l.keywordIndex < scene.words.size)
                (scene.words[l.keywordIndex].start - scene.start).toFloat() - 0.05f else -1f
            PLayer(l, PaperStyle.style(bmp, grain).also { bmp.recycle() }, enterAt, reactAt, l.id.hashCode())
        }
        Prepared(scene, bg, layers).also { cache[key] = it }
    }

    /** Draws one scene at local time [tl] (seconds since the scene started). */
    private fun drawScene(canvas: Canvas, scene: Scene, tl: Float) {
        val p = prepared(scene)
        val bg = p.bg
        if (bg == null) { drawPlaceholder(canvas, scene); return }

        val stepT = if (project.stopMotion) floor(tl * 12f) / 12f else tl
        val step = (tl * 12f).toInt()
        val u = (tl / scene.duration.toFloat().coerceAtLeast(0.5f)).coerceIn(0f, 1f)

        // Camera (Ken Burns) + parallax: cut-outs move slightly more than the background.
        val base = max(outW / bg.width.toFloat(), outH / bg.height.toFloat())
        var zoom = 1f; var panX = 0f
        when (scene.camera) {
            CameraMove.ZOOM_IN -> zoom = 1f + 0.08f * Ease.inOut(u)
            CameraMove.ZOOM_OUT -> zoom = 1.08f - 0.08f * Ease.inOut(u)
            CameraMove.PAN_LEFT -> { zoom = 1.08f; panX = (0.5f - u) * 0.06f * outW }
            CameraMove.PAN_RIGHT -> { zoom = 1.08f; panX = (u - 0.5f) * 0.06f * outW }
            CameraMove.STATIC -> {}
        }
        fun place(parallax: Float, block: () -> Unit) {
            canvas.save()
            canvas.translate(outW / 2f - panX * parallax, outH / 2f)
            val z = 1f + (zoom - 1f) * parallax
            canvas.scale(base * z, base * z)
            canvas.translate(-bg.width / 2f, -bg.height / 2f)
            block()
            canvas.restore()
        }
        place(1f) { canvas.drawBitmap(bg, 0f, 0f, bmpPaint) }

        val s = base * zoom
        place(1.06f) {
            for (pl in p.layers) {
                val local = stepT - pl.enterAt
                if (local < 0f) continue
                val l = pl.layer
                val xf = Xf()
                // Movement amounts are in output pixels; divide by scale because we draw in image space.
                Motion.enter(l.enter, local / Motion.ENTER_SEC, outW / s, outH / s, xf)
                if (local > Motion.ENTER_SEC) Motion.idle(l.idle, local - Motion.ENTER_SEC, l.h, outW / s, xf)
                if (pl.reactAt >= 0f) Motion.react(stepT - pl.reactAt, l.h, xf)
                if (project.stopMotion) Motion.boil(pl.seed, step / 2, xf)
                drawLayer(canvas, pl, xf)
            }
        }
    }

    private fun drawLayer(canvas: Canvas, pl: PLayer, xf: Xf) {
        val l = pl.layer
        val st = pl.styled
        val cx = l.x + l.w / 2f
        val cy = if (xf.pivotBottom) l.y + l.h else l.y + l.h / 2f
        canvas.save()
        canvas.translate(cx + xf.dx, cy + xf.dy)
        canvas.rotate(xf.rot)
        canvas.scale(xf.sx, xf.sy)
        bmpPaint.alpha = (xf.alpha.coerceIn(0f, 1f) * 255).toInt()
        val left = -l.w / 2f - st.pad
        val top = (if (xf.pivotBottom) -l.h else -l.h / 2f) - st.pad
        canvas.drawBitmap(st.bitmap, left, top, bmpPaint)
        bmpPaint.alpha = 255
        canvas.restore()
    }

    private fun drawTransition(canvas: Canvas, prev: Scene, cur: Scene, p: Float, tl: Float) {
        val prevT = prev.duration.toFloat()
        val e = Ease.inOut(p)
        when (cur.transition) {
            Transition.PAPER_SLIDE -> {
                drawScene(canvas, prev, prevT)
                val x = (1 - e) * outW
                canvas.save(); canvas.translate(x, 0f)
                // Paper edge shadow
                fill.shader = LinearGradient(-30f, 0f, 0f, 0f, Color.TRANSPARENT, Color.argb(90, 0, 0, 0), Shader.TileMode.CLAMP)
                canvas.drawRect(-30f, 0f, 0f, outH.toFloat(), fill); fill.shader = null
                canvas.clipRect(0f, 0f, outW.toFloat(), outH.toFloat())
                canvas.drawColor(paperColor)
                drawScene(canvas, cur, tl)
                canvas.restore()
            }
            Transition.PAPER_FLIP -> {
                val first = p < 0.5f
                val sx = abs(cos(p * PI.toFloat()))
                canvas.save(); canvas.scale(max(sx, 0.001f), 1f, outW / 2f, outH / 2f)
                if (first) drawScene(canvas, prev, prevT) else drawScene(canvas, cur, tl)
                canvas.restore()
            }
            Transition.FOLD -> {
                drawScene(canvas, prev, prevT)
                canvas.save()
                canvas.translate(0f, -(1 - Ease.outBack(p, 1.1f)) * outH)
                canvas.rotate((1 - p) * -4f, outW / 2f, 0f)
                canvas.clipRect(0f, 0f, outW.toFloat(), outH.toFloat()); canvas.drawColor(paperColor)
                drawScene(canvas, cur, tl)
                canvas.restore()
            }
            Transition.ZOOM -> {
                val sc = 1f + e * 0.6f
                canvas.save(); canvas.scale(sc, sc, outW / 2f, outH / 2f); drawScene(canvas, prev, prevT); canvas.restore()
                val layer = canvas.saveLayerAlpha(0f, 0f, outW.toFloat(), outH.toFloat(), (e * 255).toInt())
                val s2 = 0.85f + 0.15f * e
                canvas.scale(s2, s2, outW / 2f, outH / 2f); canvas.drawColor(paperColor); drawScene(canvas, cur, tl)
                canvas.restoreToCount(layer)
            }
            Transition.CUT -> drawScene(canvas, cur, tl)
        }
    }

    private val placeholderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 90, 60); typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }

    private fun drawPlaceholder(canvas: Canvas, scene: Scene) {
        placeholderPaint.textSize = min(outW, outH) * 0.045f
        val idx = project.scenes.indexOfFirst { it.id == scene.id } + 1
        val msg = "Scene $idx – import an image\n\n${scene.text}"
        val layout = android.text.StaticLayout.Builder
            .obtain(msg, 0, msg.length, placeholderPaint, (outW * 0.8f).toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build()
        canvas.save()
        canvas.translate(outW * 0.1f, (outH - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    // ------------------------------------------------------------------ captions

    private val capPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.rgb(30, 25, 20)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 196, 46) }

    /**
     * Caption groups of up to [per] words that break after sentence ends and never leave a
     * lonely 1-2 word group at the end ("पा रहा।" is merged into the group before it).
     */
    private fun captionChunks(ws: List<String>, per: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (i in ws.indices) {
            val n = i - start + 1
            val sentenceEnd = ws[i].last() in ".!?।|"
            if (n >= per || (sentenceEnd && n >= 2) || i == ws.lastIndex) { out += start to i + 1; start = i + 1 }
        }
        // Merge short trailing pieces (and sentence leftovers of 1 word) into the previous group.
        var k = out.size - 1
        while (k > 0) {
            val (a, b) = out[k]
            if (b - a <= 2 && out[k - 1].second - out[k - 1].first + (b - a) <= per + 2) {
                out[k - 1] = out[k - 1].first to b; out.removeAt(k)
            }
            k--
        }
        return out
    }

    private fun drawCaption(canvas: Canvas, scene: Scene, t: Double) {
        val words = scene.words
        if (words.isEmpty()) return
        val cur = words.indexOfLast { it.start <= t }.coerceAtLeast(0)
        if (t > words.last().end + 0.6) return
        val per = if (outW < outH) 3 else 5
        val chunks = captionChunks(words.map { it.text }, per)
        val (chunkStart, chunkEnd) = chunks.first { cur < it.second }
        val chunk = words.subList(chunkStart, chunkEnd)
        val appear = ((t - chunk.first().start) / 0.15).toFloat().coerceIn(0f, 1f)

        capPaint.textSize = min(outW, outH) * 0.058f
        val space = capPaint.measureText(" ")
        val widths = chunk.map { capPaint.measureText(it.text) }
        val total = widths.sum() + space * (chunk.size - 1)
        val padX = capPaint.textSize * 0.6f; val padY = capPaint.textSize * 0.4f
        val boxW = total + padX * 2; val boxH = capPaint.textSize + padY * 2
        val cx = outW / 2f; val cy = outH - outH * (if (outW < outH) 0.2f else 0.11f)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(if (chunks.indexOfFirst { it.first == chunkStart } % 2 == 0) -1.5f else 1.2f)
        val s = Ease.outBack(appear, 2f)
        canvas.scale(s, s)
        val box = RectF(-boxW / 2, -boxH / 2, boxW / 2, boxH / 2)
        fill.color = Color.argb(70, 0, 0, 0)
        canvas.drawRoundRect(RectF(box).apply { offset(5f, 7f) }, 10f, 10f, fill)
        fill.color = Color.rgb(253, 251, 245)
        canvas.drawRoundRect(box, 10f, 10f, fill)
        var x = -total / 2
        val baseline = capPaint.textSize * 0.35f
        chunk.forEachIndexed { k, w ->
            if (chunkStart + k == cur) {
                canvas.drawRoundRect(x - 6, -boxH / 2 + padY * 0.5f, x + widths[k] + 6, boxH / 2 - padY * 0.5f, 8f, 8f, markPaint)
            }
            capPaint.color = if (chunkStart + k <= cur) Color.rgb(30, 25, 20) else Color.rgb(120, 110, 100)
            canvas.drawText(w.text, x, baseline, capPaint)
            x += widths[k] + space
        }
        canvas.restore()
    }
}
