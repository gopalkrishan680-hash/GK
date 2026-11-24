package com.gk.facelessstudio.engine

import com.gk.facelessstudio.model.Backdrop
import com.gk.facelessstudio.model.CamMove
import com.gk.facelessstudio.model.Deco
import com.gk.facelessstudio.model.Pacing
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.model.TextFx
import com.gk.facelessstudio.model.Transition
import kotlin.random.Random

/**
 * Makes every scene a different combination of layout × text entrance × backdrop × camera × decoration × transition.
 *  - a layout is used at most twice per video (the cap only rises when every possible layout has been used twice,
 *    which spreads them evenly in very long videos),
 *  - the same layout never comes back within 3 scenes,
 *  - each dimension always prefers its least-used option,
 *  - no full combination appears twice.
 */
class Variety(seed: Long, private val pacing: Pacing) {
    private val rnd = Random(seed)
    private val layoutUse = HashMap<SceneType, Int>()
    private val fxUse = HashMap<TextFx, Int>()
    private val bgUse = HashMap<Backdrop, Int>()
    private val camUse = HashMap<CamMove, Int>()
    private val decoUse = HashMap<Deco, Int>()
    private val trUse = HashMap<Transition, Int>()
    private val combos = HashSet<String>()
    private val recent = ArrayDeque<SceneType>()
    var cap = 2; private set

    private fun <T> leastUsed(options: List<T>, use: Map<T, Int>): T {
        val min = options.minOf { use[it] ?: 0 }
        return options.filter { (use[it] ?: 0) == min }.random(rnd)
    }

    /**
     * [preferred] fit what the line says (numbers, questions, lists…); [generic] can show any line.
     * Picks the least-used allowed layout, preferring content-specific ones.
     */
    fun layout(preferred: List<SceneType>, generic: List<SceneType>, soft: List<SceneType> = emptyList(), first: List<SceneType> = emptyList()): SceneType {
        val all = (preferred + soft + generic).distinct()
        while (true) {
            fun ok(t: SceneType) = (layoutUse[t] ?: 0) < cap && t !in recent
            val pref = preferred.filter(::ok)
            val sft = soft.filter(::ok)
            val gen = generic.filter(::ok)
            val top = first.filter(::ok)
            val roomy = all.filter { (layoutUse[it] ?: 0) < cap }
            val pick = when {
                pref.isNotEmpty() -> leastUsed(pref, layoutUse)
                sft.isNotEmpty() -> leastUsed(sft, layoutUse)
                top.isNotEmpty() -> leastUsed(top, layoutUse)   // e.g. picture layouts before text layouts
                gen.isNotEmpty() -> leastUsed(gen, layoutUse)
                // spacing matters more than the cap: if other layouts exist, give everyone one more use instead
                all.any { it !in recent } -> null
                // tiny candidate pool (all recent): allow a recent one
                roomy.isNotEmpty() -> leastUsed(roomy, layoutUse)
                else -> null
            }
            if (pick != null) { use(pick); return pick }
            cap++   // every candidate already used `cap` times: allow one more round for all
        }
    }

    /** Registers a layout chosen elsewhere (fixed chapter/outro scenes, manual edits). */
    fun use(t: SceneType) {
        layoutUse[t] = (layoutUse[t] ?: 0) + 1
        recent.addLast(t); if (recent.size > 3) recent.removeFirst()
    }

    data class Look(val fx: TextFx, val bg: Backdrop, val cam: CamMove, val deco: Deco)

    /** Least-used entrance/backdrop/camera/decoration whose full combination with [type] is new. */
    fun look(type: SceneType, allowBg: List<Backdrop> = Backdrop.entries): Look {
        val cams = when (pacing) {
            Pacing.CALM -> CamMove.entries.filter { it != CamMove.HANDHELD }
            else -> CamMove.entries
        }
        val decos = when (pacing) {
            Pacing.CALM -> Deco.entries.filter { it != Deco.CONFETTI && it != Deco.SCANLINES }
            else -> Deco.entries
        }
        var chosen: Look? = null
        for (attempt in 0 until 80) {
            val l = if (attempt == 0) Look(leastUsed(TextFx.entries, fxUse), leastUsed(allowBg, bgUse), leastUsed(cams, camUse), leastUsed(decos, decoUse))
            else Look(TextFx.entries.random(rnd), allowBg.random(rnd), cams.random(rnd), decos.random(rnd))
            val key = "$type/${l.fx}/${l.bg}/${l.cam}/${l.deco}"
            if (key !in combos) { chosen = l; combos += key; break }
        }
        val l = chosen ?: Look(TextFx.entries.random(rnd), allowBg.random(rnd), cams.random(rnd), decos.random(rnd))
        fxUse[l.fx] = (fxUse[l.fx] ?: 0) + 1; bgUse[l.bg] = (bgUse[l.bg] ?: 0) + 1
        camUse[l.cam] = (camUse[l.cam] ?: 0) + 1; decoUse[l.deco] = (decoUse[l.deco] ?: 0) + 1
        return l
    }

    fun transition(last: Transition, chapterStart: Boolean): Transition {
        val pool = when {
            chapterStart -> listOf(Transition.FLASH, Transition.GLITCH, Transition.ZOOM_PUNCH, Transition.SHUTTER, Transition.DOORS, Transition.DIP)
            pacing == Pacing.CALM -> listOf(Transition.FADE, Transition.SLIDE_LEFT, Transition.SLIDE_UP, Transition.CIRCLE, Transition.DIP, Transition.PAGE, Transition.DOORS, Transition.BLINDS)
            pacing == Pacing.ENERGETIC -> listOf(Transition.ZOOM_PUNCH, Transition.WHIP, Transition.CUT, Transition.GLITCH, Transition.FLASH, Transition.SPIN,
                Transition.SHUTTER, Transition.SLIDE_UP, Transition.PIXEL, Transition.DOORS, Transition.BLINDS)
            else -> Transition.entries.filter { it != Transition.CUT || rnd.nextFloat() < 0.5f }
        }.filter { it != last }
        val t = leastUsed(pool, trUse)
        trUse[t] = (trUse[t] ?: 0) + 1
        return t
    }

    /** Whiteboard: the camera pans along the board, or the page turns. */
    fun doodleTransition(last: Transition): Transition {
        val pool = listOf(Transition.SLIDE_LEFT, Transition.SLIDE_UP, Transition.FADE, Transition.PAGE, Transition.CUT).filter { it != last }
        val t = leastUsed(pool, trUse)
        trUse[t] = (trUse[t] ?: 0) + 1
        return t
    }

    fun uses(t: SceneType) = layoutUse[t] ?: 0
}
