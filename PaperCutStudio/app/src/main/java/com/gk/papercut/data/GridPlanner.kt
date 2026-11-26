package com.gk.papercut.data

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Generate several scenes in ONE image: the app cuts the grid back into panels. */
@Serializable
enum class GridLayout(val label: String, val cols: Int, val rows: Int) {
    SINGLE("One image per scene", 1, 1),
    G2X2("2×2 grid (4 scenes)", 2, 2),
    G3X2("3×2 grid (6 scenes)", 3, 2),
    G3X3("3×3 grid (9 scenes)", 3, 3);

    val cells get() = cols * rows
}

/** One grid image = a batch of consecutive scenes. */
data class GridBatch(val index: Int, val first: Int, val scenes: List<Scene>, val cols: Int, val rows: Int) {
    val cells get() = cols * rows
    val last get() = first + scenes.size - 1
}

object GridPlanner {

    /** Layouts the app may fall back to for a short last batch (cols x rows). */
    private val fallbacks = listOf(1 to 1, 2 to 1, 3 to 1, 2 to 2, 3 to 2, 3 to 3)

    /**
     * Splits the scenes into grid batches. The last batch often has fewer scenes than the grid has
     * cells, so it gets the smallest layout that fits them (e.g. 5 leftover scenes -> 3x2, 4 -> 2x2),
     * keeping the panel count honest so the cut is right.
     */
    fun batches(project: Project): List<GridBatch> {
        val g = project.grid
        val chunks = project.scenes.chunked(g.cells).map { it.toMutableList() }.toMutableList()
        // Never end with a lonely single-scene "grid": borrow one scene from the previous grid.
        if (g.cells > 1 && chunks.size >= 2 && chunks.last().size == 1) {
            val moved = chunks[chunks.size - 2].removeAt(chunks[chunks.size - 2].lastIndex)
            chunks.last().add(0, moved)
        }
        var first = 0
        return chunks.mapIndexed { i, chunk ->
            val (c, r) = if (chunk.size == g.cells) g.cols to g.rows else fit(chunk.size, g.cells, project.aspect)
            GridBatch(i, first, chunk, c, r).also { first += chunk.size }
        }
    }

    fun fit(n: Int, maxCells: Int, aspect: Aspect): Pair<Int, Int> {
        val best = fallbacks.filter { (c, r) -> c * r in n..maxCells }
            .minWithOrNull(compareBy({ it.first * it.second }, { abs(it.first - it.second) }))
            ?: fallbacks.first { (c, r) -> c * r >= n }
        // Portrait videos: stack panels vertically instead (2x1 -> 1x2 etc.).
        return if (aspect == Aspect.PORTRAIT && best.first > best.second) best.second to best.first else best
    }

    /** Scene prompt without the shared style text (the grid prompt states the style once). */
    private fun describe(s: Scene): String {
        var d = s.prompt.replace(ScriptPlanner.STYLE_SUFFIX, "").trim()
        d = d.replace(Regex("^Layered paper cut-out craft illustration, [0-9:]+ frame\\.\\s*"), "")
        return d.ifBlank { s.text }
    }

    private fun overallRatio(cols: Int, rows: Int, a: Aspect): String {
        val r = cols * a.w.toDouble() / (rows * a.h)
        val options = listOf("1:1" to 1.0, "3:2" to 1.5, "2:3" to 0.667, "16:9" to 1.778, "9:16" to 0.5625, "4:3" to 1.333, "3:4" to 0.75)
        return options.minBy { abs(it.second - r) }.first
    }

    fun prompt(project: Project, b: GridBatch): String {
        if (b.cells == 1) return b.scenes.first().prompt
        val pos = { i: Int -> "row ${i / b.cols + 1}, column ${i % b.cols + 1}" }
        return buildString {
            append("ONE image laid out as a ${b.cols}x${b.rows} grid: exactly ${b.cells} equal-size separate panels ")
            append("(${b.cols} columns, ${b.rows} rows), read left to right, top to bottom. ")
            append("Thin plain white gutters between panels, no frames, no panel numbers, no text anywhere. ")
            append("Every panel is its own ${project.aspect.ratio} scene. Overall image aspect ratio about ${overallRatio(b.cols, b.rows, project.aspect)}.\n")
            append("Same style in every panel: layered paper cut-out craft illustration. ${ScriptPlanner.STYLE_SUFFIX}\n")
            if (project.characterNote.isNotBlank()) append("Characters (identical in every panel): ${project.characterNote.trim()}.\n")
            if (project.styleNote.isNotBlank()) append("Style: ${project.styleNote.trim()}.\n")
            b.scenes.forEachIndexed { i, s -> append("\nPanel ${i + 1} (${pos(i)}): ${describe(s)}") }
            for (i in b.scenes.size until b.cells) append("\nPanel ${i + 1} (${pos(i)}): leave completely empty, plain white.")
        }
    }

    fun allPrompts(project: Project): String = buildString {
        val bs = batches(project)
        appendLine("${project.name} - ${bs.size} grid prompts for ${project.scenes.size} scenes")
        appendLine("Save the images as grid_1, grid_2 ... then use 'Import grid images' in the app.")
        bs.forEach { b ->
            appendLine()
            appendLine("=== Grid ${b.index + 1}: scenes ${b.first + 1}-${b.last + 1} (${b.cols}x${b.rows}) ===")
            appendLine(prompt(project, b))
        }
    }
}
