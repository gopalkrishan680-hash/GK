package com.gk.meriawaaz.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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

    /** Needs MERI_AWAAZ_MODELS=<dir with tone_encoder.onnx and converter.onnx>. */
    @Test
    fun modelConvertsLongAudio() {
        val dir = System.getenv("MERI_AWAAZ_MODELS")
        assumeTrue(dir != null, "MERI_AWAAZ_MODELS not set")
        VoiceEngine("$dir/tone_encoder.onnx", "$dir/converter.onnx", 4).use { engine ->
            val wavs = System.getenv("MERI_AWAAZ_WAVS")
            // 13 s forces three chunks; real recordings are used when provided.
            val src = if (wavs != null) readWav(File(wavs, "source.wav")) else
                FloatArray(MODEL_RATE * 13) { (0.3 * sin(it * 0.05) * sin(it * 0.0007)).toFloat() }
            val tgt = if (wavs != null) readWav(File(wavs, "target.wav")) else sine(180.0, MODEL_RATE, MODEL_RATE * 4)
            val gSrc = engine.embed(ArrayPcm(src))
            val gTgt = engine.embed(ArrayPcm(tgt))
            assertEquals(VoiceEngine.EMBED_DIM, gTgt.size)

            val outFile = File(System.getProperty("java.io.tmpdir"), "meri_awaaz_test_out.wav")
            val writer = PcmWriter(outFile, MODEL_RATE)
            val done = writer.use { w -> engine.convert(ArrayPcm(src), gSrc, gTgt, w::write) }
            assertTrue(done)
            val out = readWav(outFile)
            assertEquals(src.size, out.size)
            assertTrue(out.all { it.isFinite() })
            println("converted ${src.size / MODEL_RATE}s -> $outFile")

            if (wavs != null) {
                assertTrue(out.maxOf { abs(it) } > 0.05f, "output is silent")
                val gOut = engine.embed(ArrayPcm(out))
                val before = VoiceEngine.cosine(gSrc, gTgt)
                val after = VoiceEngine.cosine(gOut, gTgt)
                println("similarity to target voice: source=$before converted=$after")
                assertTrue(after > before, "conversion did not move the voice towards the target")
            }
        }
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
