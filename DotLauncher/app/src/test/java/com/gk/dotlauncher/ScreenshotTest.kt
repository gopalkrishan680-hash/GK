package com.gk.dotlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File
import java.io.FileOutputStream

/** UI ko PNG me render karta hai (bina phone/emulator ke design check karne ke liye). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private fun shot(v: View, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val out = File(System.getProperty("shotDir") ?: "build/shots").apply { mkdirs() }
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(a.window.decorView.rootView, "home")
    }

    @Test fun settings() {
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shot(a.window.decorView.rootView, "settings")
    }
}
