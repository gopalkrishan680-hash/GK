package com.gk.papercut.ml

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Offline AI models. Downloaded once (Wi-Fi recommended), then everything runs on the phone. */
enum class OfflineModel(val label: String, val fileName: String, val url: String, val approxMb: Int) {
    SAM_ENCODER(
        "MobileSAM encoder", "mobile_sam_image_encoder.onnx",
        "https://huggingface.co/Acly/MobileSAM/resolve/main/mobile_sam_image_encoder.onnx", 28,
    ),
    SAM_DECODER(
        "MobileSAM decoder", "sam_mask_decoder_single.onnx",
        "https://huggingface.co/Acly/MobileSAM/resolve/main/sam_mask_decoder_single.onnx", 17,
    ),
    LAMA(
        "LaMa in-painting", "lama_fp32.onnx",
        "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx", 208,
    ),
}

class ModelManager(context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun file(m: OfflineModel) = File(dir, m.fileName)
    fun isReady(m: OfflineModel) = file(m).let { it.exists() && it.length() > 1_000_000 }
    val samReady get() = isReady(OfflineModel.SAM_ENCODER) && isReady(OfflineModel.SAM_DECODER)
    val lamaReady get() = isReady(OfflineModel.LAMA)

    fun delete(m: OfflineModel) { file(m).delete() }

    /** Blocking download with resume support. [progress] gets 0..1. */
    fun download(m: OfflineModel, progress: (Float) -> Unit) {
        val part = File(dir, m.fileName + ".part")
        var url = URL(m.url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            if (part.exists()) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 8) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            if (code !in 200..299) error("Download failed (HTTP $code)")
            break
        }
        val append = conn.responseCode == 206
        if (!append) part.delete()
        val already = if (append) part.length() else 0L
        val total = conn.contentLengthLong.let { if (it > 0) it + already else m.approxMb * 1_000_000L }
        var done = already
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 500_000) { progress(done.toFloat() / total); last = done }
                }
            }
        }
        part.renameTo(file(m))
        progress(1f)
    }
}
