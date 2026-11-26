package com.gk.papercut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gk.papercut.ml.Upscaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
class UpscalerTest {
    private fun sharpness(b: Bitmap): Double {
        var sum = 0.0; var n = 0
        for (y in 1 until b.height - 1 step 2) for (x in 1 until b.width - 1 step 2) {
            val a = b.getPixel(x, y) and 255; val r = b.getPixel(x + 1, y) and 255; val d = b.getPixel(x, y + 1) and 255
            sum += abs(a - r) + abs(a - d); n++
        }
        return sum / n
    }

    @Test fun upscalesPanelSharperThanBicubic() {
        val model = File(System.getProperty("seg.models") ?: "", "esr256.onnx")
        val img = System.getProperty("seg.image")?.let { File(it) }
        assumeTrue(model.exists() && img != null && img.exists())
        val out = File(System.getProperty("render.out")!!)
        val panel = Bitmap.createScaledBitmap(BitmapFactory.decodeFile(img!!.path), 768, 432, true)
        val t0 = System.currentTimeMillis()
        val up = Upscaler.upscale(panel, model)
        println("upscaled ${panel.width}x${panel.height} -> ${up.width}x${up.height} in ${System.currentTimeMillis() - t0} ms")
        assertEquals(1600, up.width)
        val bic = Bitmap.createScaledBitmap(panel, up.width, up.height, true)
        val sAi = sharpness(up); val sBic = sharpness(bic)
        println("sharpness ai=%.2f bicubic=%.2f".format(sAi, sBic))
        assertTrue(sAi > sBic * 1.15)
        up.compress(Bitmap.CompressFormat.PNG, 100, File(out, "upscaled.png").outputStream())
    }
}
