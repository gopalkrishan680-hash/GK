package com.gk.facelessstudio.engine

import com.gk.facelessstudio.model.Aspect
import com.gk.facelessstudio.model.Project

/**
 * "Grid" image workflow: the user's own ChatGPT / Gemini draws several scenes in ONE image
 * (a storyboard grid), the app cuts it into panels and gives every scene its own picture.
 */
object GridWorkflow {

    data class StylePreset(val id: String, val label: String, val desc: String, val darkLines: Boolean)

    val STYLES = listOf(
        StylePreset("storybook", "Soft 2D storybook",
            "Soft 2D storybook illustration of an Indian tale: warm pastel colours, gentle gradients, rounded friendly characters with kind expressive faces, traditional Indian clothes, light paper grain, cosy and emotional mood", false),
        StylePreset("whiteboard", "Whiteboard doodle",
            "Whiteboard doodle illustration: only the main subject (characters / key objects) drawn with bold clean black outlines and flat bright colours, on a plain pure white background, no scenery, no background fill, plenty of white space", false),
        StylePreset("stickman", "Stickman doodle",
            "Simple stickman doodle: clean black line-art stick figures with round heads and expressive poses, minimal flat backgrounds, white paper, a few accent colours, funny and clear", false),
        StylePreset("documentary", "Dark documentary",
            "Dark cinematic documentary still: realistic, moody low-key lighting, deep shadows, muted desaturated colours with warm highlights, film grain, dramatic and mysterious", true),
        StylePreset("mythology", "Epic mythology painting",
            "Epic Indian mythology digital painting: rich gold, saffron and deep blue tones, divine glowing light, ornate ancient costumes and jewellery, palaces and battlefields, dramatic heroic composition", true),
        StylePreset("realistic", "Cinematic realistic",
            "Cinematic realistic photo: natural skin and textures, shallow depth of field, golden-hour or dramatic lighting, 35mm film look, high detail", true),
        StylePreset("anime", "Anime",
            "Anime illustration: clean line art, vibrant cel shading, expressive eyes, detailed painted backgrounds, dynamic camera angles", false),
        StylePreset("pixar", "3D animated",
            "3D animated movie style: soft global illumination, appealing stylised characters with big expressive eyes, rich colours, detailed props", false),
        StylePreset("comic", "Vintage comic ink",
            "Vintage comic book art: bold ink outlines, halftone shading, limited warm colour palette, strong dramatic shadows", true),
    )

    fun preset(id: String) = STYLES.firstOrNull { it.id == id } ?: STYLES.first()

    fun styleText(p: Project) = p.styleDesc.ifBlank { preset(p.artStyle).desc }

    /** Line colour actually asked for between panels. */
    fun lineColor(p: Project): String = if (p.doodle && p.separator == "auto") "black" else when (p.separator) {
        "white" -> "white"; "black" -> "black"; "none" -> "none"
        else -> if (preset(p.artStyle).darkLines) "black" else "white"
    }

    /** Script pieces exactly as the video will be cut (one image each). */
    fun pieces(p: Project): List<String> = Director.analysisFor(p).chunks.map { it.text }

    data class Grid(val index: Int, val from: Int, val to: Int, val cols: Int, val rows: Int) {
        val count get() = to - from
    }

    fun shape(n: Int): Pair<Int, Int> = when {
        n <= 1 -> 1 to 1
        n == 2 -> 2 to 1
        n == 3 -> 3 to 1
        n == 4 -> 2 to 2
        n <= 6 -> 3 to 2
        else -> 3 to 3
    }

    /** Panel counts AI image tools draw reliably as a regular grid. */
    private val NICE = listOf(9, 6, 4, 3, 2, 1)

    fun grids(total: Int, size: Int): List<Grid> {
        val per = NICE.firstOrNull { it <= size.coerceIn(1, 9) } ?: 1
        val out = ArrayList<Grid>()
        var from = 0
        while (from < total) {
            val left = total - from
            // never ask for an odd leftover like 5, 7 or 8 panels: AI tools then draw uneven rows
            val n = if (left >= per) per else NICE.first { it <= left }
            val (c, r) = shape(n)
            out += Grid(out.size, from, from + n, c, r)
            from += n
        }
        return out
    }

    private fun ratio(a: Aspect) = a.sub

    private fun characterBlock(p: Project): String {
        val lines = p.characters.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.isEmpty()) "" else "Characters (draw them EXACTLY like this in every panel):\n" + lines.joinToString("\n") { "- $it" } + "\n"
    }

    /** Prompt for one grid image. */
    fun gridPrompt(p: Project, g: Grid, pieces: List<String>): String {
        val line = lineColor(p)
        val ratio = ratio(p.aspect)
        val sb = StringBuilder()
        sb.append("Create ONE single image that is a storyboard grid of ${g.count} equal panels: ${g.cols} columns × ${g.rows} rows. ")
        sb.append("The whole image must have aspect ratio ${gridRatio(p.aspect, g)} so that EVERY panel is exactly $ratio. ")
        if (line == "none") sb.append("Panels touch each other with no gaps or borders. ")
        else sb.append("Separate the panels with thin straight $line lines of equal width, with NO outer margin or frame. ")
        sb.append("Read order: left to right, top to bottom. ")
        sb.append("Absolutely NO text, letters, numbers, captions, labels or speech bubbles anywhere in the image.\n\n")
        sb.append("Art style (identical in every panel): ").append(styleText(p)).append(".\n")
        if (p.doodle) sb.append(DOODLE_RULE).append("\n")
        sb.append(characterBlock(p))
        if (p.title.isNotBlank()) sb.append("Story: ").append(p.title).append(".\n")
        sb.append("\n")
        for (i in g.from until g.to) {
            val visual = p.visuals.getOrNull(i)?.takeIf { it.isNotBlank() }
            sb.append("Panel ${i - g.from + 1}: ")
            if (visual != null) sb.append(visual) else sb.append("illustrate this narration line: \"").append(pieces.getOrElse(i) { "" }).append("\"")
            sb.append("\n")
        }
        sb.append("\nMake each panel a different, clear composition (vary wide shots and close-ups). Generate the image now, do not ask questions.")
        return sb.toString()
    }

    /** Whiteboard pictures: just the subject on white so it reads clearly when it is sketched on the board. */
    private const val DOODLE_RULE = "WHITEBOARD RULE for every panel: draw ONLY the main subject (characters and the key objects) in the middle of the panel " +
        "on a plain pure WHITE background — no scenery, no sky, no ground, no background colour or texture, no shadows on the background; " +
        "bold clear outlines, flat colours, lots of empty white space around the subject."

    /** Overall image ratio so each panel keeps the video ratio, e.g. 3×3 of 16:9 -> 16:9. */
    private fun gridRatio(a: Aspect, g: Grid): String {
        val w = a.ratioW * g.cols
        val h = a.ratioH * g.rows
        val gcd = gcd(w, h)
        return "${w / gcd}:${h / gcd}"
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Prompt that asks an AI chat to write one visual description per scene. */
    fun directorPrompt(p: Project, pieces: List<String>, indices: List<Int>): String {
        val sb = StringBuilder()
        sb.append("You are the visual director of a faceless YouTube video")
        if (p.title.isNotBlank()) sb.append(" called \"").append(p.title).append("\"")
        sb.append(". Below are numbered narration scenes (they may be in Hindi, Hinglish or English).\n")
        sb.append("For EACH scene write ONE English visual description (15-30 words) of the single image that should be on screen: ")
        sb.append("which characters (by name), what they are doing, the place, and the shot type (wide, medium, close-up, over-the-shoulder, top view). ")
        sb.append("Show exactly what the line is about, keep the story continuous, and vary the shots. No text in the images.\n")
        sb.append("Art style: ").append(styleText(p)).append(".\n")
        if (p.doodle) sb.append("These pictures will be DRAWN on a whiteboard, so describe ONLY the main subject of each scene (the characters and the one or two key objects, their pose and expression) — no scenery, no landscape, no background details.\n")
        sb.append(characterBlock(p))
        sb.append("\nReply ONLY with lines in the form  number: description  — one line per scene, same numbers, nothing else.\n\n")
        for (i in indices) sb.append(i + 1).append(": ").append(pieces.getOrElse(i) { "" }).append("\n")
        return sb.toString()
    }

    /** Parses "12: description" / "**12.** description" lines from an AI chat reply. Returns index -> visual. */
    fun parseDirector(reply: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        Regex("^[\\s*#>-]*(?:scene\\s*)?(\\d{1,4})\\s*[:.)\\-–]+\\**\\s*(.+)$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .findAll(reply).forEach { m ->
                val idx = m.groupValues[1].toInt() - 1
                val text = m.groupValues[2].trim().trim('*', '"', ' ')
                if (idx >= 0 && text.length > 3) out[idx] = text
            }
        return out
    }
}
