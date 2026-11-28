package com.gk.procam

import android.Manifest
import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LaunchTest {
    @Test fun launchWithoutPermission() {
        Robolectric.buildActivity(MainActivity::class.java).setup()
    }
    @Test fun launchWithPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        Robolectric.buildActivity(MainActivity::class.java).setup()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }
}
