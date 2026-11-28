package com.gk.procam

import android.app.Application
import android.os.Build
import java.io.File

/** Saves any crash to a file so the next launch can show it instead of silently closing. */
class ProCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile(this).writeText(
                    "ProCam ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                        "Thread: ${t.name}\n\n" + e.stackTraceToString()
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "last_crash.txt")
    }
}
