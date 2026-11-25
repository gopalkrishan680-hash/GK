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
 * speaks — ink outline first, then colour wiped in from the top — exactly when the AI Visual Plan says.
 *
 * Every part of the canvas ("board") is laid out as a balanced composition that fills the screen: each time something
 * is added or moves, the board is re-arranged into one of several patterns (row, triangle, 2×2, zig-zag…) and the
 * elements glide calmly to their new places. Main characters are big, props small. A character that goes to another
 * joins it side by side (travelling along a gentle arc). Arrows bend around things, handwritten words stick to their
 * element. The camera glides slowly; when the story moves on, the old board fades while the camera travels.
 */
object Explainer {

    private const val PINK = 0xFFE2307F.toInt()
    private const val ORANGE = 0xFFF08A00.toInt()
    private const val BLUE = 0xFF1F5FC8.toInt()
    private const val INK = 0xFF1B1B1B.toInt()

    /** Calm timings (seconds). */
    private const val MOVE = 1.9f        // gliding to a new place
    private const val REFRAME = 1.8f     // camera re-framing a board
    private const val PAN = 2.6f         // camera travelling to a new board
    private const val FADE_OLD = 1.6f    // old board fading while the camera travels
    private const val OUTLINE = 1.0f
    private const val FILL = 0.9f

    /** Extra seconds at the end of an Explainer Canvas video, so the last drawing finishes and stays on screen. */
    const val END_HOLD_MS = 2500L

    private class Inst(val elem: Int, val image: Int, val name: String, val board: Int, var home: PointF, val hw: Float, val hh: Float, var t0: Float) {
        /** (time, target, arc) moves; the element glides to each target. */
        val moves = mutableListOf<Triple<Float, PointF, Float>>()
        val pulses = mutableListOf<Float>()
        var gone = Float.MAX_VALUE
        fun end() = moves.lastOrNull()?.second ?: home
        fun box(at: PointF = end(), pad: Float = 0f) = RectF(at.x - hw - pad, at.y - hh - pad, at.x + hw + pad, at.y + hh + pad)
        fun pos(k: Kit, t: Float): PointF {
            var p = PointF(home.x, home.y)
            for ((mt, to, arc) in moves) {
                if (t <= mt) break
                val e = k.inOutSine(k.clamp01((t - mt) / MOVE))
                val lift = sin(Math.PI.toFloat() * e) * arc
                p = PointF(p.x + (to.x - p.x) * e, p.y + (to.y - p.y) * e - lift)
            }
            return p
        }
    }

    private class Arrow(val a: Inst, val b: Inst, val t0: Float, val bend: Float)
    private class Word(val text: String, val anchor: Inst?, val side: Int, val fixed: PointF, val size: Float, val width: Float, val color: Int, val t0: Float, val cps: Float, val board: Int) {
        /** Where the word sits: stuck to its element (above / below / right / left), following it when it moves. */
        fun at(k: Kit, t: Float, gap: Float): PointF {
            val a = anchor ?: return fixed
            val p = if (t == Float.MAX_VALUE) a.end() else a.pos(k, t)
            return when (side) {
                0 -> PointF(p.x, p.y - a.hh - size * 0.3f)
                1 -> PointF(p.x, p.y + a.hh + size * 0.95f)
                2 -> PointF(p.x + a.hw + gap * 0.4f + width / 2, p.y)
                else -> PointF(p.x - a.hw - gap * 0.4f - width / 2, p.y)
            }
        }
        fun box(at: PointF, pad: Float = 0f) = RectF(at.x - width / 2 - pad, at.y - size * 0.8f - pad, at.x + width / 2 + pad, at.y + size * 0.25f + pad)
    }
    private class Board(val center: PointF, val t0: Float, val variant: Int) {
        val tints = mutableListOf<Pair<Float, Int>>()
        /** Groups laid out as one: an element and the ones that joined it, left to right. */
        val units = mutableListOf<MutableList<Inst>>()
    }
    private class Cam(val t: Float, var rect: RectF, var dur: Float, val pan: Boolean)

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

    private val generic = setOf("young", "old", "little", "small", "big", "the", "a", "an", "happy", "sad", "angry", "baby", "child", "adult", "kid", "brave", "great")
    /** "Karna", "young Karna", "Karna with mace" -> "karna": two versions of the same person never stand side by side. */
    private fun person(name: String) = name.lowercase().split(Regex("[^\\p{L}]+")).firstOrNull { it.length >= 3 && it !in generic } ?: ""

    private fun story(k: Kit, scenes: List<Scene>): Story = stories.getOrPut("${k.w}x${k.h}/${k.project.plan.hashCode()}/${scenes.size}/${scenes.lastOrNull()?.endMs}/${k.project.images.hashCode()}/${k.project.seed}") {
        val plan = Plan.parse(k.project.plan)
        val w = k.w.toFloat(); val h = k.h.toFloat()
        val tall = h > w * 1.1f
        val S = if (tall) w * 0.5f else h * 0.5f            // a "big" element's longest side
        val ts = if (tall) 62f * k.u else 56f * k.u           // base handwriting size
        val gap = if (tall) w * 0.07f else w * 0.05f
        val band = ts * 1.4f                                  // room for handwriting above each element
        val maxUnits = 6

        val boards = mutableListOf<Board>()
        val insts = mutableListOf<Inst>(); val arrows = mutableListOf<Arrow>(); val words = mutableListOf<Word>()
        val cams = mutableListOf<Cam>()
        val fresh = mutableListOf<Inst>()
        val rnd = Random(k.project.seed)
        var lastTint = -100f

        fun board() = boards.last()
        fun onBoard() = insts.filter { it.board == boards.lastIndex && it.gone == Float.MAX_VALUE }
        fun boardWords() = words.filter { it.board == boards.lastIndex }

        // ---- the composition of a board: rows of units, centred, shaped like the screen
        fun pattern(n: Int, v: Int): List<Int> = if (tall) when (n) {
            1 -> listOf(1); 2 -> listOf(1, 1); 3 -> if (v % 2 == 0) listOf(1, 2) else listOf(2, 1)
            4 -> listOf(2, 2); 5 -> if (v % 2 == 0) listOf(2, 1, 2) else listOf(1, 2, 2); else -> listOf(2, 2, 2)
        } else when (n) {
            1 -> listOf(1); 2 -> listOf(2); 3 -> when (v % 3) { 0 -> listOf(3); 1 -> listOf(2, 1); else -> listOf(1, 2) }
            4 -> if (v % 2 == 0) listOf(2, 2) else listOf(3, 1); 5 -> if (v % 2 == 0) listOf(3, 2) else listOf(2, 3); else -> listOf(3, 3)
        }
        fun unitW(u: List<Inst>) = u.sumOf { (it.hw * 2).toDouble() }.toFloat() + gap * 0.35f * (u.size - 1)
        fun unitH(u: List<Inst>) = u.maxOf { it.hh * 2 } + band
        /** Re-arranges the board; everything already drawn glides calmly to its new place. */
        fun relayout(t: Float) {
            val b = board()
            val units = b.units.filter { it.isNotEmpty() }
            if (units.isEmpty()) return
            val rows = mutableListOf<List<List<Inst>>>()
            var i = 0
            for (cnt in pattern(units.size, b.variant)) { rows += units.subList(i, min(units.size, i + cnt)); i += cnt }
            val rowH = rows.map { r -> r.maxOf { unitH(it) } }
            val totalH = rowH.sum() + gap * 0.6f * (rows.size - 1)
            var y = b.center.y - totalH / 2
            val jr = Random(b.variant * 31 + units.size)
            rows.forEachIndexed { ri, r ->
                val rw = r.sumOf { unitW(it).toDouble() }.toFloat() + gap * 1.4f * (r.size - 1)
                var x = b.center.x - rw / 2
                r.forEachIndexed { ui, u ->
                    // a 2-unit row on its own is a little staggered (diagonal feel)
                    val stagger = if (!tall && r.size == 2 && rows.size == 1) (if (ui == 0) -1f else 1f) * h * 0.05f * (if (b.variant % 2 == 0) 1 else -1) else 0f
                    val cy = y + band + (rowH[ri] - band) / 2 + stagger + (jr.nextFloat() - 0.5f) * h * 0.03f
                    var mx = x
                    u.forEach { m ->
                        val target = PointF(mx + m.hw, cy)
                        val cur = m.end()
                        if (abs(cur.x - target.x) + abs(cur.y - target.y) > 2f) {
                            if (m.t0 >= t) m.home = target                       // not drawn yet: just put it there
                            else m.moves += Triple(max(t, (m.moves.lastOrNull()?.first ?: 0f) + 0.2f), target, 0f)
                        }
                        mx += m.hw * 2 + gap * 0.35f
                    }
                    x += unitW(u) + gap * 1.4f
                }
                y += rowH[ri] + gap * 0.6f
            }
        }

        fun newBoard(t: Float) {
            val c = if (boards.isEmpty()) PointF(w / 2, h / 2) else {
                val prev = board().center
                if (tall) PointF(prev.x + (if (boards.size % 2 == 0) 0.5f else -0.5f) * w, prev.y + 1.7f * h)
                else PointF(prev.x + 1.7f * w, prev.y + (if (rnd.nextBoolean()) 0.3f else -0.3f) * h)
            }
            boards += Board(c, t, rnd.nextInt(1000))
        }

        /** Camera keyframe framing everything on the board. Returns the time the camera is (nearly) there. */
        fun frame(t: Float, pan: Boolean = false): Float {
            val b = board()
            val items = onBoard()
            val r = if (items.isEmpty()) RectF(b.center.x - w / 2, b.center.y - h / 2, b.center.x + w / 2, b.center.y + h / 2)
            else RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { r ->
                items.forEach { r.union(it.box()) }
                boardWords().forEach { wd -> r.union(wd.box(wd.at(k, Float.MAX_VALUE, gap))) }
                r.inset(-w * 0.06f, -h * 0.08f)
                // never zoom in more than ~1.35x
                val minW = w / 1.35f; val minH = h / 1.35f
                if (r.width() < minW) r.inset(-(minW - r.width()) / 2, 0f)
                if (r.height() < minH) r.inset(0f, -(minH - r.height()) / 2)
            }
            val last = cams.lastOrNull()
            val start = max(last?.t?.plus(0.4f) ?: 0f, t - (if (pan) 0.8f else 1.0f))
            // keyframes close together are merged, so the camera never jitters
            if (last != null && !pan && !last.pan && start < last.t + 1.2f) { last.rect = r; last.dur = max(last.dur, REFRAME); return last.t + last.dur * 0.7f }
            if (last != null && !pan && last.pan && start < last.t + 1.0f) { last.rect = r; return last.t + last.dur * 0.75f }
            val dur = if (pan) PAN else REFRAME
            cams += Cam(start, r, dur, pan)
            return start + dur * 0.7f
        }

        newBoard(0f)
        cams += Cam(0f, RectF(0f, 0f, w, h), 1f, true)
        var last: Inst? = null
        var first = true
        for (i in scenes.indices) {
            val s = scenes[i]
            val st = s.startMs / 1000f
            val en = (s.startMs + s.durationMs) / 1000f - (if (i == scenes.lastIndex) END_HOLD_MS / 1000f else 0f)
            val acts = plan.lines[i] ?: continue
            acts.forEachIndexed { ai, act ->
                var t = (cueTime(s.text, act.cue, st, en) ?: (st + 0.15f + (en - st) * 0.75f * ai / max(1, acts.size)))
                if (first) { t = min(t, 0.25f); first = false }

                /** The element on this board, or a new one (placed by re-arranging the board). */
                fun inst(id: String, at: Float): Inst? {
                    val e = plan.index(id); if (e < 0) return null
                    onBoard().firstOrNull { it.elem == e }?.let { return it }
                    val el = plan.elements[e]
                    // the same person in another version replaces the one on the board instead of standing next to it
                    val same = person(el.name).takeIf { it.isNotEmpty() }?.let { pn -> onBoard().firstOrNull { person(it.name) == pn } }
                    if (same == null && board().units.count { it.isNotEmpty() } >= maxUnits) { newBoard(at); frame(at, pan = true) }
                    val img = if (e < k.project.images.size && k.project.images[e].isNotBlank()) e else -1
                    val side = S * when (el.scale) { "big" -> 1f; "small" -> 0.52f; else -> 0.78f }
                    val bmp = if (img >= 0) k.images.get(img) else null
                    val (hw, hh) = if (bmp == null) Pair(side * 0.42f, side * 0.42f) else {
                        val sub = k.images.subject(img)
                        val ar = (sub.width() * bmp.width) / (sub.height() * bmp.height)
                        val fw = if (ar >= 1f) side else side * ar
                        Pair(fw / 2, fw / ar / 2)
                    }
                    val n = Inst(e, img, el.name, boards.lastIndex, same?.end() ?: board().center, hw, hh, at)
                    insts += n
                    if (same != null) {
                        same.gone = at + 0.2f
                        board().units.forEach { u -> val j = u.indexOf(same); if (j >= 0) u[j] = n }
                    } else board().units += mutableListOf(n)
                    fresh += n
                    return n
                }
                fun settle(t0: Float) {
                    if (fresh.isEmpty()) return
                    relayout(t0)
                    val ready = frame(t0)
                    fresh.forEach { it.t0 = max(it.t0, ready) }
                    fresh.clear()
                }
                when (act.kind) {
                    Plan.Kind.NEW -> if (onBoard().isNotEmpty()) { newBoard(t); frame(t, pan = true) }
                    Plan.Kind.BG -> {
                        val col = when (act.text.trim().lowercase()) {
                            "blue", "cool", "sky" -> 0xFFEEF4FB.toInt(); "yellow", "warm" -> 0xFFFFF8E6.toInt()
                            "green" -> 0xFFEFF8EC.toInt(); "pink", "red" -> 0xFFFCEEF2.toInt(); "grey", "gray", "dark", "night", "black" -> 0xFFF0F0F0.toInt()
                            else -> Color.WHITE
                        }
                        if (t - lastTint > 10f) { board().tints += t to col; lastTint = t }
                    }
                    Plan.Kind.DRAW -> {
                        val e = plan.index(act.a)
                        val existing = onBoard().firstOrNull { it.elem == e }
                        if (existing != null) { existing.pulses += t; last = existing }
                        else inst(act.a, t)?.let { last = it; settle(t) }
                    }
                    Plan.Kind.MOVE -> {
                        val a = inst(act.a, t - 1.4f); val b = inst(act.b, t - 1.4f)
                        settle(t - 1.4f)
                        if (a != null && b != null && a !== b) {
                            val mt = max(t, a.t0 + OUTLINE + FILL + 0.2f)
                            val units = board().units
                            val ua = units.firstOrNull { a in it }; val ub = units.firstOrNull { b in it }
                            if (ua != null && ub != null && ua !== ub) {
                                val fromLeft = a.end().x < b.end().x
                                ua.remove(a); units.removeAll { it.isEmpty() }
                                if (fromLeft) ub.add(ub.indexOf(b), a) else ub.add(ub.indexOf(b) + 1, a)
                                val before = a.moves.size
                                relayout(mt)
                                // the travelling element goes along a gentle arc over the others
                                if (a.moves.size > before) { val m = a.moves.last(); a.moves[a.moves.lastIndex] = Triple(m.first, m.second, S * 0.35f) }
                                frame(mt)
                            } else a.pulses += mt
                            last = a
                        }
                    }
                    Plan.Kind.ARROW -> {
                        val a = inst(act.a, t - 1.4f); val b = inst(act.b, t - 1.4f)
                        settle(t - 1.4f)
                        if (a != null && b != null && a !== b) {
                            val pa = a.end(); val pb = b.end()
                            val d = kotlin.math.hypot(pb.x - pa.x, pb.y - pa.y) - (a.hw + b.hw)
                            val at = max(t, max(a.t0, b.t0) + OUTLINE + FILL)
                            if (d < S * 0.3f) b.pulses += at
                            else {
                                // bend away from anything in between
                                val bend = listOf(0.22f, -0.22f, 0.45f, -0.45f, 0.7f, -0.7f, 0.9f, -0.9f).firstOrNull { bd ->
                                    val dx = pb.x - pa.x; val dy = pb.y - pa.y
                                    val mx = (pa.x + pb.x) / 2 - dy * bd * 0.5f; val my = (pa.y + pb.y) / 2 + dx * bd * 0.5f
                                    (1..9).none { q -> val u = q / 10f
                                        val x = (1 - u) * (1 - u) * pa.x + 2 * (1 - u) * u * mx + u * u * pb.x
                                        val y = (1 - u) * (1 - u) * pa.y + 2 * (1 - u) * u * my + u * u * pb.y
                                        onBoard().any { o -> o !== a && o !== b && o.box(pad = gap * 0.45f).contains(x, y) } }
                                } ?: 0.9f
                                arrows += Arrow(a, b, at, bend)
                            }
                            last = b
                        }
                    }
                    Plan.Kind.TEXT -> {
                        val txt = act.text.trim().take(24)
                        if (txt.isNotEmpty()) {
                            val anchor = last?.takeIf { it.board == boards.lastIndex && it.gone == Float.MAX_VALUE }
                            // the word is sized to its element, so it never looks lost
                            val size = if (anchor == null) ts * 1.3f else (anchor.hh * 2 * 0.24f).coerceIn(ts, ts * 1.6f)
                            k.setText(k.fonts.marker, size, INK, txt)
                            val tw = k.text.measureText(txt)
                            val colors = intArrayOf(PINK, ORANGE, BLUE)
                            val wt = max(t, (anchor?.t0 ?: t) + 0.4f)
                            val cps = max(14f, txt.length / 0.5f)
                            val wd = if (anchor == null) {
                                val top = onBoard().minOfOrNull { it.box().top } ?: (board().center.y - h * 0.3f)
                                Word(txt, null, 0, PointF(board().center.x, top - size * 0.4f), size, tw, colors[words.size % 3], wt, cps, boards.lastIndex)
                            } else {
                                val side = (0..3).firstOrNull { sd ->
                                    val cand = Word(txt, anchor, sd, PointF(), size, tw, 0, 0f, 0f, 0)
                                    val bx = cand.box(cand.at(k, Float.MAX_VALUE, gap))
                                    onBoard().none { o -> o !== anchor && RectF.intersects(bx, o.box()) } &&
                                        boardWords().none { o -> RectF.intersects(bx, o.box(o.at(k, Float.MAX_VALUE, gap), ts * 0.15f)) }
                                } ?: 0
                                Word(txt, anchor, side, PointF(), size, tw, colors[words.size % 3], wt, cps, boards.lastIndex)
                            }
                            words += wd
                            frame(wt)
                        }
                    }
                }
            }
        }
        Story(boards, insts, arrows, words, cams)
    }

    fun draw(k: Kit, c: Canvas, scenes: List<Scene>, tMs: Long) {
        val sto = synchronized(stories) { story(k, scenes) }
        val t = tMs / 1000f
        // ---- camera: glide between the framings, never jumps
        var from = sto.cams.first().rect; var to = from; var t0 = 0f; var dur = 1f
        fun at(x: Float): RectF { val e = k.inOutSine(k.clamp01((x - t0) / dur)); return lerp(from, to, e) }
        for (cm in sto.cams) {
            if (cm.t > t) break
            from = at(cm.t); to = cm.rect; t0 = cm.t; dur = cm.dur
        }
        val view = at(t)
        val zoom = min(k.w / view.width(), k.h / view.height()).coerceIn(0.35f, 1.4f) * (1f + 0.01f * sin(t * 0.3f))
        val camX = view.centerX(); val camY = view.centerY()

        // ---- paper: the current board's tint (soft cross-fade)
        val bi = sto.boards.indexOfLast { it.t0 <= t }.coerceAtLeast(0)
        var paper = Doodle.PAPER
        for ((tt, col) in sto.boards[bi].tints) if (tt <= t) paper = k.mix(paper, if (col == Color.WHITE) Doodle.PAPER else col, k.clamp01((t - tt) / 2f))
        c.drawColor(paper)

        c.save()
        c.translate(k.cx, k.cy); c.scale(zoom, zoom); c.translate(-camX, -camY)
        val vw = k.w / zoom; val vh = k.h / zoom
        val visible = RectF(camX - vw / 2, camY - vh / 2, camX + vw / 2, camY + vh / 2)
        val gap = if (k.h > k.w * 1.1f) k.w * 0.07f else k.w * 0.05f

        // the old board fades away while the camera travels to the new one (no half-cut things at the edges)
        for (b in max(0, bi - 1)..bi) {
            val alpha = if (b >= bi) 1f else 1f - k.clamp01((t - sto.boards[bi].t0) / FADE_OLD)
            if (alpha <= 0.01f) continue
            val layered = alpha < 0.99f
            if (layered) c.saveLayerAlpha(visible, (255 * alpha).toInt())
            // still elements first, travelling ones on top
            val items = sto.insts.filter { it.board == b && t >= it.t0 && t < it.gone + 0.7f }
            val (moving, still) = items.partition { i -> i.moves.any { t > it.first && t < it.first + MOVE } }
            for (i in still + moving) {
                val p = i.pos(k, t)
                if (p.x + i.hw < visible.left || p.x - i.hw > visible.right || p.y + i.hh < visible.top || p.y - i.hh > visible.bottom) continue
                val fade = if (t > i.gone) 1f - k.clamp01((t - i.gone) / 0.6f) else 1f
                if (fade < 0.99f) c.saveLayerAlpha(RectF(p.x - i.hw * 1.2f, p.y - i.hh * 1.2f, p.x + i.hw * 1.2f, p.y + i.hh * 1.2f), (255 * fade).toInt())
                element(k, c, i, p, t)
                if (fade < 0.99f) c.restore()
            }
            for (a in sto.arrows) if (a.a.board == b && t >= a.t0 && t < min(a.a.gone, a.b.gone)) arrow(k, c, a, t)
            for (wd in sto.words) if (wd.board == b && t >= wd.t0 && t < (wd.anchor?.gone ?: Float.MAX_VALUE)) word(k, c, wd, t, gap)
            if (layered) c.restore()
        }
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

    private fun element(k: Kit, c: Canvas, i: Inst, p: PointF, t: Float) {
        val age = t - i.t0
        val bob = sin(t * 1.4f + i.elem * 1.7f) * 3f * k.u * k.clamp01((age - 2.5f) / 1f)
        var sc = 1f
        for (pt in i.pulses) if (t >= pt && t < pt + 0.7f) sc = 1f + 0.08f * sin(Math.PI.toFloat() * (t - pt) / 0.7f)
        c.save(); c.translate(p.x, p.y + bob); c.scale(sc, sc)
        val color = if (i.image >= 0) k.images.get(i.image) else null
        if (color == null) {
            // no picture yet: the element's name in a sketched circle (so the plan can be previewed)
            val r = i.hw * 0.95f
            k.stroke.color = INK; k.stroke.strokeWidth = 3f * k.u
            val path = Path().apply { addCircle(0f, 0f, r, Path.Direction.CW) }
            val pm = PathMeasure(path, false); val seg = Path(); pm.getSegment(0f, pm.length * k.clamp01(age / OUTLINE), seg, true)
            c.drawPath(seg, k.stroke)
            k.setText(k.fonts.note, 34f * k.u, INK, i.name)
            k.text.textAlign = Paint.Align.CENTER
            k.text.alpha = (255 * k.clamp01((age - 0.5f) / 0.5f)).toInt()
            c.drawText(i.name.take(16), 0f, 12f * k.u, k.text)
            c.restore(); return
        }
        val sub = k.images.subject(i.image)
        val r = RectF(-i.hw, -i.hh, i.hw, i.hh)
        val outline = k.clamp01(age / OUTLINE)
        val fill = k.clamp01((age - OUTLINE * 0.85f) / FILL)
        if (fill < 1f) {
            // crisp black ink outline first (falls back to the pencil sketch for very dark pictures)
            val pencil = k.images.sketch(i.image) ?: color
            val lines = k.images.ink(i.image) ?: pencil
            val ord = DrawOrder.of(pencil, "${k.project.images.getOrNull(i.image)}", i.elem * 4 + 1)
            Doodle.layer(k, c, lines, r, ord.outline, outline, 0.08f, Doodle.multiply, sub)
        }
        if (fill > 0f) Doodle.layer(k, c, color, r, wipe, fill, 0.06f, Doodle.multiply, sub)
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
        val p = k.inOutSine(k.clamp01((t - a.t0) / 1.2f))
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

    private fun word(k: Kit, c: Canvas, wd: Word, t: Float, gap: Float) {
        val shown = ((t - wd.t0) * wd.cps).toInt().coerceIn(0, wd.text.length)
        if (shown <= 0) return
        val at = wd.at(k, t, gap)
        k.setText(k.fonts.marker, wd.size, wd.color, wd.text)
        k.text.textAlign = Paint.Align.LEFT
        c.drawText(wd.text.substring(0, shown), at.x - wd.width / 2, at.y, k.text)
    }
}
