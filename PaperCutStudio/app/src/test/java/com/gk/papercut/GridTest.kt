package com.gk.papercut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.gk.papercut.data.Aspect
import com.gk.papercut.data.GridLayout
import com.gk.papercut.data.GridPlanner
import com.gk.papercut.data.Project
import com.gk.papercut.data.ScriptPlanner
import com.gk.papercut.ml.GridSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GridTest {

    private fun project(n: Int, grid: GridLayout, aspect: Aspect = Aspect.LANDSCAPE): Project {
        val script = (1..n).joinToString("\n\n") { "Scene number $it: Raju ghar gaya." }
        val p = Project(id = "g", name = "g", script = script, aspect = aspect, grid = grid)
        return p.copy(scenes = ScriptPlanner.plan(p))
    }

    @Test fun lastBatchGetsSmallerLayout() {
        val b = GridPlanner.batches(project(14, GridLayout.G3X3))
        assertEquals(listOf(9, 5), b.map { it.scenes.size })
        assertEquals(3 to 3, b[0].cols to b[0].rows)
        assertEquals(3 to 2, b[1].cols to b[1].rows) // 5 scenes -> 6 cells, 1 left empty
        val prompt = GridPlanner.prompt(project(14, GridLayout.G3X3), b[1])
        println(prompt)
        assertTrue(prompt.contains("3x2 grid"))
        assertTrue(prompt.contains("Panel 6 (row 2, column 3): leave completely empty"))
        assertEquals(listOf(2 to 2, 2 to 1), GridPlanner.batches(project(6, GridLayout.G2X2)).map { it.cols to it.rows })
        assertEquals(listOf(3 to 3, 2 to 2), GridPlanner.batches(project(13, GridLayout.G3X3)).map { it.cols to it.rows })
        assertEquals(listOf(5, 2), GridPlanner.batches(project(7, GridLayout.G3X2)).map { it.scenes.size }) // no lonely 1x1 grid
        assertEquals(listOf(3 to 2, 2 to 1), GridPlanner.batches(project(7, GridLayout.G3X2)).map { it.cols to it.rows })
        assertEquals(listOf(0, 5), GridPlanner.batches(project(7, GridLayout.G3X2)).map { it.first })
        assertEquals(listOf(2 to 2, 1 to 2), GridPlanner.batches(project(6, GridLayout.G2X2, Aspect.PORTRAIT)).map { it.cols to it.rows })
    }

    /** Builds an uneven AI-like grid (different gutter widths, last panel empty) and cuts it. */
    @Test fun splitsUnevenGridAndSkipsEmptyPanel() {
        val out = File(System.getProperty("render.out")!!).apply { mkdirs() }
        val src = System.getProperty("seg.image")?.takeIf { File(it).exists() }?.let { BitmapFactory.decodeFile(it) }
        val W = 1536; val H = 1024
        val grid = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(grid); c.drawColor(Color.WHITE)
        val xs = listOf(0 to 500, 512 to 1020, 1040 to 1536)   // gutters 12 px and 20 px, uneven widths
        val ys = listOf(6 to 330, 345 to 680, 690 to 1018)
        val colors = listOf(Color.rgb(200, 60, 50), Color.rgb(60, 140, 80), Color.rgb(50, 90, 200), Color.rgb(230, 170, 40))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var k = 0
        for (r in 0 until 3) for (cc in 0 until 3) {
            if (r == 2 && cc == 2) continue // empty panel
            val rect = Rect(xs[cc].first, ys[r].first, xs[cc].second, ys[r].second)
            if (src != null) {
                // Panel with the user's white-background art (white touching the gutter!)
                c.drawBitmap(src, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                p.color = colors[k % 4]; c.drawRect(rect, p)
            }
            p.color = colors[k % 4]; c.drawCircle(rect.exactCenterX(), rect.exactCenterY(), 30f + k * 3, p)
            k++
        }
        grid.compress(Bitmap.CompressFormat.PNG, 100, File(out, "grid_input.png").outputStream())
        val cells = GridSplitter.split(grid, 3, 3)
        assertEquals(9, cells.size)
        cells.forEachIndexed { i, cell ->
            println("cell $i: ${cell.bitmap?.let { "${it.width}x${it.height}" } ?: "EMPTY"}")
        }
        assertNull(cells[8].bitmap)
        for (i in 0 until 8) {
            val b = assertNotNullGet(cells[i].bitmap)
            val ew = xs[i % 3].second - xs[i % 3].first; val eh = ys[i / 3].second - ys[i / 3].first
            assertTrue("cell $i width ${b.width} vs $ew", abs(b.width - ew) < ew * 0.06)
            assertTrue("cell $i height ${b.height} vs $eh", abs(b.height - eh) < eh * 0.06)
        }
        val sheet = Bitmap.createBitmap(W + 60, H + 60, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet); sc.drawColor(Color.DKGRAY)
        cells.forEach { cell -> cell.bitmap?.let { sc.drawBitmap(it, cell.col * (W / 3f + 30), cell.row * (H / 3f + 30), null) } }
        sheet.compress(Bitmap.CompressFormat.PNG, 100, File(out, "grid_cells.png").outputStream())
    }

    /** A painted panel with a thin white gutter remnant must NOT be treated as a plain background. */
    @Test fun thinWhiteBorderIsNotPlainBackground() {
        val b = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE)
        val p = Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 800f, 450f, Color.rgb(180, 120, 60), Color.rgb(90, 60, 140), android.graphics.Shader.TileMode.CLAMP) }
        c.drawRect(6f, 6f, 794f, 444f, p)
        assertNull(com.gk.papercut.ml.PlainBackground.detect(b))
        val plain = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888)
        Canvas(plain).apply { drawColor(Color.rgb(252, 250, 246)); drawCircle(400f, 225f, 150f, Paint().apply { color = Color.RED }) }
        assertNotNull(com.gk.papercut.ml.PlainBackground.detect(plain))
        System.getProperty("seg.image")?.takeIf { File(it).exists() }?.let { assertNotNull(com.gk.papercut.ml.PlainBackground.detect(BitmapFactory.decodeFile(it))) }
    }

    private fun <T> assertNotNullGet(v: T?): T { assertNotNull(v); return v!! }
}
