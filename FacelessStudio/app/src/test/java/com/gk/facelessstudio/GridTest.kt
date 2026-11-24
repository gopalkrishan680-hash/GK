package com.gk.facelessstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.gk.facelessstudio.engine.GridCutter
import com.gk.facelessstudio.engine.GridWorkflow
import com.gk.facelessstudio.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GridTest {

    private val colors = intArrayOf(0xFFB03030.toInt(), 0xFF30A040.toInt(), 0xFF3050C0.toInt(), 0xFFC0A020.toInt(), 0xFF8030A0.toInt(),
        0xFF20A0A0.toInt(), 0xFFD06020.toInt(), 0xFF607080.toInt(), 0xFF904020.toInt())

    /** Builds a fake AI grid: noisy coloured panels, divider lines slightly off the exact thirds, optional outer frame. */
    private fun grid(w: Int, h: Int, cols: Int, rows: Int, line: Int?, lineW: Int, frame: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint()
        c.drawColor(line ?: Color.GRAY)
        val r = Random(3)
        val xs = (0..cols).map { k -> if (k == 0) frame else if (k == cols) w - frame else w * k / cols + r.nextInt(-12, 12) }
        val ys = (0..rows).map { k -> if (k == 0) frame else if (k == rows) h - frame else h * k / rows + r.nextInt(-12, 12) }
        for (row in 0 until rows) for (col in 0 until cols) {
            val g = if (line == null) 0 else lineW / 2
            p.color = colors[(row * cols + col) % colors.size]
            c.drawRect((xs[col] + g).toFloat(), (ys[row] + g).toFloat(), (xs[col + 1] - g).toFloat(), (ys[row + 1] - g).toFloat(), p)
            // texture so panels are not flat (like real art)
            repeat(400) {
                p.color = Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256))
                val x = r.nextInt(xs[col] + g + 2, xs[col + 1] - g - 4).toFloat(); val y = r.nextInt(ys[row] + g + 2, ys[row + 1] - g - 4).toFloat()
                c.drawRect(x, y, x + 3, y + 3, p)
            }
        }
        return b
    }

    private fun check(panels: List<Bitmap>, cols: Int, rows: Int, w: Int, h: Int) {
        assertEquals(cols * rows, panels.size)
        panels.forEachIndexed { i, bmp ->
            // the panel must be its own colour all the way to the edges (no line or neighbour leaking in)
            for ((x, y) in listOf(2 to 2, bmp.width - 3 to 2, 2 to bmp.height - 3, bmp.width - 3 to bmp.height - 3, bmp.width / 2 to bmp.height / 2)) {
                val px = bmp.getPixel(x, y); val want = colors[i % colors.size]
                val d = abs(Color.red(px) - Color.red(want)) + abs(Color.green(px) - Color.green(want)) + abs(Color.blue(px) - Color.blue(want))
                if (d > 60) {
                    // allow a texture dot, check a neighbour pixel instead
                    val px2 = bmp.getPixel((x + 5).coerceAtMost(bmp.width - 1), y)
                    val d2 = abs(Color.red(px2) - Color.red(want)) + abs(Color.green(px2) - Color.green(want)) + abs(Color.blue(px2) - Color.blue(want))
                    assertTrue("panel $i at $x,$y off by $d", d2 <= 60)
                }
            }
            assertTrue("panel $i width ${bmp.width}", bmp.width > w / cols * 0.85)
            assertTrue("panel $i height ${bmp.height}", bmp.height > h / rows * 0.85)
        }
    }

    @Test fun whiteLines3x3() { check(GridCutter.cut(grid(1920, 1080, 3, 3, Color.WHITE, 14, 0), 3, 3), 3, 3, 1920, 1080) }
    @Test fun blackLinesWithFrame() { check(GridCutter.cut(grid(1536, 1024, 3, 3, Color.BLACK, 10, 16), 3, 3), 3, 3, 1536, 1024) }
    @Test fun twoByTwo() { check(GridCutter.cut(grid(1600, 900, 2, 2, Color.WHITE, 8, 0), 2, 2), 2, 2, 1600, 900) }

    @Test fun promptsLookRight() {
        val script = "# Udyog Parva\nKrishna ne Karna ko raja banne ka offer diya.\nTum Kunti ke sabse bade bete ho.\nPaanchon Pandav tumhare charan chhuenge.\nKarna ne mana kar diya."
        val p = Project(id = "t", title = "Karna ka faisla", script = script, characters = "Karna: tall warrior, golden armour\nKrishna: blue skin, peacock feather crown")
        val pieces = GridWorkflow.pieces(p)
        val grids = GridWorkflow.grids(pieces.size, 9)
        assertEquals(1, grids.size); assertEquals(2, grids[0].cols); assertEquals(2, grids[0].rows)
        val gp = GridWorkflow.gridPrompt(p, grids[0], pieces)
        println("GRID PROMPT:\n$gp")
        assertTrue(gp.contains("2 columns × 2 rows")); assertTrue(gp.contains("Karna: tall warrior"))
        println("DIRECTOR PROMPT:\n" + GridWorkflow.directorPrompt(p, pieces, pieces.indices.toList()))
        val parsed = GridWorkflow.parseDirector("Here you go:\n**1:** Krishna talks to Karna by the river\n2. Close-up of Karna\n3) Pandavs bowing\n- 4: Karna turns away")
        assertEquals(4, parsed.size); assertEquals("Krishna talks to Karna by the river", parsed[0])
        assertEquals(listOf(9, 9, 9, 5), GridWorkflow.grids(32, 9).map { it.count })
    }
}
