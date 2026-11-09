package com.gk.speakup.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Real-time voice I/O: Android SpeechRecognizer (live partial transcript while you talk)
 * and TextToSpeech (the tutor's voice). Must be used from the main thread.
 */
class SpeechManager(private val context: Context) {

    var isListening by mutableStateOf(false)
        private set
    var isSpeaking by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)   // mic loudness 0..1 for the animation
        private set

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var pendingSpeak: (() -> Unit)? = null
    private var onSpeakDone: (() -> Unit)? = null
    private var startedAt = 0L

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            pendingSpeak?.invoke()
            pendingSpeak = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { isSpeaking = true }
            }

            override fun onDone(utteranceId: String?) {
                main.post {
                    isSpeaking = false
                    val cb = onSpeakDone
                    onSpeakDone = null
                    cb?.invoke()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { isSpeaking = false; onSpeakDone = null }
            }
        })
    }

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Start listening. [onResult] gets the final transcript and how long the user spoke (seconds).
     */
    fun listen(
        localeTag: String,
        onResult: (text: String, seconds: Long) -> Unit,
        onError: (String) -> Unit,
    ) {
        stopSpeaking()
        recognizer?.destroy()
        if (!recognitionAvailable) {
            onError("Is phone par speech recognition available nahi hai. Google app install/update karein.")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        partial = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                startedAt = System.currentTimeMillis()
            }

            override fun onBeginningOfSpeech() {
                startedAt = System.currentTimeMillis()
            }

            override fun onRmsChanged(rmsdB: Float) {
                level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                level = 0f
            }

            override fun onError(error: Int) {
                isListening = false
                level = 0f
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Kuch sunai nahi diya. Mic button dabakar dobara bolein."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech recognition ke liye internet chahiye."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone permission dein (Settings > Apps > SpeakUp AI)."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic busy hai, ek second baad try karein."
                    else -> "Mic error ($error). Dobara try karein."
                }
                // Keep whatever we heard so far if the recogniser gave up mid-way.
                val heard = partial
                if (heard.isNotBlank() && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                    partial = ""
                    onResult(heard, elapsed())
                } else {
                    onError(msg)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                level = 0f
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?: partial
                partial = ""
                if (text.isBlank()) onError("Kuch sunai nahi diya. Dobara bolein.") else onResult(text, elapsed())
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    if (it.isNotBlank()) partial = it
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Give learners time to think between words before the recogniser stops.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
        }
        r.startListening(intent)
        isListening = true
    }

    private fun elapsed(): Long = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)

    /** Stop and deliver what was heard. */
    fun stopListening() {
        recognizer?.stopListening()
    }

    fun cancelListening() {
        recognizer?.cancel()
        isListening = false
        partial = ""
        level = 0f
    }

    fun speak(text: String, localeTag: String, rate: Float, onDone: (() -> Unit)? = null) {
        val doSpeak = {
            tts.language = Locale.forLanguageTag(localeTag)
            tts.setSpeechRate(rate)
            onSpeakDone = onDone
            isSpeaking = true
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
            Unit
        }
        if (ttsReady) doSpeak() else pendingSpeak = doSpeak
    }

    fun stopSpeaking() {
        onSpeakDone = null
        if (ttsReady) tts.stop()
        isSpeaking = false
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        tts.stop()
        tts.shutdown()
    }
}
