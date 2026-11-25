package com.gk.smartcompressor.compress

import android.content.Context
import com.gk.smartcompressor.core.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Baaki sab files (docs, txt, apk, etc.) ke liye lossless ZIP (maximum compression). */
object ZipCompressor : Compressor {
    override suspend fun compress(ctx: Context, job: Job, workDir: File, progress: Progress): CompressResult =
        withContext(Dispatchers.IO) {
            val out = File(workDir, "out_${job.id}.zip")
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                zos.putNextEntry(ZipEntry(job.name))
                ctx.contentResolver.openInputStream(job.uri)!!.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zos.write(buf, 0, n)
                        done += n
                        if (job.size > 0) progress(done.toFloat() / job.size, "ZIP (lossless)")
                        coroutineContext.ensureActive()
                    }
                }
                zos.closeEntry()
            }
            CompressResult(out, "zip", "application/zip")
        }
}
