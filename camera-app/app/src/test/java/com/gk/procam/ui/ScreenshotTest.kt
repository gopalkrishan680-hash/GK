package com.gk.procam.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.gk.procam.AppSettings
import com.gk.procam.CameraEngine
import com.gk.procam.Mode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, mode: Mode, settings: Boolean = false) {
        val act = rule.activity
        val e = CameraEngine(act, AppSettings(act)).apply { demoState(mode) }
        rule.setContent { if (settings) SettingsSheet(e) {} else CameraScreen(e) }
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Test fun photo() = shot("photo", Mode.PHOTO)
    @Test fun video() = shot("video", Mode.VIDEO)
    @Test fun pro() = shot("pro", Mode.PRO)
    @Test fun settings() = shot("settings", Mode.PHOTO, settings = true)
}
