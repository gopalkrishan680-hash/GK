package com.gk.meriawaaz

import android.content.Context
import java.io.File

/** The ONNX models ship inside the APK; ONNX Runtime needs them as ordinary files. */
object Models {
    private val names = listOf("tone_encoder.onnx", "converter.onnx")

    /** Copies the models out of the APK once per installed version. Returns (encoder, converter) paths. */
    fun prepare(context: Context): Pair<String, String> {
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
        return File(dir, names[0]).path to File(dir, names[1]).path
    }
}
