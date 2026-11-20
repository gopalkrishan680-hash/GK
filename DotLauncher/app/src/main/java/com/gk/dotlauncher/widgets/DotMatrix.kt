package com.gk.dotlauncher.widgets

import android.graphics.Canvas
import android.graphics.Paint

/**
 * 5x7 dot-matrix font. Har glyph 7 rows ka hai, '#' = jalta dot, '.' = bujha dot.
 * Bujhe dots halka grey me dikhte hain, jisse asli LED-matrix jaisa look aata hai.
 */
object DotMatrix {
    const val ROWS = 7

    private val glyphs: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        ':' to arrayOf(".", ".", "#", ".", "#", ".", "."),
        '%' to arrayOf("##..#", "##.#.", "...#.", "..#..", ".#...", ".#.##", "#..##"),
        '-' to arrayOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        ';' to arrayOf(".", ".", ".", ".", ".", ".", "."), // colon jaisi width ka blank (blink ke liye)
        ' ' to arrayOf("..", "..", "..", "..", "..", "..", ".."),
    )

    private fun glyph(c: Char) = glyphs[c] ?: glyphs[' ']!!

    /** Text kitne columns ka banega (glyphs ke beech 1 column gap). */
    fun columns(text: String): Int =
        if (text.isEmpty()) 0 else text.sumOf { glyph(it)[0].length } + text.length - 1

    /**
     * @param step ek dot se agle dot ki doori (px)
     * @param off  null ho to bujhe dots nahi bante
     */
    fun draw(
        canvas: Canvas, text: String, left: Float, top: Float, step: Float,
        on: Paint, off: Paint?, accentChars: Set<Char> = emptySet(), accent: Paint? = null,
    ) {
        val r = step * 0.38f
        var x = left
        for (ch in text) {
            val g = glyph(ch)
            val paint = if (ch in accentChars && accent != null) accent else on
            for (row in 0 until ROWS) {
                val line = g[row]
                for (col in line.indices) {
                    val cx = x + col * step + step / 2
                    val cy = top + row * step + step / 2
                    if (line[col] == '#') canvas.drawCircle(cx, cy, r, paint)
                    else if (off != null) canvas.drawCircle(cx, cy, r * 0.8f, off)
                }
            }
            x += (g[0].length + 1) * step
        }
    }
}
