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
            println("pitch shift x$ratio: level x$level")
            assertTrue(level in 0.6..1.6, "level changed by $level")
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
        val p = VoiceProfile(FloatArray(VoiceEngine.EMBED_DIM) { it * 0.5f }, 123.5f, 42f, 31f, true)
        val q = VoiceProfile.fromBytes(p.toBytes())!!
        assertTrue(p.embedding.contentEquals(q.embedding))
        assertEquals(123.5f, q.f0)
        assertEquals(42f, q.speechSeconds)
        assertTrue(q.narrowband)
        assertEquals(null, VoiceProfile.fromBytes(ByteArray(1024)))
    }

    @Test
    fun hinglishBecomesDevanagari() {
        val cases = listOf(
            "aaj hum baat karenge" to "आज हम बात करेंगे",
            "Namaste doston, mera naam Amit hai." to "नमस्ते दोस्तों, मेरा नाम अमित है.",
            "kya aap jaante hain?" to "क्या आप जानते हैं?",
            "yeh video bahut accha hai" to "यह वीडियो बहुत अच्छा है",
            "mujhe 25 rupaye chahiye" to "मुझे पच्चीस रुपये चाहिए",
            "2024 me 150 log" to "दो हज़ार चौबीस में एक सौ पचास लोग",
            "पहले से हिंदी hai" to "पहले से हिंदी है",
            "AI aur TV" to "एआई और टीवी",
        )
        for ((roman, want) in cases) assertEquals(want, Hinglish.toDevanagari(roman))
        // Words the dictionary does not know go through the spelling rules.
        val rules = listOf(
            "pradesh" to "प्रदेश", "bandar" to "बंदर", "kitabon" to "किताबों", "dilli" to "दिल्ली",
            "vidya" to "विद्या", "mast" to "मस्त", "dabana" to "दबाना", "sikhao" to "सिखाओ", "ghumaya" to "घुमाया", "apne" to "अपने", "kitne" to "कितने", "sharaabi" to "शराबी", "lambai" to "लंबै",
        )
        for ((roman, want) in rules) println("rule: $roman -> ${Hinglish.toDevanagari(roman)} (want $want)")
        for ((roman, want) in rules.dropLast(1)) assertEquals(want, Hinglish.toDevanagari(roman))
        println(Hinglish.toDevanagari("Ek gaon me ek ladka rehta tha. Uska sapna tha ki wo ek din bada aadmi banega, lekin uske paas paise nahi the. Phir bhi usne himmat nahi haari aur mehnat karta raha!"))
        println(Hinglish.toDevanagari("Is video ko like karo, channel ko subscribe karo aur bell icon dabana mat bhoolna. Milte hain agle video me, tab tak ke liye dhanyavaad."))
    }

    @Test
    fun textIsCutIntoPhrasesWithPauses() {
        val parts = HindiTts.segments("नमस्ते दोस्तों, आज हम बात करेंगे। क्या आप तैयार हैं?\n\nचलिए शुरू करते हैं")
        assertEquals(listOf("नमस्ते दोस्तों", "आज हम बात करेंगे", "क्या आप तैयार हैं", "चलिए शुरू करते हैं"), parts.map { it.text })
        assertEquals(listOf(180, 420, 700, 420), parts.map { it.pauseMs })
        assertTrue(HindiTts.segments("  ... !!! ").isEmpty())
        val long = HindiTts.segments("शब्द ".repeat(200))
        assertTrue(long.size > 3 && long.all { it.text.length <= 190 })
    }

    /** Needs MERI_AWAAZ_MODELS with the text-to-speech and voice models. */
    @Test
    fun textBecomesSpeechInTheTargetVoice() {
        val dir = System.getenv("MERI_AWAAZ_MODELS")
        assumeTrue(dir != null && File(dir, "tts_hi.onnx").exists(), "text-to-speech model not available")
        val wavs = System.getenv("MERI_AWAAZ_WAVS")
        val work = java.nio.file.Files.createTempDirectory("meri_awaaz_tts").toFile()
        val script = "Namaste doston, aaj hum ek nayi kahani sunenge. Kya aap taiyar hain? Chaliye shuru karte hain!"
        val hindi = Hinglish.toDevanagari(script)
        println("script: $hindi")
        HindiTts("$dir/tts_hi.onnx", "$dir/tts_hi_tokens.txt", 4).use { tts ->
            val one = tts.synthesize("नमस्ते दोस्तों", 1f)
            val slow = tts.synthesize("नमस्ते दोस्तों", 0.7f)
            println("phrase: ${one.size / 16000f}s, slower: ${slow.size / 16000f}s, peak ${one.maxOf { abs(it) }}")
            assertTrue(one.size in 8000..48000, "implausible length ${one.size}")
            assertTrue(slow.size > one.size * 1.15f)
            assertTrue(tts.synthesize("?? !!", 1f).isEmpty())
            val plain = collect { sink -> assertTrue(tts.speak(hindi, 1f, sink)) }
            println("spoken: ${plain.size / MODEL_RATE.toFloat()}s, pitch ${Pitch.medianF0(ArrayPcm(plain))} Hz")
            assertTrue(plain.size in MODEL_RATE * 4..MODEL_RATE * 20)
            assertTrue(Pitch.medianF0(ArrayPcm(plain)) > 0f, "no voiced speech")

            VoiceEngine("$dir/tone_encoder.onnx", "$dir/converter.onnx", 4).use { engine ->
                val studio = Studio(engine, work)
                val tgt = if (wavs != null) readWav(File(wavs, "target.wav")) else voice(210.0, MODEL_RATE * 12)
                val rawTgt = File(work, "tgt.raw").also { f -> PcmWriter(f).use { it.write(tgt, 0, tgt.size) } }
                val profile = studio.profile(rawTgt)!!
                val outFile = File(work, "tts.wav")
                val stages = HashSet<Stage>()
                assertTrue(studio.speak(tts, hindi, 1f, profile, ConvertOptions(denoise = false), outFile, { s, _ -> stages.add(s) }).finished)
                assertTrue(Stage.SPEAK in stages && Stage.CONVERT in stages)
                val out = readWav(outFile)
                assertTrue(abs(out.size - plain.size) < MODEL_RATE * 3, "length ${out.size} vs ${plain.size}")
                val before = VoiceEngine.cosine(engine.embed(ArrayPcm(plain)), profile.embedding)
                val after = VoiceEngine.cosine(engine.embed(ArrayPcm(out)), profile.embedding)
                println("text to speech: similarity to target voice $before -> $after, pitch ${Pitch.medianF0(ArrayPcm(out))} Hz (target ${profile.f0})")
                if (wavs != null) assertTrue(after > before + 0.1f, "speech was not moved to the target voice")
            }
        }
        work.deleteRecursively()
    }

    @Test
    fun pitchShiftKeepsFormantsInPlace() {
        // A "vowel": 110 Hz buzz with one strong resonance at 1.6 kHz.
        val n = MODEL_RATE * 3
        val x = FloatArray(n) { i ->
            var v = 0.0
            for (k in 1..60) {
                val f = 110.0 * k
                v += sin(2 * PI * f * i / MODEL_RATE) / (1 + ((f - 1600) / 150).let { it * it })
            }
            (0.05 * v).toFloat()
        }
        val before = ToneBalance.spectrum(ArrayPcm(x))
        val peakBand = before.indices.maxByOrNull { before[it] }!!
        for (ratio in listOf(0.8f, 1.25f)) {
            val y = collect { sink -> Pitch.shift(ArrayPcm(x), ratio, sink) }
            val after = ToneBalance.spectrum(ArrayPcm(y))
            assertEquals(peakBand, after.indices.maxByOrNull { after[it] }, "resonance moved at ratio $ratio")
            assertTrue(abs(Pitch.medianF0(ArrayPcm(y)) - 110 * ratio) < 110 * ratio * 0.04f)
        }
    }

    @Test
    fun pitchContourIsCompared() {
        val a = FloatArray(200) { 120f + 30f * sin(it * 0.1).toFloat() }
        val shifted = FloatArray(200) { a[it] * 0.8f }
        val flat = FloatArray(200) { 100f + (it % 7) }
        assertTrue(Pitch.contourCorrelation(a, shifted) > 0.999f)
        assertTrue(abs(Pitch.contourCorrelation(a, flat)) < 0.3f)
    }

    @Test
    fun toneBalanceMovesTowardsReference() {
        val rnd = java.util.Random(3)
        val white = FloatArray(MODEL_RATE * 4) { (0.1 * rnd.nextGaussian()).toFloat() }
        // "Dull" copy: a simple low-pass.
        val dull = FloatArray(white.size)
        var z = 0f
        for (i in white.indices) {
            z += 0.15f * (white[i] - z); dull[i] = z
        }
        val ref = ToneBalance.spectrum(ArrayPcm(white))
        val act = ToneBalance.spectrum(ArrayPcm(dull))
        val full = ToneBalance.gains(ref, act, 1f, 40f)
        val y = collect { sink -> ToneBalance.apply(ArrayPcm(dull), full, sink) }
        assertEquals(dull.size, y.size)
        val fixed = ToneBalance.spectrum(ArrayPcm(y))
        val first = (0 until ToneBalance.BANDS).first { ToneBalance.EDGES[it] >= ToneBalance.FIRST_BAND_HZ }
        // Shapes must match from the first corrected band up (overall level is free).
        val offset = fixed[first] - ref[first]
        for (b in first until ToneBalance.BANDS) assertTrue(abs(fixed[b] - ref[b] - offset) < 1.5f, "band $b: ${fixed[b]} vs ${ref[b]}")
        assertTrue(full.take(first).all { it == full[0] }, "low bands must share one (loudness-only) gain")
        // Differences inside the tolerance are left alone.
        val tolerant = ToneBalance.gains(FloatArray(ToneBalance.BANDS) { 3f * (it % 2) }, FloatArray(ToneBalance.BANDS), 1f, 8f, 4f)
        assertTrue(tolerant.all { abs(it) < 1e-4f })
        // Limited strength and range are respected.
        val gentle = ToneBalance.gains(ref, act, 0.5f, 8f)
        assertTrue(gentle.max() - gentle.min() <= 16f + 1e-3f)
        // A flat curve changes nothing.
        val same = collect { sink -> ToneBalance.apply(ArrayPcm(white), FloatArray(ToneBalance.BANDS), sink) }
        for (i in 2000 until white.size - 2000 step 97) assertTrue(abs(same[i] - white[i]) < 1e-3f)
    }

    private fun semitoneSpread(track: FloatArray): Double {
        val st = track.filter { it > 0f }.map { 12 * kotlin.math.ln(it.toDouble()) / kotlin.math.ln(2.0) }
        val mean = st.average()
        return sqrt(st.sumOf { (it - mean) * (it - mean) } / st.size)
    }

    /** Needs MERI_AWAAZ_MODELS=<dir with tone_encoder.onnx and converter.onnx>. */
    @Test
    fun studioConvertsVoiceover() {
        val dir = System.getenv("MERI_AWAAZ_MODELS")
        assumeTrue(dir != null, "MERI_AWAAZ_MODELS not set")
        val work = java.nio.file.Files.createTempDirectory("meri_awaaz").toFile()
        val wavs = System.getenv("MERI_AWAAZ_WAVS")
        val problems = ArrayList<String>()
        val pairs = if (wavs == null) listOf("") else listOf("", "2").filter { File(wavs, "source$it.wav").exists() }
        VoiceEngine("$dir/tone_encoder.onnx", "$dir/converter.onnx", 4).use { engine ->
          for (pair in pairs) {
            println("---- pair '$pair'")
            // Without recordings: 13 s of bursts (three model chunks) and a higher-pitched "sample".
            val src = if (wavs != null) readWav(File(wavs, "source$pair.wav")) else bursts(13, 0.005, 0.0)
            val tgt = if (wavs != null) readWav(File(wavs, "target$pair.wav")) else
                voice(210.0, MODEL_RATE * 12).also { for (i in it.indices) if ((i / 20000) % 4 == 3) it[i] = 0f }
            val rawSrc = File(work, "src.raw").also { f -> PcmWriter(f).use { it.write(src, 0, src.size) } }
            val rawTgt = File(work, "tgt.raw").also { f -> PcmWriter(f).use { it.write(tgt, 0, tgt.size) } }
            val studio = Studio(engine, work)

            val profile = studio.profile(rawTgt)!!
            println("profile: f0=${profile.f0} Hz speech=${profile.speechSeconds}s snr=${profile.snrDb} dB narrowband=${profile.narrowband}")
            assertEquals(VoiceEngine.EMBED_DIM, profile.embedding.size)
            assertTrue(profile.f0 > 0f)

            val srcTrack = Pitch.track(ArrayPcm(src))
            val srcSpec = ToneBalance.spectrum(ArrayPcm(src))
            val gSrc = engine.embed(ArrayPcm(src))
            println("source: f0=${Pitch.medianF0(ArrayPcm(src))} Hz spread=${"%.2f".format(semitoneSpread(srcTrack))} st " +
                "similarity to target=${VoiceEngine.cosine(gSrc, profile.embedding)}")

            class Run(val name: String, val options: ConvertOptions, val tau: Float)
            val bare = ConvertOptions(denoise = false, pitchMatch = false, toneBalance = false)
            val runs = listOf(
                Run("default", ConvertOptions(), 0.3f),
                Run("bare model", bare, 0.3f),
                Run("only denoise", ConvertOptions(denoise = true, pitchMatch = false, toneBalance = false), 0.3f),
                Run("only tone balance", ConvertOptions(denoise = false, pitchMatch = false, toneBalance = true), 0.3f),
                Run("only pitch match", ConvertOptions(denoise = false, pitchMatch = true, toneBalance = false), 0.3f),
                Run("bare model tau 0", bare, 0f),
                Run("bare model tau 0.15", bare, 0.15f),
            )
            val similarity = HashMap<String, Float>()
            val melody = HashMap<String, Float>()
            for (run in runs) {
                engine.tau = run.tau
                val stages = HashSet<Stage>()
                val outFile = File(work, "out.wav")
                val result = studio.convert(rawSrc, profile, run.options, outFile, { s, _ -> stages.add(s) })
                assertTrue(result.finished)
                assertTrue(stages.containsAll(listOf(Stage.CLEAN, Stage.ANALYSE, Stage.CONVERT, Stage.FINISH)))
                val out = readWav(outFile)
                assertEquals(src.size, out.size)
                assertTrue(out.all { it.isFinite() })
                if (wavs != null) assertTrue(abs(out.maxOf { abs(it) } - 0.9f) < 0.02f, "output level")
                val track = Pitch.track(ArrayPcm(out))
                val spec = ToneBalance.spectrum(ArrayPcm(out))
                val toneGap = (0 until ToneBalance.BANDS - 2).map { abs(spec[it] - srcSpec[it]) }.average()
                similarity[run.name] = VoiceEngine.cosine(engine.embed(ArrayPcm(out)), profile.embedding)
                melody[run.name] = Pitch.contourCorrelation(srcTrack, track)
                println(
                    "%-20s shift=%+.1f st  f0=%.0f Hz  spread=%.2f st  melody kept=%.2f  similarity=%.3f  tone gap to source=%.1f dB".format(
                        run.name, result.semitones, Pitch.medianF0(ArrayPcm(out)), semitoneSpread(track),
                        melody[run.name], similarity[run.name], toneGap,
                    )
                )
            }
            engine.tau = 0.3f
            if (wavs != null) {
                val before = VoiceEngine.cosine(gSrc, profile.embedding)
                assertTrue(similarity["default"]!! > before, "full pipeline did not move the voice towards the target")
                assertTrue(similarity["bare model"]!! > before, "conversion did not move the voice towards the target")
                problems += listOfNotNull(
                    "pair '$pair': intonation lost (${melody["default"]})".takeIf { melody["default"]!! < 0.5f },
                    "pair '$pair': default much less similar than bare model".takeIf { similarity["default"]!! < similarity["bare model"]!! - 0.03f },
                )
            }
          }
        }
        work.deleteRecursively()
        assertTrue(problems.isEmpty(), problems.joinToString("; "))
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
