package com.gopalkrishan.stickmanstudio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI4Bharat Indic-TTS Hindi (FastPitch + HiFiGAN, CC-BY-4.0) as one ONNX file, made by
 * tools/indic-tts/export.py and published as this repository's "indic-tts-hi-v1" release.
 * Downloaded once (about 270 MB), then it speaks offline: Hindi characters in, 22.05 kHz speech out.
 */
class IndicVoice(ctx: Context) {
    private val dir = File(ctx.filesDir, "indic")
    private val model = File(dir, "indic-hi.onnx")
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    val ready: Boolean get() = model.length() > 200_000_000

    fun sizeOnDisk(): Long = if (model.exists()) model.length() else 0

    fun download(progress: (Long, Long) -> Unit) {
        dir.mkdirs()
        val part = File(dir, model.name + ".part")
        val conn = (URL(URL_MODEL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) throw RuntimeException(if (conn.responseCode == 404) "the voice file is not published yet" else "download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 272_852_660L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16); var got = 0L; var last = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (got - last > 1_000_000) { last = got; progress(got, total) }
                    }
                    progress(got, total)
                }
            }
        } finally { conn.disconnect() }
        close()
        if (!part.renameTo(model)) throw RuntimeException("could not save the voice file")
    }

    fun delete() { close(); dir.deleteRecursively() }

    private fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    private fun open(): OrtSession {
        session?.let { return it }
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return e.createSession(model.absolutePath, opts).also { session = it }
    }

    /** character ids -> 22.05 kHz mono samples. speaker: 0 female, 1 male. */
    fun speak(tokens: LongArray, speaker: Long, speed: Float): FloatArray {
        val s = open(); val e = env!!
        OnnxTensor.createTensor(e, arrayOf(tokens)).use { t ->
            OnnxTensor.createTensor(e, longArrayOf(speaker)).use { sp ->
                OnnxTensor.createTensor(e, floatArrayOf(speed)).use { sd ->
                    s.run(mapOf("tokens" to t, "speaker" to sp, "speed" to sd)).use { r ->
                        val v = r[0].value
                        @Suppress("UNCHECKED_CAST")
                        return when (v) {
                            is FloatArray -> v
                            is Array<*> -> (v as Array<FloatArray>).flatMap { it.asList() }.toFloatArray()
                            else -> throw RuntimeException("unexpected model output")
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val RATE = 22050
        private const val URL_MODEL = "https://github.com/gopalkrishan680-hash/GK/releases/download/indic-tts-hi-v1/indic-hi.onnx"
    }
}
