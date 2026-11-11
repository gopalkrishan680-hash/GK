package com.gk.meriawaaz

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.gk.meriawaaz.engine.MODEL_RATE
import com.gk.meriawaaz.engine.PcmWriter
import com.gk.meriawaaz.engine.RawPcmFile
import com.gk.meriawaaz.engine.VoiceEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var voiceoverName: TextView
    private lateinit var sampleName: TextView
    private lateinit var convert: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var resultBox: View
    private lateinit var play: Button
    private lateinit var pickers: List<Button>

    private var voiceoverUri: Uri? = null
    private var sampleUri: Uri? = null
    private var sampleLabel: String? = null
    @Volatile
    private var output: File? = null
    private var player: MediaPlayer? = null
    private var worker: Thread? = null

    @Volatile
    private var cancel = false

    private val voiceFile get() = File(filesDir, "my_voice.f32")
    private val prefs get() = getSharedPreferences("app", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        voiceoverName = findViewById(R.id.voiceoverName)
        sampleName = findViewById(R.id.sampleName)
        convert = findViewById(R.id.convert)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        resultBox = findViewById(R.id.resultBox)
        play = findViewById(R.id.play)
        val pickVoiceover = findViewById<Button>(R.id.pickVoiceover)
        val pickSample = findViewById<Button>(R.id.pickSample)
        pickers = listOf(pickVoiceover, pickSample)

        pickVoiceover.setOnClickListener { pickAudio(REQ_VOICEOVER) }
        pickSample.setOnClickListener { pickAudio(REQ_SAMPLE) }
        convert.setOnClickListener { if (worker == null) start() else cancel = true }
        play.setOnClickListener { togglePlay() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.share).setOnClickListener { share() }
        refresh()
    }

    override fun onDestroy() {
        cancel = true
        stopPlayer()
        super.onDestroy()
    }

    private fun pickAudio(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*", "application/ogg"))
        startActivityForResult(intent, request)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_VOICEOVER -> voiceoverUri = uri.also { voiceoverName.text = displayName(it) }
            REQ_SAMPLE -> {
                sampleUri = uri
                sampleLabel = displayName(uri)
            }
            REQ_SAVE -> saveTo(uri)
        }
        refresh()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "audio"
    }

    /** Syncs labels and buttons with the current state. */
    private fun refresh() {
        val running = worker != null
        val savedVoice = if (voiceFile.exists()) prefs.getString(KEY_VOICE_NAME, null) else null
        sampleName.text = when {
            sampleUri != null -> sampleLabel
            savedVoice != null -> getString(R.string.saved_voice, savedVoice)
            else -> getString(R.string.none_chosen)
        }
        pickers.forEach { it.isEnabled = !running }
        convert.isEnabled = running || (voiceoverUri != null && (sampleUri != null || savedVoice != null))
        convert.setText(if (running) R.string.cancel else R.string.convert)
        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun start() {
        val voiceover = voiceoverUri ?: return
        val sample = sampleUri
        val label = sampleLabel
        stopPlayer()
        cancel = false
        resultBox.visibility = View.GONE
        worker = Thread {
            val message = try {
                runJob(voiceover, sample, label)
            } catch (e: NoAudioException) {
                getString(R.string.err_no_audio)
            } catch (e: ShortSampleException) {
                getString(R.string.err_short_sample)
            } catch (e: OutOfMemoryError) {
                getString(R.string.err_memory)
            } catch (e: Throwable) {
                getString(R.string.err_generic, e.message ?: e.javaClass.simpleName)
            }
            File(cacheDir, "work").deleteRecursively()
            runOnUiThread {
                worker = null
                status.text = message
                resultBox.visibility = if (output?.exists() == true) View.VISIBLE else View.GONE
                refresh()
            }
        }.also { it.start() }
        refresh()
    }

    private var lastStage = 0L
    private var lastStageText = ""

    /** Worker-thread progress report; throttled so decoders can call it per buffer. */
    private fun stage(text: String, fraction: Float?) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastStageText && now - lastStage < 100) return
        lastStage = now
        lastStageText = text
        runOnUiThread {
            if (worker == null) return@runOnUiThread
            status.text = text
            progress.isIndeterminate = fraction == null
            if (fraction != null) progress.progress = (fraction * 1000).toInt()
        }
    }

    /** Runs on the worker thread; returns the final status message. */
    private fun runJob(voiceover: Uri, sample: Uri?, sampleLabel: String?): String {
        val cancelled = { cancel }
        val work = File(cacheDir, "work").apply { deleteRecursively(); mkdirs() }
        val outDir = File(filesDir, "output").apply { deleteRecursively(); mkdirs() }
        output = null

        stage(getString(R.string.st_models), null)
        val (encoderPath, converterPath) = Models.prepare(this)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        VoiceEngine(encoderPath, converterPath, threads).use { engine ->
            val target: FloatArray
            if (sample != null) {
                val text = getString(R.string.st_sample)
                stage(text, 0f)
                val raw = File(work, "sample.raw")
                AudioDecoder.decode(this, sample, raw, SAMPLE_MAX_SECONDS, { stage(text, it * 0.5f) }, cancelled)
                if (cancel) return getString(R.string.st_cancelled)
                target = RawPcmFile(raw).use { pcm ->
                    if (pcm.length < SAMPLE_MIN_SECONDS * MODEL_RATE) throw ShortSampleException()
                    engine.embed(pcm, SAMPLE_MAX_SECONDS) { stage(text, 0.5f + it * 0.5f) }
                }
                saveVoice(target, sampleLabel ?: "sample")
                runOnUiThread { sampleUri = null }
            } else {
                target = loadVoice()
            }

            val decodeText = getString(R.string.st_decode)
            stage(decodeText, 0f)
            val raw = File(work, "voiceover.raw")
            AudioDecoder.decode(this, voiceover, raw, 0, { stage(decodeText, it) }, cancelled)
            if (cancel) return getString(R.string.st_cancelled)

            RawPcmFile(raw).use { pcm ->
                if (pcm.length < MODEL_RATE / 2) throw NoAudioException()
                stage(getString(R.string.st_analyse), null)
                val source = engine.embed(pcm)
                val name = "meri_awaaz_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
                val out = File(outDir, name)
                val began = SystemClock.elapsedRealtime()
                val finished = PcmWriter(out, MODEL_RATE).use { writer ->
                    engine.convert(pcm, source, target, writer::write,
                        { stage(getString(R.string.st_convert, (it * 100).toInt()), it) }, cancelled)
                }
                if (!finished) {
                    out.delete()
                    return getString(R.string.st_cancelled)
                }
                output = out
                val took = (SystemClock.elapsedRealtime() - began) / 1000
                return getString(R.string.st_done, clock(pcm.length / MODEL_RATE), clock(took))
            }
        }
    }

    private fun clock(seconds: Long) = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    private fun saveVoice(g: FloatArray, label: String) {
        val bytes = ByteBuffer.allocate(g.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(g)
        voiceFile.writeBytes(bytes.array())
        prefs.edit().putString(KEY_VOICE_NAME, label).apply()
    }

    private fun loadVoice(): FloatArray {
        val fb = ByteBuffer.wrap(voiceFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun togglePlay() {
        if (player != null) return stopPlayer()
        val file = output ?: return
        try {
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { stopPlayer() }
                prepare()
                start()
            }
            play.setText(R.string.stop)
        } catch (e: Exception) {
            stopPlayer()
            status.text = getString(R.string.err_generic, e.message ?: "playback")
        }
    }

    private fun stopPlayer() {
        player?.release()
        player = null
        play.setText(R.string.play)
    }

    private fun save() {
        val file = output ?: return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/x-wav")
            .putExtra(Intent.EXTRA_TITLE, file.name)
        startActivityForResult(intent, REQ_SAVE)
    }

    private fun saveTo(uri: Uri) {
        val file = output ?: return
        try {
            contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            status.text = getString(R.string.st_saved)
        } catch (e: Exception) {
            status.text = getString(R.string.err_generic, e.message ?: "save")
        }
    }

    private fun share() {
        val file = output ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/x-wav")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private class ShortSampleException : Exception()

    private companion object {
        const val REQ_VOICEOVER = 1
        const val REQ_SAMPLE = 2
        const val REQ_SAVE = 3
        const val KEY_VOICE_NAME = "voice_name"
        const val SAMPLE_MIN_SECONDS = 10
        const val SAMPLE_MAX_SECONDS = 180
    }
}
