package com.gopalkrishan.stickmanstudio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Kokoro, a small open AI voice model (Apache-2.0), running on the phone with onnxruntime.
 * The page turns the text into model tokens; this class downloads the model once (about 120 MB),
 * keeps it in the app's own storage and turns tokens + a voice into 24 kHz speech. No internet after
 * the download, no API key.
 */
class KokoroVoice(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "kokoro")
    private val model = File(dir, "kokoro-v1.0.int8.onnx")
    private val voicesFile = File(dir, "voices-v1.0.bin")
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val styles = HashMap<String, FloatArray>()   // voice name -> 510 x 256 style rows

    val ready: Boolean get() = model.length() > 50_000_000 && voicesFile.length() > 20_000_000

    fun sizeOnDisk(): Long = (if (model.exists()) model.length() else 0) + (if (voicesFile.exists()) voicesFile.length() else 0)

    /** Downloads both files; progress(done, total) is called now and then. Throws on failure. */
    fun download(progress: (Long, Long) -> Unit) {
        dir.mkdirs()
        val files = listOf(model to "$BASE/kokoro-v1.0.int8.onnx", voicesFile to "$BASE/voices-v1.0.bin")
        val total = 92_361_271L + 28_214_398L
        var before = 0L
        for ((file, url) in files) {
            if (file.exists() && file.length() > 0 && (file == model && file.length() == 92_361_271L || file == voicesFile && file.length() == 28_214_398L)) {
                before += file.length(); progress(before, total); continue
            }
            val part = File(dir, file.name + ".part")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000
            }
            try {
                if (conn.responseCode != 200) throw RuntimeException("download failed (${conn.responseCode})")
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(1 shl 16); var got = 0L; var last = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (got - last > 1_000_000) { last = got; progress(before + got, total) }
                        }
                    }
                }
            } finally { conn.disconnect() }
            if (!part.renameTo(file)) throw RuntimeException("could not save ${file.name}")
            before += file.length(); progress(before, total)
        }
        close()
    }

    fun delete() { close(); dir.deleteRecursively() }

    private fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null; styles.clear()
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

    /** The voice's style rows from voices-v1.0.bin (a numpy .npz: one float32 array 510 x 1 x 256 per voice). */
    private fun style(voice: String): FloatArray {
        styles[voice]?.let { return it }
        ZipFile(voicesFile).use { zip ->
            val entry = zip.getEntry("$voice.npy") ?: throw RuntimeException("unknown voice $voice")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val major = bytes[6].toInt()
            val headerLen = if (major == 1) (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
            else ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val start = if (major == 1) 10 + headerLen else 12 + headerLen
            val fb = ByteBuffer.wrap(bytes, start, bytes.size - start).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val arr = FloatArray(fb.remaining()); fb.get(arr)
            styles[voice] = arr
            return arr
        }
    }

    /** tokens (without the 0 padding) -> 24 kHz mono samples. */
    fun speak(tokens: LongArray, voice: String, speed: Float): FloatArray {
        val s = open(); val e = env!!
        val st = style(voice); val rows = st.size / 256
        val row = (minOf(tokens.size, rows) - 1).coerceAtLeast(0)
        val styleRow = st.copyOfRange(row * 256, row * 256 + 256)
        val padded = LongArray(tokens.size + 2).also { System.arraycopy(tokens, 0, it, 1, tokens.size) }
        val names = s.inputNames.toList()
        val tokName = names.firstOrNull { it == "tokens" || it == "input_ids" } ?: names[0]
        OnnxTensor.createTensor(e, arrayOf(padded)).use { t ->
            OnnxTensor.createTensor(e, arrayOf(styleRow)).use { sty ->
                OnnxTensor.createTensor(e, floatArrayOf(speed)).use { sp ->
                    s.run(mapOf(tokName to t, "style" to sty, "speed" to sp)).use { r ->
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
        private const val BASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0"

        /** 16-bit mono WAV bytes. */
        fun wav(samples: FloatArray, sr: Int = 24000): ByteArray {
            val out = ByteArrayOutputStream(44 + samples.size * 2)
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(36 + samples.size * 2); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
            b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(sr); b.putInt(sr * 2); b.putShort(2); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(samples.size * 2)
            out.write(b.array())
            val d = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (x in samples) d.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.write(d.array())
            return out.toByteArray()
        }
    }
}
