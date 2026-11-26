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
    /** A camera framing inside one scene: centre (image px) and zoom on top of "cover" scale. */
    private class Shot(val start: Float, val cx: Float, val cy: Float, val zoom: Float)
    private class Prepared(val scene: Scene, val bg: Bitmap?, val layers: List<PLayer>, val shots: List<Shot>)

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
        Prepared(scene, bg, layers, planShots(scene, bg, layers)).also { cache[key] = it }
    }

    /** Draws one scene at local time [tl] (seconds since the scene started). */
    private fun drawScene(canvas: Canvas, scene: Scene, tl: Float) {
        val p = prepared(scene)
        val bg = p.bg
        if (bg == null) { drawPlaceholder(canvas, scene); return }

        val stepT = if (project.stopMotion) floor(tl * 12f) / 12f else tl
        val step = (tl * 12f).toInt()
        val u = (tl / scene.duration.toFloat().coerceAtLeast(0.5f)).coerceIn(0f, 1f)

        // Camera: shot framing (wide / close-up per sentence) + gentle drift, parallax on the cut-outs.
        val base = max(outW / bg.width.toFloat(), outH / bg.height.toFloat())
        val cam = camera(p, tl, base)
        val zoom = cam[2]
        fun place(parallax: Float, block: () -> Unit) {
            canvas.save()
            canvas.translate(outW / 2f, outH / 2f)
            val z = 1f + (zoom - 1f) * parallax
            canvas.scale(base * z, base * z)
            canvas.translate(-cam[0], -cam[1])
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

    /**
     * Splits a long scene into shots at sentence ends: wide → close-up on the element the sentence
     * talks about (or the next hero) → wide … so a 9-second image never feels frozen.
     */
    private fun planShots(scene: Scene, bg: Bitmap?, layers: List<PLayer>): List<Shot> {
        if (bg == null) return emptyList()
        val iw = bg.width.toFloat(); val ih = bg.height.toFloat()
        val wide = Shot(0f, iw / 2, ih / 2, 1f)
        val words = scene.words
        if (words.isEmpty() || scene.duration < 4.5) return listOf(wide)
        val heroes = layers.map { it.layer }.filter { it.w < iw * 0.8f && it.h < ih * 0.95f }
            .sortedWith(compareBy({ if (it.enter == EnterAnim.NONE) 1 else 0 }, { -(it.w * it.h) }))
        if (heroes.isEmpty()) return listOf(wide)
        // Sentence boundaries -> shot starts (at least 2.5 s per shot, 2 s left at the end).
        val starts = mutableListOf(0 to 0f)
        for (i in 0 until words.size - 1) {
            if (words[i].text.last() !in ".!?।|") continue
            val t = (words[i + 1].start - scene.start).toFloat() - 0.15f
            if (t - starts.last().second >= 2.5f && scene.duration - t >= 2.0f) starts += (i + 1) to t
        }
        if (starts.size == 1 && scene.duration >= 7.0) {
            // One long sentence: cut to a close-up halfway.
            val mid = words.size / 2
            starts += mid to (words[mid].start - scene.start).toFloat() - 0.15f
        }
        var heroTurn = 0
        return starts.mapIndexed { k, (firstWord, t) ->
            if (k == 0) return@mapIndexed wide
            if (k % 2 == 0) return@mapIndexed Shot(t, iw / 2, ih / 2, 1.06f) // back to a (slightly closer) wide
            val lastWord = starts.getOrNull(k + 1)?.first ?: words.size
            val target = heroes.firstOrNull { it.keywordIndex in firstWord until lastWord } ?: heroes[heroTurn++ % heroes.size]
            val z = min(ih / (target.h * 1.15f), iw / (target.w * 1.7f)).coerceIn(1.18f, 1.6f)
            Shot(t, target.x + target.w / 2, target.y + target.h * 0.42f, z)
        }
    }

    /** Camera centre x, y (image px) and zoom at local time [tl]; glides 0.7 s between shots. */
    private fun camera(p: Prepared, tl: Float, base: Float): FloatArray {
        val bg = p.bg!!
        val shots = p.shots.ifEmpty { listOf(Shot(0f, bg.width / 2f, bg.height / 2f, 1f)) }
        val j = shots.indexOfLast { tl >= it.start }.coerceAtLeast(0)
        val cur = shots[j]
        val prev = shots.getOrNull(j - 1) ?: cur
        val e = Ease.inOut(((tl - cur.start) / 0.7f).coerceIn(0f, 1f))
        var cx = prev.cx + (cur.cx - prev.cx) * e
        var cy = prev.cy + (cur.cy - prev.cy) * e
        var z = prev.zoom + (cur.zoom - prev.zoom) * e
        // Gentle drift inside the shot, using the scene's camera move.
        val shotEnd = shots.getOrNull(j + 1)?.start ?: p.scene.duration.toFloat()
        val u = ((tl - cur.start) / (shotEnd - cur.start).coerceAtLeast(0.5f)).coerceIn(0f, 1f)
        when (p.scene.camera) {
            CameraMove.ZOOM_IN -> z *= 1f + 0.05f * u
            CameraMove.ZOOM_OUT -> z *= 1.05f - 0.05f * u
            CameraMove.PAN_LEFT -> { z *= 1.06f; cx += (0.5f - u) * 0.05f * bg.width }
            CameraMove.PAN_RIGHT -> { z *= 1.06f; cx += (u - 0.5f) * 0.05f * bg.width }
            CameraMove.STATIC -> {}
        }
        // Keep the view inside the picture.
        val hw = outW / (2 * base * z); val hh = outH / (2 * base * z)
        cx = if (bg.width > 2 * hw) cx.coerceIn(hw, bg.width - hw) else bg.width / 2f
        cy = if (bg.height > 2 * hh) cy.coerceIn(hh, bg.height - hh) else bg.height / 2f
        return floatArrayOf(cx, cy, z)
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
                // Page turn: the old page folds away to the left, revealing the new one underneath
                // (no empty frame in between).
                drawScene(canvas, cur, tl)
                val wLeft = (1 - e) * outW
                if (wLeft > 1f) {
                    canvas.save()
                    canvas.clipRect(0f, 0f, wLeft, outH.toFloat())
                    canvas.scale(1f - e * 0.35f, 1f, 0f, outH / 2f)
                    drawScene(canvas, prev, prevT)
                    canvas.restore()
                    fill.shader = LinearGradient(wLeft, 0f, wLeft + 40f, 0f, Color.argb(110, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(wLeft, 0f, wLeft + 40f, outH.toFloat(), fill); fill.shader = null
                }
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

        capPaint.textSize = min(outW, outH) * 0.066f
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
