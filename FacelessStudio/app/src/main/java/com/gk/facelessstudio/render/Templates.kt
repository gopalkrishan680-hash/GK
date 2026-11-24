package com.gk.facelessstudio.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PathMeasure
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.gk.facelessstudio.engine.ScriptAnalyzer
import com.gk.facelessstudio.model.Scene
import com.gk.facelessstudio.model.SceneType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/** All scene layouts. `t` = seconds since scene start (can be <0 or >d during transitions), `d` = duration. */
object Templates {

    fun draw(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        when (s.type) {
            SceneType.KINETIC -> kinetic(k, c, s, t, d)
            SceneType.CHAPTER -> chapter(k, c, s, t, d)
            SceneType.KEN_BURNS -> kenBurns(k, c, s, t, d)
            SceneType.IMAGE_BANNER -> imageBanner(k, c, s, t, d)
            SceneType.QUESTION_LIST -> questionList(k, c, s, t, d)
            SceneType.CHECKLIST -> checklist(k, c, s, t, d)
            SceneType.MINDMAP -> mindmap(k, c, s, t, d)
            SceneType.PAPER_NOTE -> paperNote(k, c, s, t, d)
            SceneType.BIG_STAT -> bigStat(k, c, s, t, d)
            SceneType.DATE_STAMP -> dateStamp(k, c, s, t, d)
            SceneType.VERSUS -> versus(k, c, s, t, d)
            SceneType.QUOTE -> quote(k, c, s, t, d)
            SceneType.SPLIT_ARROW -> splitArrow(k, c, s, t, d)
            SceneType.POLAROID -> polaroid(k, c, s, t, d)
            SceneType.HEADLINE_IMAGE -> headlineImage(k, c, s, t, d)
            SceneType.PHONE -> phone(k, c, s, t, d)
            SceneType.SPOTLIGHT -> spotlight(k, c, s, t, d)
            SceneType.OUTRO -> outro(k, c, s, t, d)
            SceneType.DONUT -> InfoTemplates.donut(k, c, s, t, d)
            SceneType.BAR_CHART -> InfoTemplates.barChart(k, c, s, t, d)
            SceneType.TIMELINE -> InfoTemplates.timeline(k, c, s, t, d)
            SceneType.KEY_POINTS -> InfoTemplates.keyPoints(k, c, s, t, d)
        }
    }

    /** Start times (seconds) of each word, proportional to its length, across [span]. */
    private fun wordStarts(words: List<String>, span: Float): FloatArray {
        val w = words.map { it.length + 2f }
        val total = w.sum()
        val out = FloatArray(words.size)
        var acc = 0f
        for (i in words.indices) { out[i] = acc / total * span; acc += w[i] }
        return out
    }

    private fun typed(text: String, p: Float) = text.take((text.length * p.coerceIn(0f, 1f)).toInt())

    private fun background(k: Kit, c: Canvas, s: Scene, t: Float, pick: Int) {
        if (k.story) { if (!k.bgImageBlur(c, s.imageIndex, t, 0.62f)) k.bgBlob(c, t, s.variant); return }
        when (pick % 4) {
            0 -> k.bgBlob(c, t, s.variant)
            1 -> if (!k.bgImageBlur(c, s.imageIndex, t)) k.bgGrid(c, t)
            2 -> k.bgGrid(c, t)
            else -> { k.bgBlob(c, t, s.variant); k.particles(c, t, s.variant, 30) }
        }
    }

    // ------------------------------------------------------------------ KINETIC
    private fun kinetic(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val r = k.rnd(s)
        val mode = r.nextInt(4)
        background(k, c, s, t, r.nextInt(4))
        val raw = s.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val groups = mutableListOf<String>()
        val per = if (raw.size > 9) 3 else if (raw.size > 4) 2 else 1
        var i = 0
        while (i < raw.size) {
            var n = per
            if (per > 1 && raw[i].length > 9) n = 1
            groups += raw.subList(i, min(raw.size, i + n)).joinToString(" ")
            i += n
        }
        val starts = wordStarts(groups, d * 0.92f)
        val cur = starts.indexOfLast { it <= t }.coerceAtLeast(0)
        val localT = t - starts[cur]
        val em = s.emphasis.map { it.lowercase() }
        fun isEm(g: String) = g.split(" ").any { ScriptAnalyzer.cleanWord(it).lowercase() in em }
        val tf = if (r.nextBoolean()) k.fonts.headline else k.fonts.condensed
        val maxW = k.w * 0.84f

        when (mode) {
            0, 3 -> { // one phrase at a time, pop in
                val g = groups[cur].uppercase()
                val size = k.fitSize(g, tf, maxW, (if (k.portrait) 120f else 110f) * k.u)
                val p = k.seg(localT, 0f, 0.22f)
                val sc = if (mode == 0) k.lerp(1.45f, 1f, k.outBack(p)) else k.lerp(0.6f, 1f, k.outBack(p))
                k.setText(tf, size, if (isEm(g)) k.theme.accent else Color.WHITE, g)
                k.text.textAlign = Paint.Align.CENTER
                k.text.alpha = (255 * k.clamp01(p * 2)).toInt()
                k.glow(if (isEm(g)) k.theme.accent else Color.WHITE, 16f * k.u)
                c.save(); c.scale(sc, sc, k.cx, k.cy)
                c.drawText(g, k.cx, k.cy + size * 0.35f, k.text)
                c.restore()
            }
            1 -> { // stack: lines build up
                val visible = groups.subList(max(0, cur - 3), cur + 1)
                val size = min(84f * k.u, visible.minOf { k.fitSize(it.uppercase(), tf, maxW, 84f * k.u) })
                val lh = size * 1.12f
                val top = k.cy - (visible.size - 1) * lh / 2f
                visible.forEachIndexed { vi, g0 ->
                    val g = g0.uppercase()
                    val isCur = vi == visible.lastIndex
                    val p = if (isCur) k.seg(localT, 0f, 0.25f) else 1f
                    k.setText(tf, size, if (isEm(g)) k.theme.accent else if (isCur) Color.WHITE else k.alpha(Color.WHITE, 0.55f), g)
                    k.text.textAlign = Paint.Align.CENTER
                    k.text.alpha = (k.text.alpha * k.clamp01(p * 1.6f)).toInt()
                    k.shadow()
                    val y = top + vi * lh + (1f - k.outCubic(p)) * 40f * k.u
                    c.drawText(g, k.cx, y + size * 0.35f, k.text)
                }
            }
            else -> { // slide up through phrases
                for (j in max(0, cur - 1)..cur) {
                    val g = groups[j].uppercase()
                    val size = k.fitSize(g, tf, maxW, 100f * k.u)
                    val lt = t - starts[j]
                    val pin = k.seg(lt, 0f, 0.25f)
                    val pout = if (j < cur) k.seg(t - starts[cur], 0f, 0.25f) else 0f
                    val y = k.cy + size * 0.35f + (1f - k.outCubic(pin)) * 90f * k.u - k.outCubic(pout) * 90f * k.u
                    k.setText(tf, size, if (isEm(g)) k.theme.accent else Color.WHITE, g)
                    k.text.textAlign = Paint.Align.CENTER
                    k.text.alpha = (255 * k.clamp01(pin) * (1f - pout)).toInt()
                    k.glow(k.theme.accent, 10f * k.u)
                    c.drawText(g, k.cx, y, k.text)
                }
            }
        }
        k.text.clearShadowLayer()
    }

    // ------------------------------------------------------------------ CHAPTER
    private fun chapter(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val r = k.rnd(s)
        if (k.story && k.bgImageBlur(c, s.imageIndex, t, 0.62f)) k.particles(c, t, s.variant, 40, k.theme.accent)
        else {
            if (r.nextBoolean()) k.bgBlob(c, t, s.variant) else k.bgGlow(c, t)
            if (r.nextBoolean()) k.particles(c, t, s.variant, 50)
        }
        val title = s.title.uppercase().ifBlank { "NEXT" }
        val zoom = 1f + 0.05f * k.clamp01(t / max(1f, d))
        c.save(); c.scale(zoom, zoom, k.cx, k.cy)
        val tf = k.fonts.headline
        val size = k.fitSize(title, tf, k.w * 0.82f, 104f * k.u)
        k.setText(tf, size, Color.WHITE, title)
        val total = k.text.measureText(title)
        var x = k.cx - total / 2f
        k.glow(k.theme.accent, 22f * k.u)
        title.forEachIndexed { i, ch ->
            val p = k.seg(t, 0.04f * i, 0.04f * i + 0.35f)
            val cw = k.text.measureText(ch.toString())
            k.text.alpha = (255 * p).toInt()
            c.save()
            val sc = k.lerp(2.2f, 1f, k.outCubic(p))
            c.scale(sc, sc, x + cw / 2, k.cy)
            c.drawText(ch.toString(), x, k.cy + size * 0.35f - (1f - k.outCubic(p)) * 30f * k.u, k.text)
            c.restore()
            x += cw
        }
        k.text.clearShadowLayer()
        val up = k.outCubic(k.seg(t, 0.3f, 0.9f))
        k.fill.color = k.theme.accent
        k.rect.set(k.cx - total / 2f * up, k.cy + size * 0.6f, k.cx + total / 2f * up, k.cy + size * 0.6f + 8f * k.u)
        c.drawRoundRect(k.rect, 4f * k.u, 4f * k.u, k.fill)
        if (s.chapterIndex >= 0) {
            val label = "PART ${s.chapterIndex + 1}"
            k.setText(k.fonts.bold, 26f * k.u, k.theme.accent)
            k.text.textAlign = Paint.Align.CENTER
            k.text.letterSpacing = 0.4f * (1f - k.outCubic(k.seg(t, 0f, 0.6f))) + 0.2f
            k.text.alpha = (255 * k.seg(t, 0f, 0.4f)).toInt()
            c.drawText(label, k.cx, k.cy - size * 0.75f, k.text)
            k.text.letterSpacing = 0f
        }
        c.restore()
    }

    // ------------------------------------------------------------------ KEN BURNS
    private fun kenBurns(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val bmp = k.images.get(s.imageIndex) ?: return spotlight(k, c, s, t, d)
        val r = k.rnd(s)
        val p = k.clamp01((t + 0.4f) / (d + 0.8f))
        val e = k.inOutCubic(p) * 0.4f + p * 0.6f
        k.rect.set(0f, 0f, k.w.toFloat(), k.h.toFloat())
        if (k.story) {
            // slow, calm camera: push in towards a point of interest, pull back, or drift across
            val fx = (r.nextFloat() - 0.5f) * 1.4f; val fy = (r.nextFloat() - 0.5f) * 1.0f
            when (r.nextInt(5)) {
                0 -> k.cover(c, bmp, k.rect, k.lerp(1.04f, 1.2f, e), fx * e, fy * e)
                1 -> k.cover(c, bmp, k.rect, k.lerp(1.2f, 1.04f, e), fx * (1 - e), fy * (1 - e))
                2 -> k.cover(c, bmp, k.rect, 1.14f, k.lerp(-0.8f, 0.8f, e), fy * 0.3f)
                3 -> k.cover(c, bmp, k.rect, 1.14f, k.lerp(0.8f, -0.8f, e), fy * 0.3f)
                else -> k.cover(c, bmp, k.rect, k.lerp(1.08f, 1.16f, e), 0f, k.lerp(0.7f, -0.7f, e))
            }
            if (r.nextInt(4) == 0) { // warm light drifting over the scene
                val x = k.lerp(-k.w * 0.3f, k.w * 1.3f, p)
                k.fill.shader = RadialGradient(x, k.h * 0.15f, k.w * 0.5f, intArrayOf(k.alpha(k.theme.accent2, 0.16f), 0), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
            }
            k.subtitle(c, s.title, t, d)
            return
        }
        when (r.nextInt(6)) {
            0 -> k.cover(c, bmp, k.rect, k.lerp(1.06f, 1.28f, e))
            1 -> k.cover(c, bmp, k.rect, k.lerp(1.3f, 1.06f, e))
            2 -> k.cover(c, bmp, k.rect, 1.22f, k.lerp(-1f, 1f, e))
            3 -> k.cover(c, bmp, k.rect, 1.22f, k.lerp(1f, -1f, e))
            4 -> k.cover(c, bmp, k.rect, k.lerp(1.12f, 1.3f, e), k.lerp(-0.6f, 0.6f, e), k.lerp(0.5f, -0.5f, e))
            else -> k.cover(c, bmp, k.rect, k.lerp(1.15f, 1.25f, e), 0f, k.lerp(-1f, 1f, e), k.lerp(-1.2f, 1.2f, e))
        }
        if (r.nextInt(3) == 0) { // light leak sweep
            val x = k.lerp(-k.w * 0.5f, k.w * 1.5f, p)
            k.fill.shader = RadialGradient(x, k.h * 0.2f, k.w * 0.45f, intArrayOf(k.alpha(k.theme.accent, 0.22f), 0), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, k.w.toFloat(), k.h.toFloat(), k.fill); k.fill.shader = null
        }
        // every image scene also carries information, not only captions
        InfoTemplates.infoLayer(k, c, s, t, d, r.nextInt(4))
    }

    // ------------------------------------------------------------------ IMAGE + BANNER
    private fun imageBanner(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val bmp = k.images.get(s.imageIndex) ?: return spotlight(k, c, s, t, d)
        val r = k.rnd(s)
        k.bgGrid(c, t, spot = false)
        val zoom = k.lerp(1.04f, 1.14f, k.clamp01(t / d))
        val right = r.nextBoolean()
        if (!k.portrait) {
            val iw = k.w * 0.58f
            if (right) k.rect.set(k.w - iw, 0f, k.w.toFloat(), k.h.toFloat()) else k.rect.set(0f, 0f, iw, k.h.toFloat())
            k.cover(c, bmp, k.rect, zoom, if (right) -0.3f else 0.3f)
            if (right) k.fadeEdge(c, k.w - iw, k.w - iw + iw * 0.45f, k.theme.bg2) else k.fadeEdge(c, iw, iw - iw * 0.45f, k.theme.bg2)
            val x = if (right) 44f * k.u else k.w - k.w * 0.42f
            val maxW = k.w * 0.40f
            val bottom = k.banner(c, s.title, x, 54f * k.u, maxW, 34f * k.u, k.seg(t, 0f, 0.4f))
            body(k, c, s, t, d, x, bottom + 34f * k.u, maxW, 30f * k.u)
        } else {
            k.rect.set(0f, 0f, k.w.toFloat(), k.h * 0.56f)
            k.cover(c, bmp, k.rect, zoom)
            k.fadeEdgeV(c, k.h * 0.56f, k.h * 0.36f, k.theme.bg2)
            val bottom = k.banner(c, s.title, 40f * k.u, k.h * 0.58f, k.w - 80f * k.u, 40f * k.u, k.seg(t, 0f, 0.4f))
            body(k, c, s, t, d, 40f * k.u, bottom + 44f * k.u, k.w - 80f * k.u, 30f * k.u)
        }
    }

    /** Typewriter body text with emphasis colouring. */
    private fun body(k: Kit, c: Canvas, s: Scene, t: Float, d: Float, x: Float, y: Float, maxW: Float, size: Float) {
        k.setText(k.fonts.body, size, Color.WHITE, s.text)
        val lines = k.wrap(s.text, maxW).take(7)
        val total = lines.sumOf { it.length }
        var shown = (total * k.seg(t, 0.3f, max(0.9f, d * 0.75f))).toInt()
        val lh = size * 1.45f
        lines.forEachIndexed { i, l ->
            if (shown <= 0) return
            val part = l.take(shown); shown -= l.length
            k.drawRich(c, part, x, y + i * lh, s.emphasis, Color.WHITE, k.theme.accent)
            if (shown <= 0 && (t * 2).toInt() % 2 == 0) {
                k.fill.color = k.theme.accent
                val cx0 = x + k.text.measureText(part) + 4f * k.u
                c.drawRect(cx0, y + i * lh - size * 0.8f, cx0 + 3f * k.u, y + i * lh + size * 0.15f, k.fill)
            }
        }
    }

    // ------------------------------------------------------------------ QUESTION LIST
    private fun questionList(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val r = k.rnd(s)
        val bmp = k.images.get(s.imageIndex)
        k.bgGrid(c, t)
        val pad = 44f * k.u
        var maxW = k.w - pad * 2
        if (bmp != null && !k.portrait) {
            val iw = k.w * 0.42f
            k.rect.set(k.w - iw, 0f, k.w.toFloat(), k.h.toFloat())
            k.cover(c, bmp, k.rect, k.lerp(1.05f, 1.15f, k.clamp01(t / d)), -0.2f)
            k.fadeEdge(c, k.w - iw, k.w - iw * 0.55f, k.theme.bg2)
            maxW = k.w - iw - pad
        } else if (bmp != null) {
            k.rect.set(0f, k.h * 0.62f, k.w.toFloat(), k.h.toFloat())
            k.cover(c, bmp, k.rect, 1.1f)
            k.fadeEdgeV(c, k.h * 0.62f, k.h * 0.78f, k.theme.bg2)
        }
        var y = pad + 10f * k.u
        if (s.title.isNotBlank() && r.nextInt(3) != 0) y = k.banner(c, s.title, pad, pad, maxW, 32f * k.u, k.seg(t, 0f, 0.35f)) + 40f * k.u
        else y += 30f * k.u
        val lines = s.lines.ifEmpty { listOf(s.text) }
        val avail = (if (k.portrait && bmp != null) k.h * 0.6f else k.h - pad) - y
        val size = min((if (k.portrait) 34f else 30f) * k.u, avail / (lines.size * 1.55f))
        k.setText(k.fonts.body, size, Color.WHITE)
        val lh = size * 1.5f
        val starts = s.lineStarts.takeIf { it.size == lines.size } ?: lines.indices.map { it / lines.size.toFloat() }
        var row = 0
        lines.forEachIndexed { i, line ->
            val st = starts[i] * d
            val nx = if (i + 1 < lines.size) starts[i + 1] * d else d
            val typeDur = min(nx - st, 0.35f + line.length * 0.03f)
            val p = k.seg(t, st, st + typeDur)
            val wrapped = k.wrap(line, maxW)
            val cur = t >= st && t < nx
            wrapped.forEach { wl ->
                if (p > 0f) {
                    val part = typed(wl, p * 1.0f)
                    k.text.color = Color.WHITE
                    c.drawText(part, pad, y + row * lh, k.text)
                    if (cur) {
                        k.stroke.color = k.theme.accent2; k.stroke.strokeWidth = 3f * k.u
                        val lw = k.text.measureText(part)
                        c.drawLine(pad, y + row * lh + 8f * k.u, pad + lw, y + row * lh + 8f * k.u, k.stroke)
                    }
                }
                row++
            }
        }
    }

    // ------------------------------------------------------------------ CHECKLIST
    private fun checklist(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        k.bgGlow(c, t)
        val items = s.lines.ifEmpty { listOf(s.text) }.take(9)
        val cw = if (k.portrait) k.w * 0.82f else min(k.w * 0.40f, k.h * 0.85f)
        val itemSize = min(24f * k.u, (k.h * 0.62f) / (items.size * 2.1f))
        val headH = 92f * k.u
        val ch = headH + items.size * itemSize * 2.2f + 30f * k.u
        val p = k.seg(t, 0f, 0.4f)
        val sc = k.lerp(0.82f, 1f, k.outBack(p)) * (1f + 0.03f * k.clamp01(t / d))
        c.save(); c.scale(sc, sc, k.cx, k.cy)
        val left = k.cx - cw / 2; val top = k.cy - ch / 2
        k.fill.color = 0x55000000
        k.rect.set(left + 10f * k.u, top + 14f * k.u, left + cw + 10f * k.u, top + ch + 14f * k.u)
        c.drawRect(k.rect, k.fill)
        k.fill.color = k.theme.card
        k.rect.set(left, top, left + cw, top + ch); c.drawRect(k.rect, k.fill)
        k.fill.color = 0xFF151515.toInt()
        k.rect.set(left, top, left + cw, top + headH); c.drawRect(k.rect, k.fill)
        val title = s.title.ifBlank { "FULL GUIDE" }
        val ts = k.fitSize(title, k.fonts.headline, cw - 30f * k.u, 46f * k.u)
        k.setText(k.fonts.headline, ts, Color.WHITE, title); k.text.textAlign = Paint.Align.CENTER
        c.drawText(title, k.cx, top + headH * 0.58f, k.text)
        k.setText(k.fonts.heavy, 13f * k.u, Color.WHITE); k.text.textAlign = Paint.Align.CENTER
        c.drawText("FULL GUIDE", k.cx, top + headH * 0.85f, k.text)
        val cur = s.chapterIndex.coerceAtLeast(0)
        items.forEachIndexed { i, item ->
            val y = top + headH + 26f * k.u + i * itemSize * 2.2f + itemSize
            val ip = k.seg(t, 0.15f + i * 0.06f, 0.4f + i * 0.06f)
            c.save(); c.translate((1f - k.outCubic(ip)) * 30f * k.u, 0f)
            k.magnifier(c, left + 24f * k.u, y - itemSize * 0.35f, itemSize * 0.38f, 0xFF333333.toInt())
            val label = item.uppercase()
            val size = k.fitSize(label, k.fonts.heavy, cw - 110f * k.u, itemSize)
            k.setText(k.fonts.heavy, size, if (i == cur) k.theme.accent2 else 0xFF111111.toInt(), label)
            k.text.alpha = (255 * ip).toInt()
            c.drawText(label, left + 52f * k.u, y, k.text)
            if (i < cur) {
                val cp = if (i == cur - 1) k.seg(t, 0.5f, 0.9f) else 1f
                k.check(c, left + 58f * k.u + k.text.measureText(label), y - itemSize * 0.2f, itemSize * 1.2f, cp, 0xFF22C55E.toInt())
            }
            c.restore()
        }
        c.restore()
    }

    // ------------------------------------------------------------------ MIND MAP
    private fun mindmap(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        k.bgGrid(c, t, spot = true)
        val bmp = k.images.get(s.imageIndex)
        if (bmp != null) {
            val iw = if (k.portrait) k.w * 0.7f else k.w * 0.28f
            k.rect.set(k.w - iw - 30f * k.u, k.h - iw * 0.9f - 30f * k.u, k.w - 30f * k.u, k.h - 30f * k.u)
            k.coverRound(c, bmp, k.rect, 18f * k.u, 1.1f + 0.05f * k.clamp01(t / d))
        }
        val title = s.title.ifBlank { "WHY?" }
        val p0 = k.seg(t, 0f, 0.3f)
        val ts = k.fitSize(title, k.fonts.headline, k.w * 0.7f, 30f * k.u)
        k.setText(k.fonts.headline, ts, 0xFF111111.toInt(), title)
        val tw = k.text.measureText(title)
        val ty = if (k.portrait) k.h * 0.14f else k.h * 0.13f
        k.rect.set(k.cx - tw / 2 - 22f * k.u, ty - ts, k.cx + tw / 2 + 22f * k.u, ty + ts * 0.5f)
        c.save(); val sc = k.outBack(p0); c.scale(sc, sc, k.cx, ty)
        k.fill.color = k.theme.accent
        c.drawRoundRect(k.rect, 40f * k.u, 40f * k.u, k.fill)
        k.text.textAlign = Paint.Align.CENTER
        c.drawText(title, k.cx, ty + ts * 0.05f, k.text)
        c.restore()
        val items = s.lines.ifEmpty { listOf(s.text) }.take(5)
        val n = items.size
        val lineY = ty + ts * 1.6f
        val span = if (k.portrait) k.w * 0.0f else k.w * 0.74f
        k.stroke.color = Color.WHITE; k.stroke.strokeWidth = 2.5f * k.u
        if (!k.portrait) {
            val x0 = k.cx - span / 2; val x1 = k.cx + span / 2
            val lp = k.outCubic(k.seg(t, 0.25f, 0.6f))
            c.drawLine(k.cx, ty + ts * 0.5f, k.cx, lineY, k.stroke)
            c.drawLine(k.cx - (k.cx - x0) * lp, lineY, k.cx + (x1 - k.cx) * lp, lineY, k.stroke)
        }
        val reveal = max(0.6f, d * 0.6f)
        val hiDur = (d - reveal).coerceAtLeast(0.1f) / n
        items.forEachIndexed { i, item ->
            val st = 0.5f + i * (reveal - 0.5f) / n
            val ip = k.seg(t, st, st + 0.3f)
            val x: Float; val y: Float
            if (k.portrait) { x = k.cx; y = lineY + 70f * k.u + i * 110f * k.u }
            else { x = if (n == 1) k.cx else k.cx - span / 2 + span * i / (n - 1); y = lineY + 60f * k.u }
            if (!k.portrait) c.drawLine(x, lineY, x, lineY + 22f * k.u * ip, k.stroke)
            else c.drawLine(x, y - 46f * k.u, x, y - 46f * k.u + 40f * k.u * ip, k.stroke)
            val hl = t >= reveal + i * hiDur && t < reveal + (i + 1) * hiDur
            val size = k.fitSize(item, k.fonts.body, if (k.portrait) k.w * 0.8f else span / n - 10f * k.u, if (k.portrait) 36f * k.u else 24f * k.u)
            k.setText(k.fonts.body, size, if (hl) 0xFF111111.toInt() else Color.WHITE, item)
            k.text.textAlign = Paint.Align.CENTER
            val iw = k.text.measureText(item)
            if (hl) {
                k.fill.color = k.theme.accent
                k.rect.set(x - iw / 2 - 12f * k.u, y + 8f * k.u, x + iw / 2 + 12f * k.u, y + size * 1.55f)
                c.drawRoundRect(k.rect, 10f * k.u, 10f * k.u, k.fill)
            }
            k.text.alpha = (255 * ip).toInt()
            c.drawText(item, x, y + size * 1.15f + (1f - ip) * 20f * k.u, k.text)
        }
    }

    // ------------------------------------------------------------------ PAPER NOTE
    private fun paperNote(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val r = k.rnd(s)
        k.bgDesk(c)
        val notebook = r.nextBoolean()
        val angle = (r.nextFloat() - 0.5f) * 7f + sin(t * 0.9f) * 0.6f
        val pw = if (k.portrait) k.w * 0.84f else k.w * 0.46f
        val ph = if (k.portrait) k.h * 0.52f else k.h * 0.78f
        val enter = k.outCubic(k.seg(t, 0f, 0.45f))
        val sc = 1f + 0.04f * k.clamp01(t / d)
        c.save()
        c.translate(0f, (1f - enter) * k.h * 0.7f)
        c.rotate(angle, k.cx, k.cy); c.scale(sc, sc, k.cx, k.cy)
        val left = k.cx - pw / 2; val top = k.cy - ph / 2
        k.fill.color = 0x66000000
        k.rect.set(left + 12f * k.u, top + 16f * k.u, left + pw + 12f * k.u, top + ph + 16f * k.u); c.drawRect(k.rect, k.fill)
        k.fill.color = k.theme.paper
        k.rect.set(left, top, left + pw, top + ph); c.drawRect(k.rect, k.fill)
        if (notebook) {
            k.stroke.color = 0x332F6FD0; k.stroke.strokeWidth = 1.5f * k.u
            var ly = top + 70f * k.u
            while (ly < top + ph - 10f * k.u) { c.drawLine(left + 40f * k.u, ly, left + pw - 10f * k.u, ly, k.stroke); ly += 38f * k.u }
            k.fill.color = 0xFF2A2A2A.toInt()
            var hy = top + 30f * k.u
            while (hy < top + ph - 20f * k.u) { c.drawCircle(left + 18f * k.u, hy, 6f * k.u, k.fill); hy += 34f * k.u }
        }
        val text = if (s.text.length > 150 && s.title.isNotBlank()) s.title else s.text
        val size = if (k.portrait) 40f * k.u else 32f * k.u
        k.setText(k.fonts.hand, size, 0xFF1C1C1C.toInt(), text)
        val lines = k.wrap(text, pw - 90f * k.u).take(8)
        val total = lines.sumOf { it.length }
        var shown = (total * k.seg(t, 0.35f, max(0.9f, d * 0.7f))).toInt()
        val lh = size * 1.28f
        val y0 = top + (ph - lines.size * lh) / 2 + size
        lines.forEachIndexed { i, l ->
            if (shown <= 0) return@forEachIndexed
            val part = l.take(shown); shown -= l.length
            k.text.textAlign = Paint.Align.CENTER
            val lw = k.text.measureText(l)
            k.text.textAlign = Paint.Align.LEFT
            k.drawRich(c, part, k.cx - lw / 2, y0 + i * lh, s.emphasis, 0xFF1C1C1C.toInt(), k.theme.accent2)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ BIG STAT
    private fun bigStat(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val r = k.rnd(s)
        if (r.nextBoolean()) k.bgBlob(c, t, s.variant) else { if (!k.bgImageBlur(c, s.imageIndex, t, 0.7f)) k.bgBlob(c, t, s.variant) }
        k.rays(c, k.cx, k.cy, t, k.theme.accent)
        val prefix = s.lines.getOrElse(0) { "" }
        val value = s.lines.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val decimals = s.lines.getOrNull(2)?.toIntOrNull() ?: 0
        val grouped = s.lines.getOrNull(3)?.toBoolean() ?: true
        val suffix = s.lines.getOrElse(4) { "" }
        val countEnd = min(1.6f, d * 0.6f)
        val p = k.outExpo(k.seg(t, 0.1f, countEnd))
        val v = value * p
        val num = if (decimals > 0) String.format("%.${decimals}f", v)
        else if (grouped || value >= 10000) String.format("%,d", v.roundToLong()) else v.roundToLong().toString()
        val str = "$prefix$num$suffix"
        val finalStr = "$prefix" + (if (decimals > 0) String.format("%.${decimals}f", value) else if (grouped || value >= 10000) String.format("%,d", value.roundToLong()) else value.roundToLong().toString()) + suffix
        val size = k.fitSize(finalStr, k.fonts.headline, k.w * 0.84f, 170f * k.u)
        val punch = if (t > countEnd) 1f + 0.08f * (1f - k.seg(t, countEnd, countEnd + 0.25f)) else 1f
        c.save(); c.scale(punch, punch, k.cx, k.cy)
        k.setText(k.fonts.headline, size, k.theme.accent, str); k.text.textAlign = Paint.Align.CENTER
        k.glow(k.theme.accent, 24f * k.u)
        c.drawText(str, k.cx, k.cy + size * 0.3f, k.text)
        k.text.clearShadowLayer()
        c.restore()
        if (s.title.isNotBlank()) {
            val ls = k.fitSize(s.title, k.fonts.heavy, k.w * 0.8f, 40f * k.u)
            k.setText(k.fonts.heavy, ls, Color.WHITE, s.title); k.text.textAlign = Paint.Align.CENTER
            val lp = k.seg(t, 0.3f, 0.7f)
            k.text.alpha = (255 * lp).toInt()
            k.shadow()
            c.drawText(s.title, k.cx, k.cy + size * 0.3f + ls * 1.6f + (1f - lp) * 20f * k.u, k.text)
            k.text.clearShadowLayer()
        }
    }

    // ------------------------------------------------------------------ DATE STAMP
    private fun dateStamp(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val bmp = k.images.get(s.imageIndex)
        if (bmp != null) {
            k.rect.set(0f, 0f, k.w.toFloat(), k.h.toFloat())
            k.cover(c, bmp, k.rect, k.lerp(1.08f, 1.2f, k.clamp01(t / d)))
            c.drawColor(0x40000000)
        } else k.bgBlob(c, t, s.variant)
        val date = s.lines.getOrElse(0) { "" }
        val time = s.lines.getOrElse(1) { "" }
        if (date.isNotBlank()) {
            val size = if (bmp == null) 90f * k.u else 40f * k.u
            k.setText(k.fonts.heavy, size, 0xFF111111.toInt(), date)
            val shown = typed(date, k.seg(t, 0.1f, 0.1f + date.length * 0.06f))
            val tw = k.text.measureText(date)
            if (bmp == null) {
                k.text.color = Color.WHITE; k.text.textAlign = Paint.Align.CENTER; k.glow(k.theme.accent)
                c.drawText(shown, k.cx, k.cy + size * 0.35f, k.text); k.text.clearShadowLayer()
            } else {
                val x = 40f * k.u; val y = 40f * k.u
                val p = k.outBack(k.seg(t, 0f, 0.3f))
                k.rect.set(x, y, x + (tw + 36f * k.u) * p, y + size * 1.45f)
                k.fill.color = Color.WHITE; c.drawRect(k.rect, k.fill)
                c.drawText(shown, x + 18f * k.u, y + size * 1.08f, k.text)
            }
        }
        if (time.isNotBlank()) {
            val size = 44f * k.u
            k.setText(k.fonts.condensed, size, k.theme.accent, time)
            val tw = k.text.measureText(time)
            val x = k.w - tw - 60f * k.u; val y = 40f * k.u
            k.fill.color = 0xE6000000.toInt()
            k.rect.set(x - 18f * k.u, y, x + tw + 18f * k.u, y + size * 1.4f)
            c.drawRoundRect(k.rect, 8f * k.u, 8f * k.u, k.fill)
            k.glow(k.theme.accent, 10f * k.u)
            val shownTime = if ((t * 2).toInt() % 2 == 0) time else time.replace(':', ' ')
            k.text.alpha = (255 * k.seg(t, 0.3f, 0.6f)).toInt()
            c.drawText(shownTime, x, y + size * 1.1f, k.text)
            k.text.clearShadowLayer()
        }
    }

    // ------------------------------------------------------------------ VERSUS
    private fun versus(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        k.bgGlow(c, t)
        val a = s.lines.getOrElse(0) { "THIS" }; val b = s.lines.getOrElse(1) { "THAT" }
        val bw = if (k.portrait) k.w * 0.7f else k.w * 0.30f
        val bh = if (k.portrait) k.h * 0.2f else k.h * 0.32f
        val pa = k.outBack(k.seg(t, 0f, 0.4f)); val pb = k.outBack(k.seg(t, 0.15f, 0.55f))
        fun box(label: String, cx0: Float, cy0: Float, color: Int, p: Float, fromLeft: Boolean) {
            val off = (1f - p) * (if (fromLeft) -k.w * 0.6f else k.w * 0.6f)
            c.save()
            if (k.portrait) c.translate(0f, off) else c.translate(off, 0f)
            k.rect.set(cx0 - bw / 2, cy0 - bh / 2, cx0 + bw / 2, cy0 + bh / 2)
            k.fill.color = 0x55000000; c.save(); c.translate(8f * k.u, 10f * k.u); c.drawRoundRect(k.rect, 16f * k.u, 16f * k.u, k.fill); c.restore()
            k.fill.color = color; c.drawRoundRect(k.rect, 16f * k.u, 16f * k.u, k.fill)
            val size = k.fitSize(label, k.fonts.headline, bw * 0.86f, 62f * k.u)
            k.setText(k.fonts.headline, size, Color.WHITE, label); k.text.textAlign = Paint.Align.CENTER; k.shadow()
            c.drawText(label, cx0, cy0 + size * 0.35f, k.text); k.text.clearShadowLayer()
            c.restore()
        }
        if (k.portrait) {
            box(a, k.cx, k.h * 0.32f, k.theme.accent2, pa, true); box(b, k.cx, k.h * 0.68f, mix(k), pb, false)
        } else {
            box(a, k.w * 0.28f, k.cy, k.theme.accent2, pa, true); box(b, k.w * 0.72f, k.cy, mix(k), pb, false)
        }
        val pv = k.outBack(k.seg(t, 0.45f, 0.75f), 3f)
        if (pv > 0f) {
            c.save(); c.scale(pv, pv, k.cx, k.cy); c.rotate(sin(t * 3f) * 6f, k.cx, k.cy)
            k.fill.color = Color.WHITE; c.drawCircle(k.cx, k.cy, 50f * k.u, k.fill)
            k.setText(k.fonts.headline, 44f * k.u, 0xFF111111.toInt()); k.text.textAlign = Paint.Align.CENTER
            c.drawText("VS", k.cx, k.cy + 16f * k.u, k.text)
            c.restore()
        }
    }

    private fun mix(k: Kit) = k.mix(k.theme.accent, Color.BLACK, 0.35f)

    // ------------------------------------------------------------------ QUOTE
    private fun quote(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        if (!k.bgImageBlur(c, s.imageIndex, t, 0.72f)) k.bgBlob(c, t, s.variant)
        val q = s.lines.firstOrNull() ?: s.text
        val size = if (k.portrait) 46f * k.u else 40f * k.u
        k.setText(k.fonts.heavy, size, Color.WHITE, q)
        val maxW = k.w * if (k.portrait) 0.84f else 0.7f
        val lines = k.wrap(q, maxW).take(6)
        val lh = size * 1.3f
        val top = k.cy - lines.size * lh / 2 + size * 0.8f
        val qp = k.outBack(k.seg(t, 0f, 0.35f))
        k.setText(k.fonts.headline, 200f * k.u, k.theme.accent)
        k.text.alpha = (200 * qp).toInt()
        c.save(); c.scale(qp, qp, k.cx - maxW / 2, top - size * 1.2f)
        c.drawText("“", k.cx - maxW / 2 - 20f * k.u, top + 40f * k.u, k.text)
        c.restore()
        val words = lines.flatMap { it.split(" ") }
        val starts = wordStarts(words, d * 0.8f)
        var wi = 0
        k.setText(k.fonts.heavy, size, Color.WHITE, q)
        val space = k.text.measureText(" ")
        val em = s.emphasis.map { it.lowercase() }.toSet()
        lines.forEachIndexed { li, l ->
            val lw = k.text.measureText(l)
            var x = k.cx - lw / 2
            for (wd in l.split(" ")) {
                val p = k.seg(t, starts.getOrElse(wi) { 0f }, starts.getOrElse(wi) { 0f } + 0.25f)
                k.text.color = if (ScriptAnalyzer.cleanWord(wd).lowercase() in em) k.theme.accent else Color.WHITE
                k.text.alpha = (255 * p).toInt()
                c.drawText(wd, x, top + li * lh + (1f - p) * 14f * k.u, k.text)
                x += k.text.measureText(wd) + space
                wi++
            }
        }
    }

    // ------------------------------------------------------------------ CAUSE -> EFFECT
    private fun splitArrow(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val r = k.rnd(s)
        if (r.nextBoolean()) k.bgGlow(c, t) else k.bgBlob(c, t, s.variant)
        val bmp = k.images.get(s.imageIndex)
        val a = s.lines.getOrElse(0) { "" }; val b = s.lines.getOrElse(1) { "" }
        val cardW = if (k.portrait) k.w * 0.6f else k.w * 0.3f
        val cardH = cardW * 0.8f
        val ccx = if (k.portrait) k.cx else k.w * 0.26f
        val ccy = if (k.portrait) k.h * 0.3f else k.cy
        val pc = k.outBack(k.seg(t, 0f, 0.4f))
        c.save(); c.rotate(-4f * pc, ccx, ccy); c.scale(pc, pc, ccx, ccy)
        k.fill.color = 0x66000000
        k.rect.set(ccx - cardW / 2 + 10f * k.u, ccy - cardH / 2 + 12f * k.u, ccx + cardW / 2 + 10f * k.u, ccy + cardH / 2 + 12f * k.u)
        c.drawRect(k.rect, k.fill)
        k.fill.color = k.theme.paper
        k.rect.set(ccx - cardW / 2, ccy - cardH / 2, ccx + cardW / 2, ccy + cardH / 2); c.drawRect(k.rect, k.fill)
        if (bmp != null) {
            k.rect2.set(k.rect.left + 12f * k.u, k.rect.top + 12f * k.u, k.rect.right - 12f * k.u, k.rect.bottom - 12f * k.u)
            k.cover(c, bmp, k.rect2, 1.1f)
        } else {
            val size = k.fitSize(a, k.fonts.hand, cardW * 0.85f, 54f * k.u)
            k.setText(k.fonts.hand, size, 0xFF1C1C1C.toInt(), a); k.text.textAlign = Paint.Align.CENTER
            c.drawText(a, ccx, ccy + size * 0.35f, k.text)
        }
        k.fill.color = 0x88FFFFFF.toInt()
        c.save(); c.rotate(-8f, ccx, ccy - cardH / 2)
        k.rect.set(ccx - 50f * k.u, ccy - cardH / 2 - 16f * k.u, ccx + 50f * k.u, ccy - cardH / 2 + 16f * k.u); c.drawRect(k.rect, k.fill)
        c.restore(); c.restore()
        // arrow
        val pa = k.seg(t, 0.35f, 0.8f)
        val ax0: Float; val ay0: Float; val ax1: Float; val ay1: Float
        if (k.portrait) { ax0 = k.cx; ay0 = ccy + cardH / 2 + 30f * k.u; ax1 = k.cx; ay1 = k.h * 0.6f }
        else { ax0 = ccx + cardW / 2 + 30f * k.u; ay0 = k.cy; ax1 = k.w * 0.52f; ay1 = k.cy }
        k.path.reset(); k.path.moveTo(ax0, ay0)
        if (k.portrait) k.path.quadTo(ax0 + 60f * k.u, (ay0 + ay1) / 2, ax1, ay1) else k.path.quadTo((ax0 + ax1) / 2, ay0 - 70f * k.u, ax1, ay1)
        val pm = PathMeasure(k.path, false)
        val seg = Path(); pm.getSegment(0f, pm.length * pa, seg, true)
        k.stroke.color = Color.WHITE; k.stroke.strokeWidth = 7f * k.u
        c.drawPath(seg, k.stroke)
        if (pa >= 1f) {
            val pos = FloatArray(2); val tan = FloatArray(2)
            pm.getPosTan(pm.length, pos, tan)
            val ang = Math.toDegrees(kotlin.math.atan2(tan[1], tan[0]).toDouble()).toFloat()
            c.save(); c.rotate(ang, pos[0], pos[1])
            c.drawLine(pos[0], pos[1], pos[0] - 26f * k.u, pos[1] - 18f * k.u, k.stroke)
            c.drawLine(pos[0], pos[1], pos[0] - 26f * k.u, pos[1] + 18f * k.u, k.stroke)
            c.restore()
        }
        val pb = k.seg(t, 0.7f, 1.05f)
        if (pb > 0f && b.isNotBlank()) {
            val maxW = if (k.portrait) k.w * 0.86f else k.w * 0.4f
            k.setText(k.fonts.headline, 70f * k.u, Color.WHITE, b)
            val lines = k.wrap(b, maxW).take(3)
            val size = min(70f * k.u, lines.minOf { k.fitSize(it, k.fonts.headline, maxW, 70f * k.u) })
            k.setText(k.fonts.headline, size, Color.WHITE, b); k.shadow()
            val x = if (k.portrait) k.cx else k.w * 0.56f
            if (k.portrait) k.text.textAlign = Paint.Align.CENTER
            val y0 = (if (k.portrait) k.h * 0.68f else k.cy - (lines.size - 1) * size * 0.55f) + size * 0.35f
            val sc = k.lerp(1.3f, 1f, k.outBack(pb))
            k.text.alpha = (255 * pb).toInt()
            c.save(); c.scale(sc, sc, x, y0)
            lines.forEachIndexed { i, l -> c.drawText(l, x, y0 + i * size * 1.1f, k.text) }
            c.restore(); k.text.clearShadowLayer()
        }
    }

    // ------------------------------------------------------------------ POLAROID STACK
    private fun polaroid(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        if (!k.bgImageBlur(c, s.imageIndex, t, 0.55f)) k.bgBlob(c, t, s.variant)
        val r = k.rnd(s)
        val n = k.images.count
        val idx = listOf(s.imageIndex - 1, s.imageIndex + 1, s.imageIndex).map { ((it % n) + n) % n }
        val cw = if (k.portrait) k.w * 0.62f else k.h * 0.62f
        val chh = cw * 1.08f
        val drift = 1f + 0.04f * k.clamp01(t / d)
        c.save(); c.scale(drift, drift, k.cx, k.cy)
        idx.forEachIndexed { i, ii ->
            val bmp = k.images.get(ii) ?: return@forEachIndexed
            val p = k.seg(t, i * 0.22f, i * 0.22f + 0.5f)
            if (p <= 0f) return@forEachIndexed
            val e = k.outBack(p, 1.2f)
            val finalRot = when (i) { 0 -> -9f; 1 -> 8f; else -> (r.nextFloat() - 0.5f) * 4f }
            val fx = k.cx + when (i) { 0 -> -cw * 0.42f; 1 -> cw * 0.42f; else -> 0f } * (if (k.portrait) 0.4f else 1f)
            val fy = k.cy + when (i) { 0 -> 10f; 1 -> -10f; else -> 0f } * k.u
            val y = k.lerp(-k.h * 0.8f, fy, e)
            c.save(); c.rotate(k.lerp(finalRot * 3, finalRot, e), fx, y)
            k.fill.color = 0x77000000
            k.rect.set(fx - cw / 2 + 10f * k.u, y - chh / 2 + 14f * k.u, fx + cw / 2 + 10f * k.u, y + chh / 2 + 14f * k.u); c.drawRect(k.rect, k.fill)
            k.fill.color = Color.WHITE
            k.rect.set(fx - cw / 2, y - chh / 2, fx + cw / 2, y + chh / 2); c.drawRect(k.rect, k.fill)
            k.rect2.set(k.rect.left + 14f * k.u, k.rect.top + 14f * k.u, k.rect.right - 14f * k.u, k.rect.bottom - 70f * k.u)
            k.cover(c, bmp, k.rect2, 1.05f)
            c.restore()
        }
        c.restore()
        if (s.title.isNotBlank()) k.banner(c, s.title, 40f * k.u, 40f * k.u, k.w * 0.6f, 30f * k.u, k.seg(t, 0.6f, 1f))
    }

    // ------------------------------------------------------------------ HEADLINE ON IMAGE
    private fun headlineImage(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val bmp = k.images.get(s.imageIndex) ?: return kinetic(k, c, s, t, d)
        val r = k.rnd(s)
        k.rect.set(0f, 0f, k.w.toFloat(), k.h.toFloat())
        k.cover(c, bmp, k.rect, k.lerp(1.2f, 1.06f, k.outCubic(k.clamp01(t / d))))
        c.drawColor(0x77000000)
        val title = s.title.ifBlank { s.emphasis.firstOrNull()?.uppercase() ?: "" }
        if (title.isBlank()) return
        if (k.story) { storyHeadline(k, c, title, t); return }
        val tf = if (r.nextBoolean()) k.fonts.condensed else k.fonts.headline
        val size = k.fitSize(title, tf, k.w * 0.86f, if (k.portrait) 150f * k.u else 170f * k.u)
        val p = k.seg(t, 0f, 0.7f)
        val y = if (r.nextBoolean()) k.cy + size * 0.35f else k.h * 0.78f
        k.setText(tf, size, Color.WHITE, title); k.text.textAlign = Paint.Align.CENTER
        k.text.letterSpacing = 0.35f * (1f - k.outCubic(p))
        k.text.style = Paint.Style.STROKE; k.text.strokeWidth = 3f * k.u; k.text.color = k.theme.accent
        k.text.alpha = (255 * k.clamp01(p * 2)).toInt()
        c.drawText(title, k.cx, y, k.text)
        val fp = k.seg(t, 0.45f, 0.9f)
        if (fp > 0f) {
            k.text.style = Paint.Style.FILL; k.text.color = Color.WHITE; k.text.alpha = (255 * fp).toInt()
            k.glow(k.theme.accent, 26f * k.u)
            c.drawText(title, k.cx, y, k.text)
            k.text.clearShadowLayer()
        }
        k.text.letterSpacing = 0f; k.text.style = Paint.Style.FILL
    }

    /** Big two-line title over a darkened picture, rising softly (used for the hook and short lines). */
    private fun storyHeadline(k: Kit, c: Canvas, title: String, t: Float) {
        k.fadeEdgeV(c, k.h.toFloat(), k.h * 0.35f, 0xCC000000.toInt())
        val maxW = k.w * 0.84f
        k.setText(k.fonts.headline, 90f * k.u, Color.WHITE, title)
        val lines = k.wrap(title, maxW).take(2)
        val size = min(if (k.portrait) 92f * k.u else 86f * k.u, lines.minOf { k.fitSize(it, k.fonts.headline, maxW, 86f * k.u) })
        val lh = size * 1.08f
        val base = (if (k.portrait) k.h * 0.72f else k.h * 0.8f) - (lines.size - 1) * lh
        lines.forEachIndexed { i, l ->
            val p = k.outCubic(k.seg(t, 0.15f + i * 0.18f, 0.7f + i * 0.18f))
            k.setText(k.fonts.headline, size, if (i == lines.lastIndex) k.theme.accent else Color.WHITE, l)
            k.text.textAlign = Paint.Align.CENTER
            k.text.alpha = (255 * p).toInt()
            k.text.setShadowLayer(14f * k.u, 0f, 4f * k.u, 0xDD000000.toInt())
            c.drawText(l, k.cx, base + i * lh + (1f - p) * 30f * k.u, k.text)
        }
        k.text.clearShadowLayer()
        val lp = k.outCubic(k.seg(t, 0.6f, 1.2f))
        k.fill.color = k.theme.accent
        c.drawRect(k.cx - 60f * k.u * lp, base + (lines.size - 1) * lh + size * 0.35f, k.cx + 60f * k.u * lp, base + (lines.size - 1) * lh + size * 0.35f + 5f * k.u, k.fill)
    }

    // ------------------------------------------------------------------ PHONE MOCKUP
    private fun phone(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val bmp = k.images.get(s.imageIndex) ?: return spotlight(k, c, s, t, d)
        k.bgGrid(c, t)
        val ph = if (k.portrait) k.h * 0.58f else k.h * 0.84f
        val pw = ph * 0.5f
        val px = if (k.portrait) k.cx else k.w * 0.27f
        val py = if (k.portrait) k.h * 0.36f else k.cy
        val p = k.outBack(k.seg(t, 0f, 0.45f), 1.1f)
        val float = sin(t * 1.4f) * 6f * k.u
        k.fill.shader = RadialGradient(px, py, pw * 1.3f, intArrayOf(k.alpha(k.theme.accent, 0.45f), 0), null, Shader.TileMode.CLAMP)
        c.drawCircle(px, py, pw * 1.3f, k.fill); k.fill.shader = null
        c.save(); c.translate(0f, (1f - p) * k.h + float); c.rotate(-3f + sin(t * 0.8f) * 1.5f, px, py)
        k.rect.set(px - pw / 2, py - ph / 2, px + pw / 2, py + ph / 2)
        k.fill.color = 0xFF0E0E0E.toInt(); c.drawRoundRect(k.rect, 44f * k.u, 44f * k.u, k.fill)
        k.stroke.color = 0xFF3A3A3A.toInt(); k.stroke.strokeWidth = 3f * k.u; c.drawRoundRect(k.rect, 44f * k.u, 44f * k.u, k.stroke)
        k.rect2.set(k.rect.left + 12f * k.u, k.rect.top + 12f * k.u, k.rect.right - 12f * k.u, k.rect.bottom - 12f * k.u)
        k.path.reset(); k.path.addRoundRect(k.rect2, 34f * k.u, 34f * k.u, Path.Direction.CW)
        c.save(); c.clipPath(k.path)
        k.cover(c, bmp, k.rect2, 1.15f, 0f, k.lerp(-1f, 1f, k.inOutCubic(k.clamp01(t / d))))
        c.restore()
        k.fill.color = 0xFF0E0E0E.toInt()
        k.rect.set(px - pw * 0.16f, k.rect2.top + 8f * k.u, px + pw * 0.16f, k.rect2.top + 30f * k.u)
        c.drawRoundRect(k.rect, 12f * k.u, 12f * k.u, k.fill)
        c.restore()
        if (k.portrait) {
            val bottom = k.banner(c, s.title, 40f * k.u, k.h * 0.70f, k.w - 80f * k.u, 38f * k.u, k.seg(t, 0.2f, 0.6f))
            body(k, c, s, t, d, 40f * k.u, bottom + 42f * k.u, k.w - 80f * k.u, 28f * k.u)
        } else {
            val x = k.w * 0.52f
            val bottom = k.banner(c, s.title, x, k.h * 0.18f, k.w * 0.42f, 34f * k.u, k.seg(t, 0.2f, 0.6f))
            body(k, c, s, t, d, x, bottom + 40f * k.u, k.w * 0.42f, 30f * k.u)
        }
    }

    // ------------------------------------------------------------------ SPOTLIGHT TEXT
    private fun spotlight(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        val r = k.rnd(s)
        k.bgGrid(c, t, spot = false)
        val sway = sin(t * 0.6f) * k.w * 0.05f
        k.path.reset()
        k.path.moveTo(k.cx - k.w * 0.05f + sway, 0f); k.path.lineTo(k.cx + k.w * 0.05f + sway, 0f)
        k.path.lineTo(k.cx + k.w * 0.45f, k.h.toFloat()); k.path.lineTo(k.cx - k.w * 0.45f, k.h.toFloat()); k.path.close()
        k.fill.shader = LinearGradient(0f, 0f, 0f, k.h.toFloat(), k.alpha(k.theme.accent, 0.28f), 0, Shader.TileMode.CLAMP)
        c.drawPath(k.path, k.fill); k.fill.shader = null
        k.particles(c, t, s.variant, 36, k.theme.accent)
        val leftAlign = r.nextBoolean() && !k.portrait
        val size = if (k.portrait) 54f * k.u else 50f * k.u
        k.setText(k.fonts.heavy, size, Color.WHITE, s.text)
        val maxW = k.w * if (leftAlign) 0.7f else 0.82f
        val lines = k.wrap(s.text, maxW).take(5)
        val lh = size * 1.25f
        val top = k.cy - (lines.size - 1) * lh / 2 + size * 0.35f
        val words = lines.flatMap { it.split(" ") }
        val starts = wordStarts(words, d * 0.8f)
        val em = s.emphasis.map { it.lowercase() }.toSet()
        val space = k.text.measureText(" ")
        val x0 = k.w * 0.12f
        if (leftAlign) {
            k.fill.color = k.theme.accent
            val bp = k.outCubic(k.seg(t, 0f, 0.4f))
            c.drawRect(x0 - 24f * k.u, top - size, x0 - 14f * k.u, top - size + (lines.size * lh + size * 0.2f) * bp, k.fill)
        }
        var wi = 0
        k.shadow()
        lines.forEachIndexed { li, l ->
            val lw = k.text.measureText(l)
            var x = if (leftAlign) x0 else k.cx - lw / 2
            for (wd in l.split(" ")) {
                val st = starts.getOrElse(wi) { 0f }
                val p = k.seg(t, st, st + 0.22f)
                k.text.color = if (ScriptAnalyzer.cleanWord(wd).lowercase() in em) k.theme.accent else Color.WHITE
                k.text.alpha = (255 * (0.18f + 0.82f * p)).toInt()
                c.drawText(wd, x, top + li * lh + (1f - k.outCubic(p)) * 10f * k.u, k.text)
                x += k.text.measureText(wd) + space
                wi++
            }
        }
        k.text.clearShadowLayer()
    }

    // ------------------------------------------------------------------ OUTRO
    private fun outro(k: Kit, c: Canvas, s: Scene, t: Float, d: Float) {
        if (!k.bgImageBlur(c, s.imageIndex, t, 0.7f)) k.bgBlob(c, t, s.variant)
        k.particles(c, t, s.variant, 60, k.theme.accent)
        val handle = s.title.let { if (it.startsWith("@") || it.isBlank()) it else "@$it" }
        val p = k.outBack(k.seg(t, 0f, 0.45f))
        val clickT = 1.3f
        val clicked = t > clickT
        val bw = 360f * k.u; val bh = 96f * k.u
        val by = k.cy + 10f * k.u
        val press = if (abs(t - clickT) < 0.12f) 0.92f else 1f
        c.save(); c.scale(p * press, p * press, k.cx, by)
        k.rect.set(k.cx - bw / 2, by - bh / 2, k.cx + bw / 2, by + bh / 2)
        k.fill.color = if (clicked) 0xFF3A3A3A.toInt() else 0xFFE11D2E.toInt()
        c.drawRoundRect(k.rect, bh / 2, bh / 2, k.fill)
        val label = if (clicked) "SUBSCRIBED ✓" else "SUBSCRIBE"
        val size = k.fitSize(label, k.fonts.headline, bw * 0.8f, 44f * k.u)
        k.setText(k.fonts.headline, size, Color.WHITE, label); k.text.textAlign = Paint.Align.CENTER
        c.drawText(label, k.cx, by + size * 0.36f, k.text)
        c.restore()
        if (t in clickT..clickT + 0.6f) {
            val rp = k.seg(t, clickT, clickT + 0.6f)
            k.stroke.color = k.alpha(Color.WHITE, 1f - rp); k.stroke.strokeWidth = 6f * k.u
            c.drawCircle(k.cx + bw * 0.3f, by, 20f * k.u + rp * 120f * k.u, k.stroke)
        }
        if (handle.isNotBlank()) {
            val hs = k.fitSize(handle, k.fonts.heavy, k.w * 0.8f, 46f * k.u)
            k.setText(k.fonts.heavy, hs, Color.WHITE, handle); k.text.textAlign = Paint.Align.CENTER
            k.text.alpha = (255 * k.seg(t, 0.3f, 0.7f)).toInt(); k.glow(k.theme.accent)
            c.drawText(handle, k.cx, by - bh - 20f * k.u, k.text); k.text.clearShadowLayer()
        }
        k.setText(k.fonts.body, 26f * k.u, k.alpha(Color.WHITE, 0.8f)); k.text.textAlign = Paint.Align.CENTER
        k.text.alpha = (200 * k.seg(t, 0.8f, 1.2f)).toInt()
        c.drawText("Thanks for watching!", k.cx, by + bh + 30f * k.u, k.text)
    }
}
