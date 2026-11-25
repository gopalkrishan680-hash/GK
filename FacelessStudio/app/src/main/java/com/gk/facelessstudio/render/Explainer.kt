package com.gk.facelessstudio.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import com.gk.facelessstudio.engine.Plan
import com.gk.facelessstudio.model.Scene
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Explainer Canvas: separate cartoon elements are drawn one by one on an endless white canvas while the narrator
 * speaks — outline first, then colour wiped in from the top — exactly when the AI Visual Plan says. Elements move next
 * to each other, arrows are drawn between them, key words are handwritten in colour, and the camera keeps gliding and
 * zooming so the newest things are always framed. A "new" action moves on to a fresh part of the canvas.
 */
object Explainer {

    private const val PINK = 0xFFE2307F.toInt()
    private const val ORANGE = 0xFFF08A00.toInt()
    private const val BLUE = 0xFF1F5FC8.toInt()
    private const val INK = 0xFF1B1B1B.toInt()

    /** One drawn element on the canvas; [hw]/[hh] = half of its real drawn width/height. */
    private class Inst(val elem: Int, val image: Int, val name: String, val board: Int, val home: PointF, val hw: Float, val hh: Float, var t0: Float) {
        /** (time, target) moves; the element glides to each target. */
        val moves = mutableListOf<Pair<Float, PointF>>()
        val pulses = mutableListOf<Float>()
        val size get() = max(hw, hh) * 2
        fun end() = moves.lastOrNull()?.second ?: home
        fun box(at: PointF = end(), pad: Float = 0f) = RectF(at.x - hw - pad, at.y - hh - pad, at.x + hw + pad, at.y + hh + pad)
        fun pos(k: Kit, t: Float): PointF {
            var p = PointF(home.x, home.y)
            for ((mt, to) in moves) {
                if (t <= mt) break
                val e = k.inOutCubic(k.clamp01((t - mt) / 1.3f))
                p = PointF(p.x + (to.x - p.x) * e, p.y + (to.y - p.y) * e)
            }
            return p
        }
    }

    private class Arrow(val a: Inst, val b: Inst, val t0: Float, val bend: Float)
    private class Word(val text: String, val at: PointF, val size: Float, val width: Float, val color: Int, val t0: Float, val cps: Float) {
        fun box(pad: Float = 0f) = RectF(at.x - width / 2 - pad, at.y - size * 0.8f - pad, at.x + width / 2 + pad, at.y + size * 0.25f + pad)
    }
    private class Board(val center: PointF, val t0: Float, val tints: MutableList<Pair<Float, Int>> = mutableListOf())
    private class Cam(val t: Float, val rect: RectF, val dur: Float)

    private class Story(val boards: List<Board>, val insts: List<Inst>, val arrows: List<Arrow>, val words: List<Word>, val cams: List<Cam>)

    private val stories = java.util.concurrent.ConcurrentHashMap<String, Story>()

    /** Extra seconds at the end of an Explainer Canvas video, so the last drawing finishes and stays on screen. */
    const val END_HOLD_MS = 2200L

    private fun cueTime(text: String, cue: String, st: Float, en: Float): Float? {
        if (cue.isBlank()) return null
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val key = cue.lowercase().split(Regex("\\s+")).first().trim(',', '.', '"', '!', '?')
        val idx = words.indexOfFirst { w -> val c = w.lowercase().trim(',', '.', '"', '!', '?', '।'); c == key || (key.length >= 4 && c.startsWith(key)) }
        if (idx < 0) return null
        fun syl(x: String) = max(1, Regex("[aeiouyAEIOUY]+").findAll(x).count())
        val total = words.sumOf { syl(it) }.toFloat()
        val before = words.take(idx).sumOf { syl(it) }
        return st + (en - st) * 0.92f * before / total
    }

    private fun story(k: Kit, scenes: List<Scene>): Story = stories.getOrPut("${k.w}x${k.h}/${k.project.plan.hashCode()}/${scenes.size}/${scenes.lastOrNull()?.endMs}/${k.project.images.hashCode()}") {
        val plan = Plan.parse(k.project.plan)
        val w = k.w.toFloat(); val h = k.h.toFloat()
        val tall = h > w * 1.1f
        // element size, text size, rows: elements sit in rows with a band for handwriting above each row
        val S = if (tall) w * 0.44f else h * 0.36f
        val ts = if (tall) 64f * k.u else 58f * k.u
        val band = ts * 1.5f
        val gap = if (tall) w * 0.07f else w * 0.06f
        val perRowW = if (tall) w * 0.96f else w * 0.92f
        val maxRows = if (tall) 3 else 2
        val rowH = S + band

        val boards = mutableListOf<Board>()
        val insts = mutableListOf<Inst>(); val arrows = mutableListOf<Arrow>(); val words = mutableListOf<Word>()
        val cams = mutableListOf<Cam>()
        val rnd = Random(k.project.seed)
        var row = 0; var cursor = 0f
        var lastTint = -100f
        fun left() = boards.last().center.x - perRowW / 2
        fun rowY(r: Int) = boards.last().center.y - h * 0.5f + band + S / 2 + r * rowH + (if (tall) h * 0.06f else 0f)
        fun newBoard(t: Float) {
            val c = if (boards.isEmpty()) PointF(w / 2, h / 2) else {
                val prev = boards.last().center
                if (tall) PointF(prev.x + (if (boards.size % 2 == 0) 0.3f else -0.3f) * w, prev.y + (maxRows * rowH + band) + h * 0.35f)
                else PointF(prev.x + 1.45f * w, prev.y + (if (rnd.nextBoolean()) 0.2f else -0.2f) * h)
            }
            boards += Board(c, t)
            row = 0; cursor = 0f
        }
        fun onBoard() = insts.filter { it.board == boards.lastIndex }
        fun boardWords() = words.filter { it.t0 >= boards.last().t0 }
        fun blocked(r: RectF, except: Inst? = null) = onBoard().any { it !== except && RectF.intersects(r, it.box(pad = gap * 0.3f)) } ||
            boardWords().any { RectF.intersects(r, it.box(ts * 0.15f)) }

        // the real drawn size of an element (its subject's own shape), never bigger than S
        fun extent(img: Int): Pair<Float, Float> {
            val bmp = if (img >= 0) k.images.get(img) else null
            if (bmp == null) return Pair(S * 0.4f, S * 0.4f)
            val sub = k.images.subject(img)
            val ar = (sub.width() * bmp.width) / (sub.height() * bmp.height)
            val fw = if (ar >= 1f) S else S * ar
            return Pair(fw / 2, fw / ar / 2)
        }

        var emptyCam = -1
        /** Frames everything on the current board; returns when the camera is there (drawing waits for it). */
        fun frame(t: Float, dur: Float = 1.1f): Float {
            val b = boards.last()
            val items = onBoard()
            val r = if (items.isEmpty()) RectF(b.center.x - w / 2, b.center.y - h / 2, b.center.x + w / 2, b.center.y + h / 2)
            else RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { r ->
                items.forEach { i -> r.union(i.box()); r.union(i.box(i.home)) }
                boardWords().forEach { wd -> r.union(wd.box()) }
                r.inset(-w * 0.05f, -h * 0.07f)
            }
            if (items.isEmpty()) { cams += Cam(max(cams.lastOrNull()?.t?.plus(0.3f) ?: 0f, t - 0.6f), r, dur); emptyCam = cams.lastIndex; return t }
            if (emptyCam >= 0 && t - cams[emptyCam].t < 4f) { cams[emptyCam] = Cam(cams[emptyCam].t, r, cams[emptyCam].dur); emptyCam = -1; return t }
            emptyCam = -1
            val ct = max(cams.lastOrNull()?.t?.plus(0.25f) ?: 0f, t - 0.8f)
            cams += Cam(ct, r, dur)
            return ct + dur * 0.8f
        }
        newBoard(0f)
        frame(0f)
        var last: Inst? = null
        var first = true
        val fresh = mutableListOf<Inst>()
        for (i in scenes.indices) {
            val s = scenes[i]
            val st = s.startMs / 1000f
            // the last scene also holds the end pause: its words are spoken before that
            val en = (s.startMs + s.durationMs) / 1000f - (if (i == scenes.lastIndex) END_HOLD_MS / 1000f else 0f)
            val acts = plan.lines[i] ?: continue
            acts.forEachIndexed { ai, act ->
                var t = (cueTime(s.text, act.cue, st, en) ?: (st + 0.15f + (en - st) * 0.75f * ai / max(1, acts.size)))
                if (first) { t = min(t, 0.25f); first = false }   // never start on an empty screen
                /** The element on this board, or a new one placed in the next free place of the rows. */
                fun inst(id: String, at: Float): Inst? {
                    val e = plan.index(id); if (e < 0) return null
                    onBoard().firstOrNull { it.elem == e }?.let { return it }
                    val img = if (e < k.project.images.size && k.project.images[e].isNotBlank()) e else -1
                    val (hw, hh) = extent(img)
                    var spot: PointF? = null
                    var tries = 0
                    while (spot == null && tries++ < 40) {
                        if (cursor + 2 * hw > perRowW) { row++; cursor = 0f }
                        if (row >= maxRows) { newBoard(at); frame(at, 1.6f) }
                        val c = PointF(left() + cursor + hw, rowY(row))
                        val r = RectF(c.x - hw, c.y - hh, c.x + hw, c.y + hh)
                        if (!blocked(r)) spot = c
                        cursor += 2 * hw + gap
                    }
                    val home = spot ?: PointF(left() + cursor + hw, rowY(row)).also { cursor += 2 * hw + gap }
                    return Inst(e, img, plan.elements[e].name, boards.lastIndex, home, hw, hh, at).also { insts += it; fresh += it }
                }
                /** New elements are drawn once the camera has arrived to frame them. */
                fun settle() { if (fresh.isNotEmpty()) { val ready = frame(fresh.minOf { it.t0 }); fresh.forEach { it.t0 = max(it.t0, ready) }; fresh.clear() } }
                when (act.kind) {
                    Plan.Kind.NEW -> if (onBoard().isNotEmpty()) { newBoard(t); frame(t, 1.6f) }
                    Plan.Kind.BG -> {
                        // soft paper tints only, and not too often (no dark boards: drawings are made on white)
                        val col = when (act.text.trim().lowercase()) {
                            "blue", "cool", "sky" -> 0xFFEEF4FB.toInt(); "yellow", "warm" -> 0xFFFFF8E6.toInt()
                            "green" -> 0xFFEFF8EC.toInt(); "pink", "red" -> 0xFFFCEEF2.toInt(); "grey", "gray", "dark", "night", "black" -> 0xFFF0F0F0.toInt()
                            else -> Color.WHITE
                        }
                        if (t - lastTint > 10f) { boards.last().tints += t to col; lastTint = t }
                    }
                    Plan.Kind.DRAW -> {
                        val e = plan.index(act.a)
                        val existing = onBoard().firstOrNull { it.elem == e }
                        if (existing != null) { existing.pulses += t; last = existing }
                        else inst(act.a, t)?.let { last = it; settle() }
                    }
                    Plan.Kind.MOVE -> {
                        val a = inst(act.a, t - 1.2f); val b = inst(act.b, t - 1.2f)
                        settle()
                        if (a != null && b != null && a !== b) {
                            val ap = a.end(); val bp = b.end()
                            val side = if (ap.x <= bp.x) -1f else 1f
                            // next to b, on a free side (never on top of anything)
                            val cands = listOf(PointF(bp.x + side * (b.hw + a.hw + gap * 0.5f), bp.y), PointF(bp.x - side * (b.hw + a.hw + gap * 0.5f), bp.y),
                                PointF(bp.x, bp.y - b.hh - a.hh - gap * 0.5f), PointF(bp.x, bp.y + b.hh + a.hh + gap * 0.5f))
                            val to = cands.firstOrNull { q -> !blocked(RectF(q.x - a.hw, q.y - a.hh, q.x + a.hw, q.y + a.hh), except = a) }
                            val mt = max(t, a.t0 + 1.6f)   // it moves once it has been drawn
                            if (to != null) { a.moves += mt to to; frame(mt) } else a.pulses += mt
                            last = a
                        }
                    }
                    Plan.Kind.ARROW -> {
                        val a = inst(act.a, t - 1.2f); val b = inst(act.b, t - 1.2f)
                        settle()
                        if (a != null && b != null && a !== b) {
                            val pa = a.end(); val pb = b.end()
                            val d = kotlin.math.hypot(pb.x - pa.x, pb.y - pa.y) - (a.hw + b.hw)
                            if (d < S * 0.35f) b.pulses += t   // too close for an arrow: the target just bounces
                            else {
                                // bend away from anything in between
                                // try gentle to strong bends on both sides; keep the first whose curve misses everything else
                                val bend = listOf(0.22f, -0.22f, 0.45f, -0.45f, 0.7f, -0.7f, 0.9f, -0.9f).firstOrNull { bd ->
                                    val dx = pb.x - pa.x; val dy = pb.y - pa.y
                                    val mx = (pa.x + pb.x) / 2 - dy * bd * 0.5f; val my = (pa.y + pb.y) / 2 + dx * bd * 0.5f
                                    (1..9).none { q -> val u = q / 10f
                                        val x = (1 - u) * (1 - u) * pa.x + 2 * (1 - u) * u * mx + u * u * pb.x
                                        val y = (1 - u) * (1 - u) * pa.y + 2 * (1 - u) * u * my + u * u * pb.y
                                        onBoard().any { o -> o !== a && o !== b && o.box(pad = gap * 0.45f).contains(x, y) } }
                                } ?: 0.9f
                                val at = max(t, max(a.t0, b.t0) + 1.5f)
                                arrows += Arrow(a, b, at, bend); frame(at)
                            }
                            last = b
                        }
                    }
                    Plan.Kind.TEXT -> {
                        val txt = act.text.trim().take(24)
                        if (txt.isNotEmpty()) {
                            k.setText(k.fonts.marker, ts, INK, txt)
                            val tw = k.text.measureText(txt)
                            val anchor = last?.takeIf { it.board == boards.lastIndex }
                            val cands = if (anchor == null) listOf(PointF(boards.last().center.x, rowY(0) - S / 2 - ts * 0.3f)) else {
                                val p = anchor.end()
                                listOf(PointF(p.x, p.y - anchor.hh - ts * 0.3f), PointF(p.x, p.y + anchor.hh + ts * 0.95f),
                                    PointF(p.x + anchor.hw + gap * 0.4f + tw / 2, p.y), PointF(p.x - anchor.hw - gap * 0.4f - tw / 2, p.y),
                                    PointF(p.x, p.y - anchor.hh - ts * 1.4f))
                            }
                            val at = cands.firstOrNull { q -> !blocked(RectF(q.x - tw / 2, q.y - ts * 0.8f, q.x + tw / 2, q.y + ts * 0.25f)) } ?: cands[0]
                            val colors = intArrayOf(PINK, ORANGE, BLUE)
                            words += Word(txt, at, ts, tw, colors[words.size % 3], t, max(14f, txt.length / 0.5f))
                            frame(t)
                        }
                    }
                }
            }
        }
        Story(boards, insts, arrows, words, cams)
    }

    fun draw(k: Kit, c: Canvas, scenes: List<Scene>, tMs: Long) {
        val sto = story(k, scenes)
        val t = tMs / 1000f
        // ---- camera: glide between the framings, never jumps
        var from = sto.cams.first().rect; var to = from; var t0 = 0f; var dur = 1f
        fun at(x: Float): RectF { val e = k.inOutCubic(k.clamp01((x - t0) / dur)); return lerp(from, to, e) }
        for (cm in sto.cams) {
            if (cm.t > t) break
            from = at(cm.t); to = cm.rect; t0 = cm.t; dur = cm.dur
        }
        val view = at(t)
        val zoom = min(k.w / (view.width() * 1.0f), k.h / view.height()).coerceIn(0.45f, 1.5f) * (1f + 0.012f * sin(t * 0.35f))
        val camX = view.centerX(); val camY = view.centerY()

        // ---- paper: the current board's tint (soft cross-fade)
        val bi = sto.boards.indexOfLast { it.t0 <= t }.coerceAtLeast(0)
        var paper = Doodle.PAPER
        for ((tt, col) in sto.boards[bi].tints) if (tt <= t) paper = k.mix(paper, if (col == Color.WHITE) Doodle.PAPER else col, k.clamp01((t - tt) / 1.6f))
        c.drawColor(paper)
        val dark = Color.red(paper) < 90

        c.save()
        c.translate(k.cx, k.cy); c.scale(zoom, zoom); c.translate(-camX, -camY)
        val vw = k.w / zoom; val vh = k.h / zoom
        val visible = RectF(camX - vw / 2, camY - vh / 2, camX + vw / 2, camY + vh / 2)

        for (i in sto.insts) {
            if (t < i.t0) continue
            val p = i.pos(k, t)
            if (p.x + i.hw < visible.left || p.x - i.size > visible.right || p.y + i.size < visible.top || p.y - i.size > visible.bottom) continue
            element(k, c, i, p, t, dark)
        }
        for (a in sto.arrows) if (t >= a.t0) arrow(k, c, a, t)   // arrows on top, so their heads are never hidden
        for (wd in sto.words) if (t >= wd.t0) word(k, c, wd, t)
        c.restore()
    }

    private fun lerp(a: RectF, b: RectF, e: Float) = RectF(a.left + (b.left - a.left) * e, a.top + (b.top - a.top) * e, a.right + (b.right - a.right) * e, a.bottom + (b.bottom - a.bottom) * e)

    /** Top-to-bottom (slightly diagonal) colour wipe, like a marker filling the drawing. */
    private val wipe: FloatArray by lazy {
        FloatArray(DrawOrder.W * DrawOrder.H) { idx ->
            val x = idx % DrawOrder.W; val y = idx / DrawOrder.W
            (y.toFloat() / DrawOrder.H) * 0.82f + (x.toFloat() / DrawOrder.W) * 0.18f
        }
    }

    private fun element(k: Kit, c: Canvas, i: Inst, p: PointF, t: Float, dark: Boolean) {
        val age = t - i.t0
        // a gentle idle bob so the characters feel alive
        val bob = sin(t * 1.6f + i.elem * 1.7f) * 3f * k.u * k.clamp01((age - 2f) / 1f)
        var sc = 1f
        for (pt in i.pulses) if (t >= pt && t < pt + 0.6f) sc = 1f + 0.1f * sin(Math.PI.toFloat() * (t - pt) / 0.6f)
        c.save(); c.translate(p.x, p.y + bob); c.scale(sc, sc)
        val color = if (i.image >= 0) k.images.get(i.image) else null
        if (color == null) {
            // no picture yet: the element's name in a sketched circle (so the plan can be previewed)
            val r = i.hw * 0.95f
            k.stroke.color = if (dark) Color.WHITE else INK; k.stroke.strokeWidth = 3f * k.u
            val path = Path().apply { addCircle(0f, 0f, r, Path.Direction.CW) }
            val pm = PathMeasure(path, false); val seg = Path(); pm.getSegment(0f, pm.length * k.clamp01(age / 0.9f), seg, true)
            c.drawPath(seg, k.stroke)
            k.setText(k.fonts.note, 34f * k.u, if (dark) Color.WHITE else INK, i.name)
            k.text.textAlign = Paint.Align.CENTER
            k.text.alpha = (255 * k.clamp01((age - 0.5f) / 0.5f)).toInt()
            c.drawText(i.name.take(16), 0f, 12f * k.u, k.text)
            c.restore(); return
        }
        val sub = k.images.subject(i.image)
        val ar = (sub.width() * color.width) / (sub.height() * color.height)
        @Suppress("UNUSED_VARIABLE") val unusedAr = ar
        val r = RectF(-i.hw, -i.hh, i.hw, i.hh)
        val outline = k.clamp01(age / 0.9f)
        val fill = k.clamp01((age - 0.75f) / 0.75f)
        val blend = if (dark) null else Doodle.multiply
        if (fill < 1f) {
            // crisp black ink outline first (falls back to the pencil sketch for very dark pictures)
            val sketch = k.images.ink(i.image) ?: k.images.sketch(i.image) ?: color
            val ord = DrawOrder.of(sketch, "${k.project.images.getOrNull(i.image)}", i.elem * 4 + 1)
            Doodle.layer(k, c, sketch, r, ord.outline, outline, 0.08f, blend, sub)
        }
        if (fill > 0f) Doodle.layer(k, c, color, r, wipe, fill, 0.06f, blend, sub)
        c.restore()
    }

    private fun arrow(k: Kit, c: Canvas, a: Arrow, t: Float) {
        val pa = a.a.pos(k, t); val pb = a.b.pos(k, t)
        val dx = pb.x - pa.x; val dy = pb.y - pa.y; val d = max(1f, kotlin.math.hypot(dx, dy))
        val ux = dx / d; val uy = dy / d
        // start/end just outside each element's own box
        fun reach(i: Inst) = min(i.hw / max(0.01f, abs(ux)), i.hh / max(0.01f, abs(uy))) + 10f * k.u
        val sx = pa.x + ux * reach(a.a); val sy = pa.y + uy * reach(a.a)
        val ex = pb.x - ux * reach(a.b); val ey = pb.y - uy * reach(a.b)
        if (kotlin.math.hypot(ex - sx, ey - sy) < 20f * k.u) return
        val mx = (sx + ex) / 2 - uy * d * 0.5f * a.bend; val my = (sy + ey) / 2 + ux * d * 0.5f * a.bend
        val path = Path().apply { moveTo(sx, sy); quadTo(mx, my, ex, ey) }
        val pm = PathMeasure(path, false)
        val p = k.inOutSine(k.clamp01((t - a.t0) / 0.8f))
        val seg = Path(); pm.getSegment(0f, pm.length * p, seg, true)
        k.stroke.color = BLUE; k.stroke.strokeWidth = 6f * k.u; k.stroke.strokeCap = Paint.Cap.ROUND
        c.drawPath(seg, k.stroke)
        // a second, thinner marker pass next to the first (hand-drawn look)
        k.stroke.strokeWidth = 3f * k.u; k.stroke.color = 0xFF3B7BE0.toInt()
        c.save(); c.translate(-uy * 7f * k.u, ux * 7f * k.u); c.drawPath(seg, k.stroke); c.restore()
        k.stroke.color = BLUE; k.stroke.strokeWidth = 6f * k.u
        if (p >= 0.98f) {
            val pos = FloatArray(2); val tan = FloatArray(2)
            pm.getPosTan(pm.length, pos, tan)
            val ang = atan2(tan[1], tan[0]); val hl = 24f * k.u
            c.drawLine(pos[0], pos[1], pos[0] - hl * cos(ang - 0.5f), pos[1] - hl * sin(ang - 0.5f), k.stroke)
            c.drawLine(pos[0], pos[1], pos[0] - hl * cos(ang + 0.5f), pos[1] - hl * sin(ang + 0.5f), k.stroke)
        }
    }

    private fun word(k: Kit, c: Canvas, wd: Word, t: Float) {
        val shown = ((t - wd.t0) * wd.cps).toInt().coerceIn(0, wd.text.length)
        if (shown <= 0) return
        k.setText(k.fonts.marker, wd.size, wd.color, wd.text)
        k.text.textAlign = Paint.Align.LEFT
        c.drawText(wd.text.substring(0, shown), wd.at.x - wd.width / 2, wd.at.y, k.text)
    }
}
