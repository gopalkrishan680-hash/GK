package com.gk.papercut.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.gk.papercut.render.FrameRenderer
import com.gk.papercut.sync.AudioTools
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Renders every frame with [FrameRenderer], encodes H.264 + AAC (the voice-over) and writes an MP4.
 * Runs fully offline with Android's hardware encoders.
 */
class VideoExporter(private val renderer: FrameRenderer, private val audio: File?) {

    private class Sample(val data: ByteArray, val pts: Long, val flags: Int)

    suspend fun export(out: File, fps: Int = 30, progress: (Float, String) -> Unit) {
        val w = renderer.outW; val h = renderer.outH
        val duration = renderer.duration.coerceAtLeast(1.0)

        // 1) Voice-over -> AAC samples in memory (a few MB for several minutes).
        var audioFormat: MediaFormat? = null
        val audioSamples = ArrayList<Sample>()
        if (audio != null && audio.exists()) {
            progress(0f, "Encoding voice-over…")
            encodeAac(audio) { fmt, s -> if (fmt != null) audioFormat = fmt; if (s != null) audioSamples += s }
        }

        // 2) Video encoder
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.15).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()

        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()

        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val argb = IntArray(w * h)
        val total = (duration * fps).toInt()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(enc.outputFormat)
                        val at = audioFormat?.let { muxer.addTrack(it) } ?: -1
                        muxer.start(); muxing = true
                        if (at >= 0) {
                            val bi = MediaCodec.BufferInfo()
                            for (s in audioSamples) {
                                if (s.pts > (duration * 1_000_000).toLong()) break
                                bi.set(0, s.data.size, s.pts, s.flags)
                                muxer.writeSampleData(at, ByteBuffer.wrap(s.data), bi)
                            }
                        }
                    }
                    idx >= 0 -> {
                        val buf = enc.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            for (i in 0..total) {
                coroutineContext.ensureActive()
                val t = i.toDouble() / fps
                renderer.prefetch(t)
                renderer.render(canvas, t)
                frame.getPixels(argb, 0, w, 0, 0, w, h)
                var inIdx: Int
                while (true) {
                    inIdx = enc.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) break
                    drain(false)
                }
                val image = enc.getInputImage(inIdx)!!
                writeYuv(argb, w, h, image)
                val pts = i * 1_000_000L / fps
                val last = i == total
                enc.queueInputBuffer(inIdx, 0, w * h * 3 / 2, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                drain(false)
                if (i % 10 == 0) progress(i / total.toFloat(), "Rendering frame $i / $total")
            }
            drain(true)
            progress(1f, "Done")
        } finally {
            runCatching { enc.stop() }; enc.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
            frame.recycle()
        }
    }

    /** ARGB -> YUV420 (BT.601 limited range) into whatever plane layout the encoder wants. */
    private fun writeYuv(argb: IntArray, w: Int, h: Int, image: android.media.Image) {
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = argb[row + x]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                yb.put(y * yRow + x * yPix, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (y and 1 == 0 && x and 1 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    ub.put((y / 2) * uRow + (x / 2) * uPix, u.coerceIn(0, 255).toByte())
                    vb.put((y / 2) * vRow + (x / 2) * vPix, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    /** Decodes the voice-over and re-encodes it as AAC-LC. */
    private fun encodeAac(file: File, sink: (MediaFormat?, Sample?) -> Unit) {
        var enc: MediaCodec? = null
        var sampleRate = 44100; var channels = 1; var isFloat = false
        var framesWritten = 0L
        val info = MediaCodec.BufferInfo()

        fun drain(eos: Boolean) {
            val e = enc ?: return
            while (true) {
                val idx = e.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return else continue }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { sink(e.outputFormat, null); continue }
                if (idx < 0) continue
                val b = e.getOutputBuffer(idx)!!
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val arr = ByteArray(info.size)
                    b.position(info.offset); b.get(arr)
                    sink(null, Sample(arr, info.presentationTimeUs, info.flags))
                }
                e.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }

        fun feed(pcm16: ByteArray, len: Int, eos: Boolean) {
            val e = enc!!
            var off = 0
            do {
                val idx = e.dequeueInputBuffer(10_000)
                if (idx < 0) { drain(false); continue }
                val buf = e.getInputBuffer(idx)!!
                val n = minOf(buf.capacity(), len - off)
                buf.clear(); buf.put(pcm16, off, n)
                val pts = framesWritten * 1_000_000L / sampleRate
                framesWritten += n / (2 * channels)
                off += n
                val last = eos && off >= len
                e.queueInputBuffer(idx, 0, n, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                drain(false)
            } while (off < len)
        }

        try {
            AudioTools.decode(file, { f ->
                sampleRate = f.sampleRate; channels = f.channels.coerceIn(1, 2); isFloat = f.float
                val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
                }
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                    it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); it.start()
                }
                srcChannels = f.channels
            }) { buf, size ->
                // Convert to interleaved 16-bit with at most 2 channels.
                val bps = if (isFloat) 4 else 2
                val frames = size / (bps * srcChannels)
                val outBytes = ByteArray(frames * channels * 2)
                var o = 0
                for (i in 0 until frames) {
                    for (c in 0 until srcChannels) {
                        val s: Int = if (isFloat) (buf.getFloat() * 32767f).toInt().coerceIn(-32768, 32767) else buf.getShort().toInt()
                        if (c < channels) { outBytes[o++] = (s and 255).toByte(); outBytes[o++] = ((s shr 8) and 255).toByte() }
                    }
                }
                feed(outBytes, outBytes.size, false)
            }
            if (enc != null) { feed(ByteArray(0), 0, true); drain(true) }
        } finally {
            enc?.let { runCatching { it.stop() }; it.release() }
        }
    }

    private var srcChannels = 1
}
