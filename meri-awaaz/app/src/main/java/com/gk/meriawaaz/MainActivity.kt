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
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.gk.meriawaaz.engine.ConvertOptions
import com.gk.meriawaaz.engine.MODEL_RATE
import com.gk.meriawaaz.engine.SampleTooShortException
import com.gk.meriawaaz.engine.Stage
import com.gk.meriawaaz.engine.Studio
import com.gk.meriawaaz.engine.VoiceEngine
import com.gk.meriawaaz.engine.VoiceProfile
import java.io.File
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
    private lateinit var sampleQuality: TextView
    private lateinit var denoise: CheckBox
    private lateinit var pitchMatch: CheckBox
    private lateinit var toneBalance: CheckBox
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

    private val voiceFile get() = File(filesDir, "my_voice.profile")
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
        sampleQuality = findViewById(R.id.sampleQuality)
        denoise = findViewById(R.id.denoise)
        pitchMatch = findViewById(R.id.pitchMatch)
        toneBalance = findViewById(R.id.toneBalance)
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
        val saved = if (voiceFile.exists()) VoiceProfile.fromBytes(voiceFile.readBytes()) else null
        val savedVoice = if (saved != null) prefs.getString(KEY_VOICE_NAME, "sample") else null
        sampleQuality.text = if (saved != null && sampleUri == null) describe(saved) else ""
        sampleQuality.visibility = if (sampleQuality.text.isEmpty()) View.GONE else View.VISIBLE
        sampleName.text = when {
            sampleUri != null -> sampleLabel
            savedVoice != null -> getString(R.string.saved_voice, savedVoice)
            else -> getString(R.string.none_chosen)
        }
        pickers.forEach { it.isEnabled = !running }
        denoise.isEnabled = !running
        pitchMatch.isEnabled = !running
        toneBalance.isEnabled = !running
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
        val options = ConvertOptions(denoise.isChecked, pitchMatch.isChecked, toneBalance.isChecked)
        stopPlayer()
        cancel = false
        resultBox.visibility = View.GONE
        worker = Thread {
            val message = try {
                runJob(voiceover, sample, label, options)
            } catch (e: NoAudioException) {
                getString(R.string.err_no_audio)
            } catch (e: SampleTooShortException) {
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
    private fun runJob(voiceover: Uri, sample: Uri?, sampleLabel: String?, options: ConvertOptions): String {
        val cancelled = { cancel }
        val work = File(cacheDir, "work").apply { deleteRecursively(); mkdirs() }
        val outDir = File(filesDir, "output").apply { deleteRecursively(); mkdirs() }
        output = null

        stage(getString(R.string.st_models), null)
        val (encoderPath, converterPath) = Models.prepare(this)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        VoiceEngine(encoderPath, converterPath, threads).use { engine ->
            val studio = Studio(engine, work)
            val profile: VoiceProfile
            if (sample != null) {
                val text = getString(R.string.st_sample)
                stage(text, 0f)
                val raw = File(work, "sample.raw")
                AudioDecoder.decode(this, sample, raw, SAMPLE_MAX_SECONDS, { stage(text, it * 0.3f) }, cancelled)
                if (cancel) return getString(R.string.st_cancelled)
                profile = studio.profile(raw, { stage(text, 0.3f + it * 0.7f) }, cancelled)
                    ?: return getString(R.string.st_cancelled)
                voiceFile.writeBytes(profile.toBytes())
                prefs.edit().putString(KEY_VOICE_NAME, sampleLabel ?: "sample").apply()
                runOnUiThread {
                    sampleUri = null
                    refresh()
                }
            } else {
                profile = VoiceProfile.fromBytes(voiceFile.readBytes()) ?: throw SampleTooShortException()
            }

            val decodeText = getString(R.string.st_decode)
            stage(decodeText, 0f)
            val raw = File(work, "voiceover.raw")
            val samples = AudioDecoder.decode(this, voiceover, raw, 0, { stage(decodeText, it) }, cancelled)
            if (cancel) return getString(R.string.st_cancelled)
            if (samples < MODEL_RATE / 2) throw NoAudioException()

            val name = "meri_awaaz_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
            val out = File(outDir, name)
            val began = SystemClock.elapsedRealtime()
            val result = studio.convert(raw, profile, options, out, { s, f ->
                when (s) {
                    Stage.CLEAN -> stage(getString(R.string.st_clean), f)
                    Stage.PITCH -> stage(getString(R.string.st_pitch), f)
                    Stage.ANALYSE -> stage(getString(R.string.st_analyse), f)
                    Stage.CONVERT -> stage(getString(R.string.st_convert, (f * 100).toInt()), f)
                    Stage.FINISH -> stage(getString(R.string.st_finish), null)
                }
            }, cancelled)
            if (!result.finished) {
                out.delete()
                return getString(R.string.st_cancelled)
            }
            output = out
            val took = (SystemClock.elapsedRealtime() - began) / 1000
            var message = getString(R.string.st_done, clock(samples / MODEL_RATE), clock(took))
            if (result.semitones != 0f) {
                message += "\n" + getString(R.string.st_pitch_done, String.format(Locale.US, "%+.1f", result.semitones))
            }
            return message
        }
    }

    private fun clock(seconds: Long) = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    /** One line telling the user how good the stored voice sample is. */
    private fun describe(p: VoiceProfile): String {
        val noise = getString(
            when {
                p.snrDb >= 35 -> R.string.q_noise_low
                p.snrDb >= 25 -> R.string.q_noise_mid
                else -> R.string.q_noise_high
            }
        )
        var text = getString(R.string.q_summary, p.speechSeconds.toInt(), noise)
        if (p.f0 > 0) text += " · " + getString(R.string.q_pitch, p.f0.toInt())
        if (p.speechSeconds < 30) text += "\n" + getString(R.string.q_tip_longer)
        if (p.snrDb < 25) text += "\n" + getString(R.string.q_tip_quiet)
        if (p.narrowband) text += "\n" + getString(R.string.q_tip_band)
        return text
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

    private companion object {
        const val REQ_VOICEOVER = 1
        const val REQ_SAMPLE = 2
        const val REQ_SAVE = 3
        const val KEY_VOICE_NAME = "voice_name"
        const val SAMPLE_MAX_SECONDS = 180
    }
}
