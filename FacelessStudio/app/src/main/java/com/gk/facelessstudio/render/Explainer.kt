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

    private class Inst(val elem: Int, val image: Int, val name: String, val board: Int, val home: PointF, val size: Float, val t0: Float) {
        /** (time, target) moves; the element glides to each target. */
        val moves = mutableListOf<Pair<Float, PointF>>()
        val pulses = mutableListOf<Float>()
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
    private class Word(val text: String, val at: PointF, val size: Float, val color: Int, val t0: Float, val cps: Float)
    private class Board(val center: PointF, val t0: Float, var tint: Int, val tints: MutableList<Pair<Float, Int>> = mutableListOf())
    private class Cam(val t: Float, val rect: RectF, val dur: Float)

    private class Story(val boards: List<Board>, val insts: List<Inst>, val arrows: List<Arrow>, val words: List<Word>, val cams: List<Cam>)

    private val stories = java.util.concurrent.ConcurrentHashMap<String, Story>()

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
        val size = if (tall) w * 0.5f else min(w, h) * 0.42f
        // where the n-th element of a board goes (relative to the board centre)
        val slots = if (tall) listOf(PointF(0f, -0.2f), PointF(0.2f, 0.1f), PointF(-0.2f, 0.3f), PointF(0.22f, -0.38f), PointF(-0.22f, -0.05f), PointF(0.24f, 0.4f), PointF(-0.24f, 0.42f))
            else listOf(PointF(-0.24f, -0.04f), PointF(0.06f, -0.1f), PointF(0.32f, 0.04f), PointF(-0.06f, 0.3f), PointF(0.3f, 0.32f), PointF(-0.34f, 0.3f))
        val maxPer = slots.size
        val boards = mutableListOf<Board>()
        val insts = mutableListOf<Inst>(); val arrows = mutableListOf<Arrow>(); val words = mutableListOf<Word>()
        val cams = mutableListOf<Cam>()
        val rnd = Random(k.project.seed)
        var tint = Color.WHITE
        fun newBoard(t: Float) {
            val c = if (boards.isEmpty()) PointF(w / 2, h / 2) else {
                val prev = boards.last().center
                if (tall) PointF(prev.x + (if (boards.size % 2 == 0) 0.35f else -0.35f) * w, prev.y + 1.25f * h)
                else PointF(prev.x + 1.3f * w, prev.y + (if (rnd.nextBoolean()) 0.25f else -0.25f) * h)
            }
            boards += Board(c, t, tint)
        }
        fun onBoard() = insts.filter { it.board == boards.lastIndex }
        var emptyCam = -1   // the framing of a still-empty board: replaced by the first thing drawn on it
        fun frame(t: Float, dur: Float = 1.4f) {
            val b = boards.last()
            val items = onBoard()
            val r = if (items.isEmpty()) RectF(b.center.x - w / 2, b.center.y - h / 2, b.center.x + w / 2, b.center.y + h / 2)
            else RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { r ->
                items.forEach { i -> val p = i.moves.lastOrNull()?.second ?: i.home
                    r.union(p.x - i.size * 0.62f, p.y - i.size * 0.62f, p.x + i.size * 0.62f, p.y + i.size * 0.62f) }
                words.filter { it.t0 >= b.t0 }.forEach { wd -> r.union(wd.at.x - wd.size * 2.2f, wd.at.y - wd.size, wd.at.x + wd.size * 2.2f, wd.at.y + wd.size * 0.4f) }
            }
            // the camera starts moving a little before the drawing starts, so the new thing is drawn in frame
            if (items.isEmpty()) { cams += Cam(max(cams.lastOrNull()?.t?.plus(0.3f) ?: 0f, t - 0.6f), r, dur); emptyCam = cams.lastIndex; return }
            if (emptyCam >= 0 && t - cams[emptyCam].t < 4f) { cams[emptyCam] = Cam(cams[emptyCam].t, r, cams[emptyCam].dur); emptyCam = -1; return }
            emptyCam = -1
            val ct = max(cams.lastOrNull()?.t?.plus(0.3f) ?: 0f, t - 0.6f)
            cams += Cam(ct, r, dur)
        }
        newBoard(0f)
        frame(0f)
        var last: Inst? = null
        val sceneCount = scenes.size
        for (i in 0 until sceneCount) {
            val s = scenes[i]
            val st = s.startMs / 1000f; val en = (s.startMs + s.durationMs) / 1000f
            val acts = plan.lines[i] ?: continue
            acts.forEachIndexed { ai, act ->
                val t = (cueTime(s.text, act.cue, st, en) ?: (st + 0.15f + (en - st) * 0.75f * ai / max(1, acts.size)))
                fun inst(id: String, at: Float): Inst? {
                    val e = plan.index(id); if (e < 0) return null
                    onBoard().firstOrNull { it.elem == e }?.let { return it }
                    if (onBoard().size >= maxPer) { newBoard(at); frame(at, 1.8f) }
                    val b = boards.last()
                    // next free slot (not covering anything already drawn or written on this board)
                    val n0 = onBoard().size
                    fun box(q: PointF) = RectF(b.center.x + q.x * w - size * 0.42f, b.center.y + q.y * h - size * 0.42f, b.center.x + q.x * w + size * 0.42f, b.center.y + q.y * h + size * 0.42f)
                    val sl = (0 until slots.size).map { slots[(n0 + it) % slots.size] }.firstOrNull { q ->
                        val bx = box(q)
                        onBoard().none { o -> val op = o.moves.lastOrNull()?.second ?: o.home; RectF.intersects(bx, RectF(op.x - o.size * 0.42f, op.y - o.size * 0.42f, op.x + o.size * 0.42f, op.y + o.size * 0.42f)) } &&
                            words.filter { it.t0 >= b.t0 }.none { wd -> RectF.intersects(bx, RectF(wd.at.x - wd.size * 0.28f * wd.text.length, wd.at.y - wd.size * 0.85f, wd.at.x + wd.size * 0.28f * wd.text.length, wd.at.y + wd.size * 0.2f)) }
                    } ?: slots[n0 % slots.size]
                    val img = if (e < k.project.images.size && k.project.images[e].isNotBlank()) e else -1
                    return Inst(e, img, plan.elements[e].name, boards.lastIndex, PointF(b.center.x + sl.x * w, b.center.y + sl.y * h), size, at).also { insts += it }
                }
                when (act.kind) {
                    Plan.Kind.NEW -> if (onBoard().isNotEmpty()) { newBoard(t); frame(t, 1.8f) }
                    Plan.Kind.BG -> {
                        tint = when (act.text.trim().lowercase()) {
                            "blue", "cool", "sky" -> 0xFFE6F0FB.toInt(); "yellow", "warm" -> 0xFFFFF6DC.toInt()
                            "green" -> 0xFFE8F6E6.toInt(); "pink", "red" -> 0xFFFCE8EE.toInt(); "grey", "gray" -> 0xFFEDEDED.toInt()
                            "dark", "night", "black" -> 0xFF2A2D3A.toInt(); else -> Color.WHITE
                        }
                        boards.last().tints += t to tint
                    }
                    Plan.Kind.DRAW -> {
                        val e = plan.index(act.a)
                        val existing = onBoard().firstOrNull { it.elem == e }
                        if (existing != null) existing.pulses += t
                        else inst(act.a, t)?.let { last = it; frame(t) }
                        if (existing != null) last = existing
                    }
                    Plan.Kind.MOVE -> {
                        val a = inst(act.a, t - 0.9f); val b = inst(act.b, t - 0.9f)
                        if (a != null && b != null) {
                            val ap = a.moves.lastOrNull()?.second ?: a.home; val bp = b.moves.lastOrNull()?.second ?: b.home
                            val dx = ap.x - bp.x; val dy = ap.y - bp.y; val d = max(1f, kotlin.math.hypot(dx, dy))
                            val gap = (a.size + b.size) * 0.47f
                            a.moves += t to PointF(bp.x + dx / d * gap, bp.y + dy / d * gap)
                            last = a; frame(t)
                        }
                    }
                    Plan.Kind.ARROW -> {
                        val a = inst(act.a, t - 0.9f); val b = inst(act.b, t - 0.9f)
                        if (a != null && b != null) { arrows += Arrow(a, b, t, if (rnd.nextBoolean()) 1f else -1f); last = b; frame(t) }
                    }
                    Plan.Kind.TEXT -> {
                        val txt = act.text.trim().take(28)
                        if (txt.isNotEmpty()) {
                            val anchor = last
                            val ts = if (tall) 78f * k.u else 70f * k.u
                            val tw = ts * 0.55f * txt.length
                            val at = if (anchor == null) PointF(boards.last().center.x, boards.last().center.y - h * 0.3f) else {
                                val p = anchor.moves.lastOrNull()?.second ?: anchor.home
                                val sz = anchor.size
                                // first free spot around the thing it belongs to: above, below, right, left
                                val cands = listOf(PointF(p.x, p.y - sz * 0.58f), PointF(p.x, p.y + sz * 0.62f + ts * 0.6f),
                                    PointF(p.x + sz * 0.5f + tw * 0.55f, p.y), PointF(p.x - sz * 0.5f - tw * 0.55f, p.y))
                                fun free(q: PointF): Boolean {
                                    val box = RectF(q.x - tw / 2, q.y - ts * 0.85f, q.x + tw / 2, q.y + ts * 0.2f)
                                    return onBoard().none { o -> val op = o.moves.lastOrNull()?.second ?: o.home
                                        RectF.intersects(box, RectF(op.x - o.size * 0.4f, op.y - o.size * 0.4f, op.x + o.size * 0.4f, op.y + o.size * 0.4f)) } &&
                                        words.none { o -> abs(o.at.x - q.x) < (tw + o.size * 0.55f * o.text.length) / 2 && abs(o.at.y - q.y) < ts }
                                }
                                cands.firstOrNull { free(it) } ?: cands[0]
                            }
                            val colors = intArrayOf(PINK, ORANGE, BLUE)
                            words += Word(txt, at, ts, colors[words.size % 3], t, (txt.length / 0.9f).coerceIn(8f, 18f))
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
        val zoom = min(k.w / (view.width() * 1.0f), k.h / view.height()).coerceIn(0.55f, 1.5f) * (1f + 0.012f * sin(t * 0.35f))
        val camX = view.centerX(); val camY = view.centerY()

        // ---- paper: the current board's tint (soft cross-fade)
        val bi = sto.boards.indexOfLast { it.t0 <= t }.coerceAtLeast(0)
        var paper = Doodle.PAPER
        for ((tt, col) in sto.boards[bi].tints) if (tt <= t) paper = k.mix(paper, if (col == Color.WHITE) Doodle.PAPER else col, k.clamp01((t - tt) / 0.8f))
        c.drawColor(paper)
        val dark = Color.red(paper) < 90

        c.save()
        c.translate(k.cx, k.cy); c.scale(zoom, zoom); c.translate(-camX, -camY)
        val vw = k.w / zoom; val vh = k.h / zoom
        val visible = RectF(camX - vw / 2, camY - vh / 2, camX + vw / 2, camY + vh / 2)

        for (i in sto.insts) {
            if (t < i.t0) continue
            val p = i.pos(k, t)
            if (p.x + i.size < visible.left || p.x - i.size > visible.right || p.y + i.size < visible.top || p.y - i.size > visible.bottom) continue
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
            val r = i.size * 0.38f
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
        val fw = if (ar >= 1f) i.size else i.size * ar; val fh = fw / ar
        val r = RectF(-fw / 2, -fh / 2, fw / 2, fh / 2)
        val outline = k.clamp01(age / 0.9f)
        val fill = k.clamp01((age - 0.8f) / 0.8f)
        val blend = if (dark) null else Doodle.multiply
        if (fill < 1f) {
            val sketch = k.images.sketch(i.image) ?: color
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
        val sx = pa.x + ux * a.a.size * 0.45f; val sy = pa.y + uy * a.a.size * 0.45f
        val ex = pb.x - ux * a.b.size * 0.48f; val ey = pb.y - uy * a.b.size * 0.48f
        if (kotlin.math.hypot(ex - sx, ey - sy) < 20f * k.u) return
        val mx = (sx + ex) / 2 - uy * d * 0.18f * a.bend; val my = (sy + ey) / 2 + ux * d * 0.18f * a.bend
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
            val ang = atan2(tan[1], tan[0]); val hl = 18f * k.u
            c.drawLine(pos[0], pos[1], pos[0] - hl * cos(ang - 0.5f), pos[1] - hl * sin(ang - 0.5f), k.stroke)
            c.drawLine(pos[0], pos[1], pos[0] - hl * cos(ang + 0.5f), pos[1] - hl * sin(ang + 0.5f), k.stroke)
        }
    }

    private fun word(k: Kit, c: Canvas, wd: Word, t: Float) {
        val shown = ((t - wd.t0) * wd.cps).toInt().coerceIn(0, wd.text.length)
        if (shown <= 0) return
        k.setText(k.fonts.marker, wd.size, wd.color, wd.text)
        k.text.textAlign = Paint.Align.LEFT
        val full = k.text.measureText(wd.text)
        c.drawText(wd.text.substring(0, shown), wd.at.x - full / 2, wd.at.y, k.text)
    }
}
