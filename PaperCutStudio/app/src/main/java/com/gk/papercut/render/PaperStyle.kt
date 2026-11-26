package com.gk.papercut.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Turns a plain cut-out into a "real paper" piece: white cut edge, paper grain and a soft drop shadow. */
object PaperStyle {

    class Styled(val bitmap: Bitmap, val pad: Int)

    val texture: Bitmap by lazy { makeTexture() }

    fun style(cut: Bitmap, grain: Boolean): Styled {
        val edge = max(2, min(cut.width, cut.height) / 70).coerceAtMost(6)
        val shadowOff = edge * 2
        val blur = edge * 3
        val pad = edge + shadowOff + blur
        val w = cut.width + pad * 2; val h = cut.height + pad * 2

        // 1) content = white edge + picture (+ grain)
        val content = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(content)
        val alpha = cut.extractAlpha()
        val white = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.rgb(252, 250, 244) }
        val steps = 16
        for (i in 0 until steps) {
            val a = i * 2 * Math.PI / steps
            c.drawBitmap(alpha, pad + (cos(a) * edge).toFloat(), pad + (sin(a) * edge).toFloat(), white)
        }
        c.drawBitmap(cut, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        if (grain) {
            val p = Paint().apply {
                shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }

        // 2) soft shadow from the content's alpha (downscale/upscale = cheap blur)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val o = Canvas(out)
        val shadowAlpha = content.extractAlpha()
        val f = max(1, blur)
        val small = Bitmap.createScaledBitmap(shadowAlpha, max(1, w / f), max(1, h / f), true)
        val soft = Bitmap.createScaledBitmap(small, w, h, true)
        val sp = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.argb(110, 40, 25, 10) }
        o.drawBitmap(soft, shadowOff * 0.6f, shadowOff.toFloat(), sp)
        o.drawBitmap(content, 0f, 0f, null)
        content.recycle(); small.recycle(); soft.recycle(); alpha.recycle(); shadowAlpha.recycle()
        return Styled(out, pad)
    }

    /** Background plate with paper grain and a gentle vignette. */
    fun styleBackground(bg: Bitmap, grain: Boolean): Bitmap {
        val out = bg.copy(Bitmap.Config.ARGB_8888, true)
        if (!grain) return out
        val c = Canvas(out)
        c.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply {
            shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        })
        val r = max(out.width, out.height) * 0.75f
        c.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply {
            shader = android.graphics.RadialGradient(
                out.width / 2f, out.height / 2f, r,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(70, 60, 35, 10)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
        })
        return out
    }

    private fun makeTexture(): Bitmap {
        val s = 512
        val rnd = Random(7)
        val px = IntArray(s * s) {
            val v = 236 + rnd.nextInt(20)
            Color.rgb(v, v - 2, v - 6)
        }
        // A few paper fibres.
        repeat(700) {
            var x = rnd.nextInt(s).toFloat(); var y = rnd.nextInt(s).toFloat()
            val ang = rnd.nextFloat() * 6.28f
            repeat(6 + rnd.nextInt(14)) {
                val i = ((y.toInt() + s) % s) * s + (x.toInt() + s) % s
                px[i] = Color.rgb(222, 218, 210)
                x += cos(ang); y += sin(ang)
            }
        }
        return Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888)
    }
}
