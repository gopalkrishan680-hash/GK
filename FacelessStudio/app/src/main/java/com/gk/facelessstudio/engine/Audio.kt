package com.gk.facelessstudio.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.model.Transition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

const val SAMPLE_RATE = 44100

/** Decodes any audio file Android supports (mp3, m4a, wav, ogg, aac…) to mono 16-bit PCM at 44.1 kHz. */
object AudioDecoder {

    fun decode(path: String, maxMs: Long = Long.MAX_VALUE): ShortArray {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { ex.release(); error("No audio track in file") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val out = ShortBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val maxSamples = if (maxMs == Long.MAX_VALUE) Long.MAX_VALUE else maxMs * SAMPLE_RATE / 1000
        val resampler = Resampler()

        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (oi >= 0) {
                val buf = codec.getOutputBuffer(oi)!!
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val bb = buf.slice().order(java.nio.ByteOrder.nativeOrder())
                if (pcmFloat) {
                    val fb = bb.asFloatBuffer()
                    val frames = fb.remaining() / channels
                    for (f in 0 until frames) {
                        var acc = 0f
                        for (ch in 0 until channels) acc += fb.get(f * channels + ch)
                        resampler.push((acc / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort(), rate, out)
                    }
                } else {
                    val sb = bb.asShortBuffer()
                    val frames = sb.remaining() / channels
                    for (f in 0 until frames) {
                        var acc = 0
                        for (ch in 0 until channels) acc += sb.get(f * channels + ch)
                        resampler.push((acc / channels).toShort(), rate, out)
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || out.size >= maxSamples) outputDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        return out.toArray()
    }

    /** Linear resampler to 44.1 kHz. */
    private class Resampler {
        private var pos = 0.0
        private var prev: Short = 0
        private var hasPrev = false
        fun push(s: Short, rate: Int, out: ShortBuilder) {
            if (rate == SAMPLE_RATE) { out.add(s); return }
            if (!hasPrev) { prev = s; hasPrev = true; return }
            val step = rate.toDouble() / SAMPLE_RATE
            while (pos < 1.0) {
                out.add((prev + (s - prev) * pos).toInt().toShort())
                pos += step
            }
            pos -= 1.0
            prev = s
        }
    }
}

class ShortBuilder {
    private var data = ShortArray(1 shl 16)
    var size = 0; private set
    fun add(s: Short) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = s
    }
    fun toArray(): ShortArray = data.copyOf(size)
}

object VoiceAnalyzer {
    /** Finds where speech starts/ends and the silent gaps between sentences. */
    fun analyze(pcm: ShortArray): VoiceInfo {
        val win = SAMPLE_RATE / 50 // 20 ms
        val n = pcm.size / win
        if (n == 0) return VoiceInfo(0, 0, 0, emptyList())
        val rms = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            val o = i * win
            for (j in 0 until win) { val v = pcm[o + j] / 32768.0; acc += v * v }
            rms[i] = sqrt(acc / win).toFloat()
        }
        val sorted = rms.sortedArray()
        val floor = sorted[(n * 0.10).toInt()]
        val loud = sorted[(n * 0.85).toInt().coerceAtMost(n - 1)]
        val thr = floor + (loud - floor) * 0.12f
        val speech = BooleanArray(n) { rms[it] > thr }
        val first = speech.indexOfFirst { it }.coerceAtLeast(0)
        val last = speech.indexOfLast { it }.let { if (it < 0) n - 1 else it }
        val pauses = mutableListOf<Pair<Long, Long>>()
        var i = first
        while (i <= last) {
            if (!speech[i]) {
                var j = i
                while (j <= last && !speech[j]) j++
                val len = (j - i) * 20L
                if (len >= 160) pauses += Pair(((i + j) / 2) * 20L, len)
                i = j
            } else i++
        }
        return VoiceInfo(
            durationMs = pcm.size * 1000L / SAMPLE_RATE,
            speechStartMs = max(0L, first * 20L - 120),
            speechEndMs = min(n * 20L, (last + 1) * 20L + 150),
            pauses = pauses,
        )
    }

    /** Envelope in 0..1 per 10 ms, used to duck the music under the voice. */
    fun envelope(pcm: ShortArray): FloatArray {
        val win = SAMPLE_RATE / 100
        val n = pcm.size / win + 1
        val env = FloatArray(n)
        for (i in 0 until n) {
            var peak = 0
            val o = i * win
            for (j in o until min(pcm.size, o + win)) peak = max(peak, abs(pcm[j].toInt()))
            env[i] = peak / 32768f
        }
        return env
    }
}

/** Procedural sound effects – no asset files needed. */
object Sfx {
    fun whoosh(ms: Int = 380, seed: Int = 1): FloatArray {
        val n = SAMPLE_RATE * ms / 1000
        val r = Random(seed)
        val out = FloatArray(n)
        var lp = 0f; var lp2 = 0f
        for (i in 0 until n) {
            val x = i / n.toFloat()
            val env = sin(PI * x).toFloat().let { it * it }
            val cutoff = 0.02f + 0.25f * sin(PI * x).toFloat()
            val noise = r.nextFloat() * 2 - 1
            lp += cutoff * (noise - lp); lp2 += cutoff * (lp - lp2)
            out[i] = lp2 * env * 2.2f
        }
        return out
    }

    fun pop(ms: Int = 90): FloatArray {
        val n = SAMPLE_RATE * ms / 1000
        var phase = 0.0
        return FloatArray(n) { i ->
            val x = i / n.toFloat()
            val f = 900.0 - 650.0 * x
            phase += 2 * PI * f / SAMPLE_RATE
            (sin(phase) * exp(-6.0 * x) * 0.5).toFloat()
        }
    }

    fun boom(ms: Int = 700): FloatArray {
        val n = SAMPLE_RATE * ms / 1000
        var phase = 0.0
        val r = Random(3)
        return FloatArray(n) { i ->
            val x = i / n.toFloat()
            val f = 90.0 - 50.0 * x
            phase += 2 * PI * f / SAMPLE_RATE
            ((sin(phase) * 0.8 + (r.nextFloat() * 2 - 1) * 0.15 * exp(-20.0 * x)) * exp(-4.5 * x)).toFloat()
        }
    }

    fun click(): FloatArray {
        val n = SAMPLE_RATE / 100
        val r = Random(9)
        return FloatArray(n) { i -> (r.nextFloat() * 2 - 1) * exp(-i / (n / 6.0)).toFloat() * 0.25f }
    }
}

/** Builds the final soundtrack: voice + ducked looping music + auto SFX. */
object Mixer {
    fun mix(project: Project, voice: ShortArray?, music: ShortArray?, totalMs: Long): ShortArray {
        val n = (totalMs * SAMPLE_RATE / 1000).toInt()
        val buf = FloatArray(n)
        voice?.let { v -> for (i in 0 until min(n, v.size)) buf[i] = v[i] / 32768f }

        if (music != null && music.isNotEmpty()) {
            val env = voice?.let { VoiceAnalyzer.envelope(it) }
            var duck = 1f
            val fadeOut = SAMPLE_RATE * 2
            val fadeIn = SAMPLE_RATE / 2
            for (i in 0 until n) {
                val e = env?.getOrNull(i / (SAMPLE_RATE / 100)) ?: 0f
                val target = if (e > 0.05f) 0.35f else 1f
                duck += (target - duck) * (if (target < duck) 0.002f else 0.0002f)
                var g = project.musicVolume * duck
                if (i < fadeIn) g *= i / fadeIn.toFloat()
                if (i > n - fadeOut) g *= (n - i) / fadeOut.toFloat()
                buf[i] += music[i % music.size] / 32768f * g
            }
        }

        if (project.sfx) {
            val whoosh = Sfx.whoosh()
            val pop = Sfx.pop()
            val boom = Sfx.boom()
            project.scenes.forEachIndexed { idx, s ->
                if (idx > 0 && s.transition != Transition.CUT && s.transition != Transition.FADE) {
                    add(buf, whoosh, ((s.startMs - 190) * SAMPLE_RATE / 1000).toInt(), 0.32f)
                }
                when (s.type) {
                    SceneType.CHAPTER, SceneType.CHECKLIST -> add(buf, boom, (s.startMs * SAMPLE_RATE / 1000).toInt(), 0.45f)
                    SceneType.BIG_STAT -> add(buf, boom, ((s.startMs + 1000) * SAMPLE_RATE / 1000).toInt(), 0.3f)
                    SceneType.KINETIC, SceneType.VERSUS, SceneType.OUTRO -> add(buf, pop, ((s.startMs + 60) * SAMPLE_RATE / 1000).toInt(), 0.35f)
                    else -> {}
                }
            }
        }

        // soft limiter
        return ShortArray(n) { i ->
            val x = buf[i]
            val y = if (abs(x) < 0.85f) x else (0.85f + (abs(x) - 0.85f) / (1f + (abs(x) - 0.85f) * 6f)) * (if (x < 0) -1 else 1)
            (y.coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
    }

    private fun add(buf: FloatArray, fx: FloatArray, at: Int, gain: Float) {
        for (i in fx.indices) { val j = at + i; if (j in buf.indices) buf[j] += fx[i] * gain }
    }
}
