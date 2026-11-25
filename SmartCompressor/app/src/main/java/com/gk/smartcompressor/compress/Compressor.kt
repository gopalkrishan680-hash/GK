package com.gk.smartcompressor.compress

import android.content.Context
import android.net.Uri
import com.gk.smartcompressor.core.Job
import java.io.File

class CompressResult(val file: File, val ext: String, val mime: String)

typealias Progress = (fraction: Float, note: String) -> Unit

interface Compressor {
    suspend fun compress(ctx: Context, job: Job, workDir: File, progress: Progress): CompressResult
}

/** Agar options me target % diya hai to bytes me target size. */
fun Job.targetBytes(): Long? = options.targetReduction?.let { pct -> (size * (100 - pct) / 100.0).toLong() }

fun copyToFile(ctx: Context, uri: Uri, dest: File): File {
    ctx.contentResolver.openInputStream(uri)!!.use { input ->
        dest.outputStream().use { input.copyTo(it, 1 shl 16) }
    }
    return dest
}
