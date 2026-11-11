package com.gk.meriawaaz

import android.content.Context
import java.io.File

/** The ONNX models ship inside the APK; ONNX Runtime needs them as ordinary files. */
object Models {
    class Paths(val encoder: String, val converter: String, val tts: String, val ttsTokens: String)

    private val names = listOf("tone_encoder.onnx", "converter.onnx", "tts_hi.onnx", "tts_hi_tokens.txt")

    /** Copies the models out of the APK once per installed version. */
    fun prepare(context: Context): Paths {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = File(context.filesDir, "models-$installed")
        context.filesDir.listFiles { f -> f.name.startsWith("models-") && f != dir }
            ?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        for (name in names) {
            val target = File(dir, name)
            if (target.exists()) continue
            val tmp = File(dir, "$name.tmp")
            context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            check(tmp.renameTo(target)) { "could not store $name" }
        }
        val path = names.map { File(dir, it).path }
        return Paths(path[0], path[1], path[2], path[3])
    }
}
