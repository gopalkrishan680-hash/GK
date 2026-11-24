package com.gk.facelessstudio.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import com.gk.facelessstudio.engine.AudioDecoder
import com.gk.facelessstudio.engine.Mixer
import com.gk.facelessstudio.engine.SAMPLE_RATE
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.Quality
import com.gk.facelessstudio.render.Fonts
import com.gk.facelessstudio.render.FrameRenderer
import com.gk.facelessstudio.render.ImageStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class ExportCancelled : Exception("Export cancelled")

/**
 * Renders every frame with [FrameRenderer] on several CPU cores, uploads it to a GL texture and feeds the
 * hardware H.264 encoder through an input Surface with exact timestamps. Audio is mixed and encoded to AAC.
 */
class VideoExporter(
    private val project: Project,
    private val quality: Quality,
    private val fonts: Fonts,
    private val fps: Int = 30,
) {
    @Volatile var cancelled = false

    fun export(out: File, onProgress: (Float, String) -> Unit) {
        val (w, h) = project.aspect.size(quality)
        val totalMs = project.durationMs
        val frames = (totalMs * fps / 1000).toInt().coerceAtLeast(1)

        onProgress(0f, "Mixing audio…")
        val voice = project.voicePath?.let { runCatching { AudioDecoder.decode(it) }.getOrNull() }
        val music = project.musicPath?.let { runCatching { AudioDecoder.decode(it, maxMs = 10 * 60_000L) }.getOrNull() }
        val pcm = Mixer.mix(project, voice, music, totalMs)
        checkCancel()
        val audio = encodeAac(pcm)
        checkCancel()

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val encoder = SurfaceEncoder(w, h, fps, quality.bitrate)
        val images = ImageStore(project.images, (max(w, h) * 1.3f).toInt())
        val threads = max(1, min(4, Runtime.getRuntime().availableProcessors() - 1))
        val pool = Executors.newFixedThreadPool(threads)
        val renderers = List(threads) { FrameRenderer(w, h, project, fonts, images) }
        val bitmaps = List(threads) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }

        var videoTrack = -1
        var audioTrack = -1
        var muxStarted = false
        var audioIdx = 0
        val start = System.currentTimeMillis()

        fun writeAudioUpTo(ptsUs: Long) {
            if (!muxStarted) return
            val info = MediaCodec.BufferInfo()
            while (audioIdx < audio.samples.size && audio.samples[audioIdx].pts <= ptsUs) {
                val s = audio.samples[audioIdx++]
                info.set(0, s.data.size, s.pts, s.flags)
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), info)
            }
        }

        val sink = object : SurfaceEncoder.Sink {
            override fun onFormat(format: MediaFormat) {
                videoTrack = muxer.addTrack(format)
                audioTrack = muxer.addTrack(audio.format)
                muxer.start(); muxStarted = true
            }
            override fun onSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
                writeAudioUpTo(info.presentationTimeUs)
                muxer.writeSampleData(videoTrack, buf, info)
            }
        }

        try {
            var f = 0
            while (f < frames) {
                checkCancel()
                val batch = min(threads, frames - f)
                val base = f
                val futures = (0 until batch).map { j ->
                    pool.submit(Callable {
                        val bmp = bitmaps[j]
                        val c = Canvas(bmp)
                        renderers[j].draw(c, (base + j) * 1000L / fps)
                        bmp
                    })
                }
                futures.forEachIndexed { j, fut ->
                    val bmp = fut.get()
                    encoder.encode(bmp, (base + j) * 1_000_000_000L / fps, sink)
                }
                f += batch
                val prog = f / frames.toFloat()
                val el = (System.currentTimeMillis() - start) / 1000f
                val eta = if (prog > 0.02f) ((el / prog) - el).toInt() else -1
                onProgress(0.05f + 0.93f * prog, "Frame $f / $frames" + if (eta >= 0) "  •  ~${eta / 60}m ${eta % 60}s left" else "")
            }
            encoder.finish(sink)
            writeAudioUpTo(Long.MAX_VALUE)
            onProgress(1f, "Done")
        } finally {
            pool.shutdownNow()
            encoder.release()
            runCatching { if (muxStarted) muxer.stop() }
            muxer.release()
            bitmaps.forEach { it.recycle() }
        }
    }

    private fun checkCancel() { if (cancelled) throw ExportCancelled() }

    // ---------------------------------------------------------------- audio
    private class Sample(val data: ByteArray, val pts: Long, val flags: Int)
    private class EncodedAudio(val format: MediaFormat, val samples: List<Sample>)

    private fun encodeAac(pcm: ShortArray): EncodedAudio {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val samples = mutableListOf<Sample>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inputDone = false
        var done = false
        while (!done) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    buf.clear()
                    val count = min(buf.capacity() / 2, pcm.size - pos).coerceAtMost(4096)
                    val pts = pos * 1_000_000L / SAMPLE_RATE
                    if (count <= 0) {
                        codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, pos, count)
                        codec.queueInputBuffer(ii, 0, count * 2, pts, 0)
                        pos += count
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                oi >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val arr = ByteArray(info.size); buf.get(arr)
                        samples += Sample(arr, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            if (cancelled) break
        }
        codec.stop(); codec.release()
        return EncodedAudio(outFormat ?: error("AAC encoder gave no format"), samples)
    }
}

/** H.264 encoder fed through EGL so every frame gets an exact presentation time. */
class SurfaceEncoder(private val w: Int, private val h: Int, fps: Int, bitrate: Int) {
    interface Sink {
        fun onFormat(format: MediaFormat)
        fun onSample(buf: ByteBuffer, info: MediaCodec.BufferInfo)
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val input: Surface
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var tex = 0
    private var texAllocated = false
    private val info = MediaCodec.BufferInfo()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
    }

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        input = codec.createInputSurface()
        codec.start()
        setupEgl()
        setupGl()
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreateWindowSurface(display, configs[0], input, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun setupGl() {
        val vs = """
            attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;
            void main(){ gl_Position = vec4(aPos,0.0,1.0); vTex = aTex; }
        """.trimIndent()
        val fs = """
            precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
            void main(){ gl_FragColor = texture2D(uTex, vTex); }
        """.trimIndent()
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(program)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun encode(bmp: Bitmap, ptsNs: Long, sink: Sink) {
        drain(false, sink)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        if (!texAllocated) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0); texAllocated = true }
        else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs)
        EGL14.eglSwapBuffers(display, surface)
    }

    fun finish(sink: Sink) {
        codec.signalEndOfInputStream()
        drain(true, sink)
    }

    private fun drain(end: Boolean, sink: Sink) {
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.onFormat(codec.outputFormat)
                oi >= 0 -> {
                    val buf = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        sink.onSample(buf, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun release() {
        runCatching { codec.stop() }; runCatching { codec.release() }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
        input.release()
    }

    companion object { private const val EGL_RECORDABLE_ANDROID = 0x3142 }
}
