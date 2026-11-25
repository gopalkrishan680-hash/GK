package com.gk.smartcompressor.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.gk.smartcompressor.compress.AudioCompressor
import com.gk.smartcompressor.compress.ImageCompressor
import com.gk.smartcompressor.compress.PdfCompressor
import com.gk.smartcompressor.compress.VideoCompressor
import com.gk.smartcompressor.compress.ZipCompressor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.Job as CoJob

/** Files ki queue – ek-ek karke compress karta hai (phone garam/hang na ho). */
class JobManager(private val ctx: Context, private val history: HistoryStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private var nextId = System.currentTimeMillis()
    @Volatile private var currentId = -1L
    private var current: CoJob? = null

    init {
        scope.launch { for (id in queue) runJob(id) }
    }

    fun enqueue(files: List<Pair<Uri, SourceInfo>>, options: Options) {
        if (files.isEmpty()) return
        val newJobs = files.map { (uri, info) ->
            Job(nextId++, uri, info.name, info.size, info.kind, info.mime, options)
        }
        _jobs.update { it + newJobs }
        newJobs.forEach { queue.trySend(it.id) }
        ContextCompat.startForegroundService(ctx, Intent(ctx, CompressionService::class.java))
    }

    fun cancel(id: Long) {
        if (id == currentId) current?.cancel()
        else setState(id, JobState.Cancelled)
    }

    fun cancelAll() {
        _jobs.value.filter { it.state == JobState.Queued }.forEach { setState(it.id, JobState.Cancelled) }
        current?.cancel()
    }

    fun clearFinished() = _jobs.update { list ->
        list.filter { it.state is JobState.Queued || it.state is JobState.Running }
    }

    fun isBusy() = _jobs.value.any { it.state is JobState.Queued || it.state is JobState.Running }

    private fun setState(id: Long, s: JobState) =
        _jobs.update { list -> list.map { if (it.id == id) it.copy(state = s) else it } }

    private suspend fun runJob(id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.state != JobState.Queued) return
        currentId = id
        setState(id, JobState.Running(0f, "Shuru ho raha hai…"))
        val workDir = File(ctx.cacheDir, "work").apply { mkdirs() }
        val wake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartCompressor:job")
        wake.acquire(3 * 60 * 60 * 1000L)
        val c = scope.launch {
            var outFile: File? = null
            try {
                val compressor = when (job.kind) {
                    FileKind.VIDEO -> VideoCompressor
                    FileKind.IMAGE -> ImageCompressor
                    FileKind.PDF -> PdfCompressor
                    FileKind.AUDIO -> AudioCompressor
                    FileKind.OTHER -> ZipCompressor
                }
                var lastP = -1f
                val res = compressor.compress(ctx, job, workDir) { f, note ->
                    val p = f.coerceIn(0f, 1f)
                    if (p - lastP >= 0.005f || p >= 1f) {
                        lastP = p
                        setState(id, JobState.Running(p, note))
                    }
                }
                outFile = res.file
                val outSize = res.file.length()
                if (outSize <= 0) error("Output khaali hai")
                if (outSize >= job.size * 0.97) {
                    // Pehle se compressed file – bada output save karne ka koi fayda nahi
                    setState(id, JobState.AlreadySmall(outSize))
                } else {
                    val name = OutputStore.outName(job.name, res.ext)
                    val uri = withContext(Dispatchers.IO) { OutputStore.save(ctx, res.file, name, res.mime) }
                    setState(id, JobState.Done(uri, name, outSize, res.mime))
                    history.add(HistoryItem(name, job.kind, job.size, outSize, uri.toString(), res.mime, System.currentTimeMillis()))
                }
            } catch (e: CancellationException) {
                setState(id, JobState.Cancelled)
            } catch (e: Throwable) {
                val msg = when (e) {
                    is OutOfMemoryError -> "Memory kam pad gayi – 'Max Saving' try karein"
                    else -> e.message ?: e.javaClass.simpleName
                }
                setState(id, JobState.Failed(msg))
            } finally {
                outFile?.delete()
                workDir.listFiles()?.forEach { if (it.name.contains("_${job.id}")) it.delete() }
            }
        }
        current = c
        c.join()
        current = null
        currentId = -1L
        if (wake.isHeld) wake.release()
    }
}
