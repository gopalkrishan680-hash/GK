package com.gk.meriawaaz.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class EngineTest {
    private fun sine(freq: Double, rate: Int, n: Int) =
        FloatArray(n) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun resample(x: FloatArray, inRate: Int, outRate: Int, block: Int): FloatArray {
        val out = ArrayList<Float>()
        val r = StreamResampler(inRate, outRate) { b, n -> for (i in 0 until n) out.add(b[i]) }
        var p = 0
        while (p < x.size) {
            val n = minOf(block, x.size - p)
            r.process(x.copyOfRange(p, p + n), n)
            p += n
        }
        r.finish()
        return out.toFloatArray()
    }

    @Test
    fun resamplerKeepsToneAndLength() {
        for ((inRate, block) in listOf(48000 to 4096, 44100 to 1000, 16000 to 77, 8000 to 50000)) {
            val x = sine(1000.0, inRate, inRate * 2)
            val y = resample(x, inRate, MODEL_RATE, block)
            assertEquals(MODEL_RATE * 2, y.size, "length from $inRate Hz")
            val want = sine(1000.0, MODEL_RATE, y.size)
            var worst = 0f
            for (i in 2000 until y.size - 2000) worst = maxOf(worst, abs(y[i] - want[i]))
            assertTrue(worst < 0.01f, "error $worst from $inRate Hz")
        }
    }

    @Test
    fun resamplerRemovesAliasingTones() {
        // 15 kHz cannot exist at 22.05 kHz; it must be filtered out, not folded down.
        val y = resample(sine(15000.0, 48000, 48000), 48000, MODEL_RATE, 4096)
        val worst = y.copyOfRange(500, y.size - 500).maxOf { abs(it) }
        assertTrue(worst < 0.002f, "alias level $worst")
    }

    @Test
    fun resamplerPassesSameRateThrough() {
        val x = sine(440.0, MODEL_RATE, 5000)
        assertTrue(x.contentEquals(resample(x, MODEL_RATE, MODEL_RATE, 999)))
    }

    @Test
    fun chunkerIsSeamlessForAnyLength() {
        val chunker = Chunker(core = 1024, ctx = 256, xf = 64, hop = 64, minInput = 512)
        for (len in listOf(1, 63, 500, 1024, 1279, 1280, 1281, 2048, 5000, 10007)) {
            val x = FloatArray(len) { sin(it * 0.01f) }
            val out = ArrayList<Float>()
            var calls = 0
            val done = chunker.run(
                ArrayPcm(x),
                { input ->
                    calls++
                    assertEquals(0, input.size % 64)
                    assertTrue(input.size >= 512 && input.size <= 1024 + 3 * 256)
                    input.copyOf()
                },
                { b, off, n -> for (i in off until off + n) out.add(b[i]) },
            )
            assertTrue(done)
            assertEquals(len, out.size, "length for $len")
            for (i in x.indices) assertTrue(abs(x[i] - out[i]) < 1e-5f, "sample $i of $len")
            if (len > 3000) assertTrue(calls > 1)
        }
    }

    @Test
    fun chunkerStopsWhenCancelled() {
        val chunker = Chunker(1024, 256, 64, 64, 512)
        var calls = 0
        val done = chunker.run(ArrayPcm(FloatArray(10000)), { calls++; it }, { _, _, _ -> },
            cancelled = { calls >= 2 })
        assertTrue(!done && calls == 2)
    }

    @Test
    fun wavAndRawFilesRoundTrip() {
        val x = sine(300.0, MODEL_RATE, 3000)
        val wav = File.createTempFile("pcm", ".wav")
        PcmWriter(wav, MODEL_RATE).use { it.write(x, 0, 1000); it.write(x, 1000, 2000) }
        val back = readWav(wav)
        assertEquals(3000, back.size)
        for (i in x.indices) assertTrue(abs(x[i] - back[i]) < 1e-4f)

        val raw = File.createTempFile("pcm", ".raw")
        PcmWriter(raw).use { it.write(x, 0, 3000) }
        RawPcmFile(raw).use { f ->
            assertEquals(3000L, f.length)
            val part = FloatArray(10)
            f.read(1500, part, 0, 10)
            for (i in 0 until 10) assertTrue(abs(x[1500 + i] - part[i]) < 1e-4f)
        }
    }

    @Test
    fun silenceIsRemoved() {
        val x = FloatArray(MODEL_RATE * 3)
        sine(200.0, MODEL_RATE, MODEL_RATE).copyInto(x, MODEL_RATE)
        val y = VoiceEngine.removeSilence(x)
        assertTrue(y.size in MODEL_RATE..MODEL_RATE + 10 * 512, "kept ${y.size}")
    }

    /** A buzzy, voice-like tone: fundamental plus harmonics. */
    private fun voice(f0: Double, n: Int, amp: Double = 0.2) = FloatArray(n) { i ->
        var v = 0.0
        for (k in 1..10) v += sin(2 * PI * f0 * k * i / MODEL_RATE) / k
        (amp * v).toFloat()
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += x[i] * x[i]
        return sqrt(e / (to - from))
    }

    private fun collect(run: ((FloatArray, Int, Int) -> Unit) -> Unit): FloatArray {
        val out = ArrayList<Float>()
        run { b, off, n -> for (i in off until off + n) out.add(b[i]) }
        return out.toFloatArray()
    }

    @Test
    fun fftFindsToneAndInverts() {
        val n = 1024
        val re = FloatArray(n) { sin(2 * PI * 37 * it / n).toFloat() }
        val im = FloatArray(n)
        val orig = re.copyOf()
        val fft = Fft(n)
        fft.transform(re, im, false)
        val mag = FloatArray(n / 2) { sqrt((re[it] * re[it] + im[it] * im[it]).toDouble()).toFloat() }
        assertEquals(37, mag.indices.maxByOrNull { mag[it] })
        assertTrue(abs(mag[37] - n / 2f) < 0.5f)
        fft.transform(re, im, true)
        for (i in 0 until n) assertTrue(abs(re[i] - orig[i]) < 1e-4f)
    }

    /** Half-second bursts of "speech" with half-second pauses. */
    private fun bursts(seconds: Int, noise: Double, rumble: Double): FloatArray {
        val rnd = java.util.Random(1)
        val v = voice(140.0, MODEL_RATE * seconds)
        return FloatArray(v.size) { i ->
            val on = (i / (MODEL_RATE / 2)) % 2 == 0
            ((if (on) v[i] else 0f) + noise * rnd.nextGaussian() + rumble * sin(2 * PI * 30 * i / MODEL_RATE)).toFloat()
        }
    }

    @Test
    fun cleanerRemovesNoiseAndRumbleButKeepsSpeech() {
        val x = bursts(8, noise = 0.01, rumble = 0.05)
        var report: CleanReport? = null
        val y = collect { sink -> report = AudioCleaner.clean(ArrayPcm(x), sink) }
        assertEquals(x.size, y.size)
        assertTrue(report!!.denoised)
        assertTrue(report!!.speechSeconds in 3.5f..5.5f, "speech ${report!!.speechSeconds}")
        val half = MODEL_RATE / 2
        // Speech-to-background ratio, measured in the 3rd burst and the pause after it.
        fun ratioDb(a: FloatArray) =
            20 * log10(rms(a, 4 * half + 2000, 5 * half - 2000) / rms(a, 5 * half + 2000, 6 * half - 2000))
        val before = ratioDb(x)
        val after = ratioDb(y)
        println("cleaner: speech/background $before dB -> $after dB, snr report ${report!!.snrDb}")
        assertTrue(after > before + 15, "only improved from $before to $after dB")
        // Loudness ends up near the standard level, and nothing clips.
        assertTrue(rms(y, 4 * half + 2000, 5 * half - 2000) in 0.06..0.2)
        assertTrue(y.all { abs(it) <= 1f })
    }

    @Test
    fun cleanerLeavesCleanSpeechAlone() {
        val x = bursts(6, noise = 0.0, rumble = 0.0)
        val y = collect { sink -> AudioCleaner.clean(ArrayPcm(x), sink) }
        var xy = 0.0
        var xx = 0.0
        var yy = 0.0
        for (i in x.indices) {
            xy += x[i] * y[i]; xx += x[i] * x[i]; yy += y[i] * y[i]
        }
        val corr = xy / sqrt(xx * yy)
        assertTrue(corr > 0.99, "correlation $corr")
    }

    @Test
    fun pitchIsMeasuredAndShifted() {
        for (f in listOf(95.0, 140.0, 230.0)) {
            val got = Pitch.medianF0(ArrayPcm(voice(f, MODEL_RATE * 2)))
            assertTrue(abs(got - f) < f * 0.03, "measured $got for $f Hz")
        }
        assertEquals(0f, Pitch.medianF0(ArrayPcm(FloatArray(MODEL_RATE))))
        val x = voice(120.0, MODEL_RATE * 3)
        for (ratio in listOf(1.5f, 0.7f)) {
            val y = collect { sink -> assertTrue(Pitch.shift(ArrayPcm(x), ratio, sink)) }
            assertEquals(x.size, y.size)
            val got = Pitch.medianF0(ArrayPcm(y))
            assertTrue(abs(got - 120 * ratio) < 120 * ratio * 0.04, "shifted to $got, wanted ${120 * ratio}")
            val level = rms(y, MODEL_RATE, 2 * MODEL_RATE) / rms(x, MODEL_RATE, 2 * MODEL_RATE)
            assertTrue(level in 0.8..1.25, "level changed by $level")
        }
    }

    @Test
    fun gateQuietensOnlyThePauses() {
        val input = FloatArray(MODEL_RATE * 2)
        voice(150.0, MODEL_RATE).copyInto(input, 0)
        val out = FloatArray(input.size) { 0.5f }
        VoiceEngine.applyGate(input, out)
        assertTrue(out.take(MODEL_RATE).all { it == 0.5f })
        assertTrue(out[MODEL_RATE + 1000] == 0.5f) // hold time just after speech
        assertTrue(abs(out[out.size - 1] - 0.05f) < 0.001f)
    }

    @Test
    fun voiceProfileSurvivesStorage() {
        val p = VoiceProfile(FloatArray(VoiceEngine.EMBED_DIM) { it * 0.5f }, 123.5f, 42f, 31f)
        val q = VoiceProfile.fromBytes(p.toBytes())!!
        assertTrue(p.embedding.contentEquals(q.embedding))
        assertEquals(123.5f, q.f0)
        assertEquals(42f, q.speechSeconds)
        assertEquals(null, VoiceProfile.fromBytes(ByteArray(1024)))
    }

    /** Needs MERI_AWAAZ_MODELS=<dir with tone_encoder.onnx and converter.onnx>. */
    @Test
    fun studioConvertsVoiceover() {
        val dir = System.getenv("MERI_AWAAZ_MODELS")
        assumeTrue(dir != null, "MERI_AWAAZ_MODELS not set")
        val work = java.nio.file.Files.createTempDirectory("meri_awaaz").toFile()
        VoiceEngine("$dir/tone_encoder.onnx", "$dir/converter.onnx", 4).use { engine ->
            val wavs = System.getenv("MERI_AWAAZ_WAVS")
            // Without recordings: 13 s of bursts (three model chunks) and a higher-pitched "sample".
            val src = if (wavs != null) readWav(File(wavs, "source.wav")) else bursts(13, 0.005, 0.0)
            val tgt = if (wavs != null) readWav(File(wavs, "target.wav")) else
                voice(210.0, MODEL_RATE * 12).also { for (i in it.indices) if ((i / 20000) % 4 == 3) it[i] = 0f }
            val rawSrc = File(work, "src.raw").also { f -> PcmWriter(f).use { it.write(src, 0, src.size) } }
            val rawTgt = File(work, "tgt.raw").also { f -> PcmWriter(f).use { it.write(tgt, 0, tgt.size) } }
            val studio = Studio(engine, work)

            val profile = studio.profile(rawTgt)!!
            println("profile: f0=${profile.f0} Hz speech=${profile.speechSeconds}s snr=${profile.snrDb} dB")
            assertEquals(VoiceEngine.EMBED_DIM, profile.embedding.size)
            assertTrue(profile.f0 > 0f)

            val stages = HashSet<Stage>()
            val outFile = File(work, "out.wav")
            val result = studio.convert(rawSrc, profile, ConvertOptions(), outFile, { s, _ -> stages.add(s) })
            assertTrue(result.finished)
            assertTrue(stages.containsAll(listOf(Stage.CLEAN, Stage.ANALYSE, Stage.CONVERT, Stage.FINISH)))
            val out = readWav(outFile)
            assertEquals(src.size, out.size)
            assertTrue(out.all { it.isFinite() })
            val srcF0 = Pitch.medianF0(ArrayPcm(src))
            println("pitch: source=$srcF0 Hz target=${profile.f0} Hz shift=${result.semitones} semitones")

            val plainFile = File(work, "plain.wav")
            assertTrue(studio.convert(rawSrc, profile, ConvertOptions(denoise = false, pitchMatch = false), plainFile).finished)
            assertEquals(src.size, readWav(plainFile).size)

            if (wavs != null) {
                assertTrue(abs(out.maxOf { abs(it) } - 0.9f) < 0.02f, "output level")
                val outF0 = Pitch.medianF0(ArrayPcm(out))
                val gSrc = engine.embed(ArrayPcm(src))
                val before = VoiceEngine.cosine(gSrc, profile.embedding)
                val plain = VoiceEngine.cosine(engine.embed(ArrayPcm(readWav(plainFile))), profile.embedding)
                val full = VoiceEngine.cosine(engine.embed(ArrayPcm(out)), profile.embedding)
                println("output pitch: $outF0 Hz")
                println("similarity to target voice: source=$before converted=$plain converted+pitch-match=$full")
                assertTrue(abs(outF0 - profile.f0) < profile.f0 * 0.2f, "pitch $outF0 vs ${profile.f0}")
                assertTrue(plain > before, "conversion did not move the voice towards the target")
                assertTrue(full > before, "full pipeline did not move the voice towards the target")
            }
        }
        work.deleteRecursively()
    }

    private fun readWav(file: File): FloatArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        while (pos + 8 <= b.limit()) {
            val id = String(b.array(), pos, 4)
            val size = b.getInt(pos + 4)
            if (id == "fmt ") {
                channels = b.getShort(pos + 10).toInt()
                check(b.getShort(pos + 8).toInt() == 1 && b.getShort(pos + 22).toInt() == 16) { "need 16-bit PCM" }
                check(b.getInt(pos + 12) == MODEL_RATE) { "need $MODEL_RATE Hz" }
            } else if (id == "data") {
                val n = minOf(size, b.limit() - pos - 8) / 2 / channels
                return FloatArray(n) { b.getShort(pos + 8 + it * 2 * channels) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $file")
    }
}
