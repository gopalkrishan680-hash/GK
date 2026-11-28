package com.gk.procam

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Persistent, Compose-observable app settings. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("procam_settings", Context.MODE_PRIVATE)

    // Composition
    var grid by boolPref("grid", true)
    var level by boolPref("level", true)
    var aspect by intPref("aspect", 0)            // 0 = 4:3, 1 = 16:9, 2 = 1:1
    var timer by intPref("timer", 0)              // 0, 3, 10 seconds
    var flash by intPref("flash", 2)              // 0 = auto, 1 = on, 2 = off
    var style by intPref("style", 0)              // PhotoStyle ordinal

    // Photo
    var highRes by boolPref("high_res", false)    // 50 MP full sensor
    var smartHdr by boolPref("smart_hdr", true)   // use device HDR extension if present
    var fusion by intPref("fusion", 1)            // 0 = off, 1 = auto, 2 = always
    var ultraHdr by boolPref("ultra_hdr", false)
    var proRaw by boolPref("pro_raw", true)
    var mirrorFront by boolPref("mirror_front", true)
    var nightEngine by intPref("night_engine", 0) // 0 = auto (device if available), 1 = ProCam stack
    var portraitEngine by intPref("portrait_engine", 0) // 0 = ProCam AI, 1 = device bokeh
    var qrScan by boolPref("qr_scan", true)

    // Video
    var videoRes by intPref("video_res", 0)       // 0 = 4K, 1 = 1080p
    var videoFps by intPref("video_fps", 30)      // 30 / 60
    var hdrVideo by boolPref("hdr_video", false)
    var stabilization by boolPref("stabilization", true)
    var slomoFps by intPref("slomo_fps", 240)     // 120 / 240
    var timelapseSpeed by intPref("timelapse_speed", 15)

    // General
    var shutterSound by boolPref("shutter_sound", true)
    var volumeShutter by boolPref("volume_shutter", true)
    var haptics by boolPref("haptics", true)

    private fun boolPref(key: String, def: Boolean) = Pref(key, def,
        { prefs.getBoolean(key, def) }, { prefs.edit().putBoolean(key, it).apply() })

    private fun intPref(key: String, def: Int) = Pref(key, def,
        { prefs.getInt(key, def) }, { prefs.edit().putInt(key, it).apply() })

    private class Pref<T>(
        val key: String,
        def: T,
        read: () -> T,
        val write: (T) -> Unit,
    ) : ReadWriteProperty<Any?, T> {
        private val state: MutableState<T> = mutableStateOf(runCatching(read).getOrDefault(def))
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            write(value)
        }
    }
}
