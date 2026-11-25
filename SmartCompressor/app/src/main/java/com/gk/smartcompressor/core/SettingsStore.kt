package com.gk.smartcompressor.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User ki last chuni hui settings yaad rakhta hai. */
class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _options = MutableStateFlow(load())
    val options: StateFlow<Options> = _options

    private inline fun <reified T : Enum<T>> e(key: String, def: T): T =
        runCatching { enumValueOf<T>(p.getString(key, def.name)!!) }.getOrDefault(def)

    private fun load() = Options(
        level = e("level", Level.BALANCED),
        targetReduction = p.getInt("target", -1).takeIf { it > 0 },
        videoRes = e("res", VideoRes.AUTO),
        removeAudio = p.getBoolean("noAudio", false),
        h264Compat = p.getBoolean("h264", false),
        imageFormat = e("imgFmt", ImageFormat.JPEG),
        pdfMode = e("pdfMode", PdfMode.SMART),
    )

    fun update(o: Options) {
        _options.value = o
        p.edit()
            .putString("level", o.level.name)
            .putInt("target", o.targetReduction ?: -1)
            .putString("res", o.videoRes.name)
            .putBoolean("noAudio", o.removeAudio)
            .putBoolean("h264", o.h264Compat)
            .putString("imgFmt", o.imageFormat.name)
            .putString("pdfMode", o.pdfMode.name)
            .apply()
    }
}
