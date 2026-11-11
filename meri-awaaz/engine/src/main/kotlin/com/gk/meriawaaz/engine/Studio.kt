package com.gk.meriawaaz.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/** Everything the app remembers about the user's voice. */
class VoiceProfile(
    val embedding: FloatArray,
    /** Typical speaking pitch in Hz, 0 if unknown. */
    val f0: Float,
    val speechSeconds: Float,
    val snrDb: Float,
    /** True if the sample has nothing above ~8 kHz: a phone-call or messenger-compressed recording. */
    val narrowband: Boolean = false,
) {
    fun toBytes(): ByteArray {
        val b = ByteBuffer.allocate((embedding.size + FIELDS) * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putFloat(f0).putFloat(speechSeconds).putFloat(snrDb).putFloat(if (narrowband) 1f else 0f)
        embedding.forEach { b.putFloat(it) }
        return b.array()
    }

    companion object {
        private const val FIELDS = 4

        fun fromBytes(bytes: ByteArray): VoiceProfile? {
            if (bytes.size != (VoiceEngine.EMBED_DIM + FIELDS) * 4) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val f0 = b.getFloat()
            val speech = b.getFloat()
            val snr = b.getFloat()
            val narrow = b.getFloat() != 0f
            return VoiceProfile(FloatArray(VoiceEngine.EMBED_DIM) { b.getFloat() }, f0, speech, snr, narrow)
        }
    }
}

class SampleTooShortException : Exception()

enum class Stage { CLEAN, PITCH, ANALYSE, CONVERT, FINISH }

class ConvertOptions(
    val denoise: Boolean = true,
    val pitchMatch: Boolean = true,
    /** Keep the voiceover's natural bass/presence/brightness balance (halfway), see [ToneBalance]. */
    val toneBalance: Boolean = true,
)

class ConvertResult(val finished: Boolean, /** Pitch change that was applied, in semitones. */ val semitones: Float)

/**
 * The full pipeline around [VoiceEngine]. Inputs are headerless 16-bit mono files at
 * [MODEL_RATE] (see [PcmWriter]); intermediate files go to [work].
 */
class Studio(private val engine: VoiceEngine, private val work: File) {

    /**
     * Learns the user's voice from a sample: cleans it first (rumble, background noise,
     * loudness), ignores pauses and untypical stretches, and measures the speaking pitch.
     * Returns null if cancelled.
     */
    fun profile(
        rawSample: File,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): VoiceProfile? {
        val clean = File(work, "sample_clean.raw")
        val report = RawPcmFile(rawSample).use { src ->
            PcmWriter(clean).use { w -> AudioCleaner.clean(src, w::write, true, { progress(it * 0.4f) }, cancelled) }
        } ?: return null
        if (report.speechSeconds < MIN_SPEECH_SECONDS) throw SampleTooShortException()
        return RawPcmFile(clean).use { pcm ->
            val g = engine.embed(pcm, 180) { progress(0.4f + it * 0.5f) }
            val f0 = Pitch.medianF0(pcm)
            val top = ToneBalance.spectrum(pcm)[ToneBalance.BANDS - 1]
            progress(1f)
            VoiceProfile(g, f0, report.speechSeconds, report.snrDb, top < NARROWBAND_DB)
        }
    }

    /** Converts a whole voiceover into the profile's voice and writes a WAV file. */
    fun convert(
        rawVoiceover: File,
        profile: VoiceProfile,
        options: ConvertOptions,
        outWav: File,
        progress: (Stage, Float) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): ConvertResult {
        val stopped = ConvertResult(false, 0f)
        var current = File(work, "voiceover_clean.raw")
        RawPcmFile(rawVoiceover).use { src ->
            PcmWriter(current).use { w ->
                AudioCleaner.clean(src, w::write, options.denoise, { progress(Stage.CLEAN, it) }, cancelled)
            }
        } ?: return stopped

        val reference = if (options.toneBalance) RawPcmFile(current).use { ToneBalance.spectrum(it) } else null

        var semitones = 0f
        if (options.pitchMatch && profile.f0 > 0f) {
            progress(Stage.PITCH, 0f)
            val sourceF0 = RawPcmFile(current).use { Pitch.medianF0(it) }
            if (sourceF0 > 0f) {
                val ratio = (profile.f0 / sourceF0).coerceIn(0.5f, 2f)
                val wanted = (12 * ln(ratio.toDouble()) / ln(2.0)).toFloat()
                if (abs(wanted) >= MIN_SHIFT_SEMITONES) {
                    val shifted = File(work, "voiceover_pitch.raw")
                    val done = RawPcmFile(current).use { src ->
                        PcmWriter(shifted).use { w ->
                            Pitch.shift(src, ratio, w::write, { progress(Stage.PITCH, it) }, cancelled)
                        }
                    }
                    if (!done) return stopped
                    current = shifted
                    semitones = wanted
                }
            }
        }

        val converted = File(work, "converted.raw")
        var peak = 0f
        val finished = RawPcmFile(current).use { src ->
            progress(Stage.ANALYSE, 0f)
            val source = engine.embed(src) { progress(Stage.ANALYSE, it) }
            PcmWriter(converted).use { w ->
                engine.convert(src, source, profile.embedding, { b, off, n ->
                    for (i in off until off + n) peak = maxOf(peak, abs(b[i]))
                    w.write(b, off, n)
                }, { progress(Stage.CONVERT, it) }, cancelled, gate = true)
            }
        }
        if (!finished) return stopped

        progress(Stage.FINISH, 0f)
        var last = converted
        if (reference != null) {
            // The model copies the sample's tone balance, including a thin or harsh recording's
            // faults. Move the result part of the way back to the voiceover's natural balance.
            val balanced = File(work, "balanced.raw")
            RawPcmFile(converted).use { src ->
                val curve = ToneBalance.gains(reference, ToneBalance.spectrum(src), BALANCE_STRENGTH, BALANCE_MAX_DB)
                peak = PcmWriter(balanced).use { w -> ToneBalance.apply(src, curve, w::write) }
            }
            last = balanced
        }

        // Bring the result to a full, consistent level (peak just below clipping).
        val gain = if (peak > 1e-4f) (PEAK_TARGET / peak).coerceAtMost(4f) else 1f
        RawPcmFile(last).use { src ->
            PcmWriter(outWav, MODEL_RATE).use { w ->
                val buf = FloatArray(16384)
                var pos = 0L
                while (pos < src.length) {
                    val n = minOf(buf.size.toLong(), src.length - pos).toInt()
                    src.read(pos, buf, 0, n)
                    for (i in 0 until n) buf[i] *= gain
                    w.write(buf, 0, n)
                    pos += n
                }
            }
        }
        return ConvertResult(true, semitones)
    }

    private companion object {
        const val MIN_SPEECH_SECONDS = 5f
        const val MIN_SHIFT_SEMITONES = 1f
        const val PEAK_TARGET = 0.9f
        const val BALANCE_STRENGTH = 0.5f
        const val BALANCE_MAX_DB = 8f
        const val NARROWBAND_DB = -42f
    }
}
