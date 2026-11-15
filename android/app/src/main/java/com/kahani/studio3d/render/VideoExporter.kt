package com.kahani.studio3d.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import com.kahani.studio3d.data.Project
import com.kahani.studio3d.logic.AudioTools
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

class ExportCancelled : Exception("Export cancel kar diya")

/**
 * Renders a [Project] into an H.264 + AAC MP4 completely on the phone.
 * Video frames are drawn with [FrameRenderer] (Canvas) and pushed to the
 * hardware encoder through an OpenGL surface so every frame gets an exact timestamp.
 */
class VideoExporter(
    private val context: Context,
    private val project: Project,
    private val shortSide: Int,
    private val fps: Int,
) {
    private class Sample(val data: ByteArray, val pts: Long, val flags: Int)

    @Volatile var cancelled = false

    fun export(out: File, onProgress: (Float, String) -> Unit) {
        val (w, h) = project.aspect.size(shortSide)
        val renderer = FrameRenderer(project, w, h)
        val totalMs = renderer.durationMs
        require(totalMs > 0) { "Video ki length 0 hai" }

        onProgress(0f, "Audio taiyar ho raha hai…")
        val (audioFormat, audioSamples) = buildAudio(totalMs) { p -> onProgress(p * 0.12f, "Audio mix ho raha hai…") }
        checkCancel()

        out.delete()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bitrate = (w.toLong() * h * fps * 0.13).toInt().coerceIn(2_000_000, 16_000_000)
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = enc.createInputSurface()
        enc.start()

        val gl = GlSurface(inputSurface)
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val info = MediaCodec.BufferInfo()
        var vTrack = -1
        var aTrack = -1
        var muxStarted = false
        var audioIdx = 0
        val totalFrames = (totalMs * fps / 1000).toInt().coerceAtLeast(1)

        fun writeAudioUpTo(ptsUs: Long) {
            if (!muxStarted || aTrack < 0) return
            val bi = MediaCodec.BufferInfo()
            while (audioIdx < audioSamples.size && audioSamples[audioIdx].pts <= ptsUs) {
                val s = audioSamples[audioIdx++]
                bi.set(0, s.data.size, s.pts, s.flags)
                muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), bi)
            }
        }

        fun drain(endOfStream: Boolean) {
            if (endOfStream) enc.signalEndOfInputStream()
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) return else continue
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vTrack = muxer.addTrack(enc.outputFormat)
                    if (audioFormat != null) aTrack = muxer.addTrack(audioFormat)
                    muxer.start(); muxStarted = true
                } else if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxStarted) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        writeAudioUpTo(info.presentationTimeUs)
                        muxer.writeSampleData(vTrack, buf, info)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }

        try {
            for (f in 0 until totalFrames) {
                checkCancel()
                val tMs = f * 1000L / fps
                renderer.draw(canvas, tMs)
                gl.drawBitmap(frame)
                gl.setPresentationTime(tMs * 1_000_000L)
                gl.swap()
                drain(false)
                if (f % 5 == 0) onProgress(0.12f + 0.86f * f / totalFrames, "Video render: ${f * 100 / totalFrames}%")
            }
            drain(true)
            writeAudioUpTo(Long.MAX_VALUE)
        } finally {
            runCatching { enc.stop() }; enc.release()
            gl.release(); inputSurface.release()
            frame.recycle(); renderer.release()
            runCatching { if (muxStarted) muxer.stop() }; muxer.release()
        }
        onProgress(1f, "Ho gaya!")
    }

    private fun checkCancel() { if (cancelled) throw ExportCancelled() }

    // --------------------------------------------------------------------- audio

    private fun buildAudio(totalMs: Long, onProgress: (Float) -> Unit): Pair<MediaFormat?, List<Sample>> {
        val cache = context.cacheDir
        val voicePcm = project.voicePath?.let { p ->
            File(cache, "voice.pcm").also { AudioTools.decodeToPcm(p, it) { x -> onProgress(x * 0.4f) } }
        }
        val musicPcm = project.musicPath?.let { p ->
            File(cache, "music.pcm").also { AudioTools.decodeToPcm(p, it) { x -> onProgress(0.4f + x * 0.2f) } }
        }
        val totalFrames = totalMs * AudioTools.SAMPLE_RATE / 1000
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioTools.SAMPLE_RATE, AudioTools.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()

        val voiceIn: InputStream? = voicePcm?.let { BufferedInputStream(FileInputStream(it), 1 shl 16) }
        var musicIn: InputStream? = musicPcm?.takeIf { it.length() > 0 }?.let { BufferedInputStream(FileInputStream(it), 1 shl 16) }
        val chunkFrames = 1024
        val vBytes = ByteArray(chunkFrames * 4)
        val mBytes = ByteArray(chunkFrames * 4)
        val mixed = ByteBuffer.allocate(chunkFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        var gain = project.musicVolume
        val samples = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        var framesIn = 0L
        var inputDone = false
        val info = MediaCodec.BufferInfo()

        try {
            while (true) {
                checkCancel()
                if (!inputDone) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val remaining = totalFrames - framesIn
                        if (remaining <= 0) {
                            enc.queueInputBuffer(idx, 0, 0, framesIn * 1_000_000 / AudioTools.SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = minOf(chunkFrames.toLong(), remaining).toInt()
                            java.util.Arrays.fill(vBytes, 0); java.util.Arrays.fill(mBytes, 0)
                            voiceIn?.let { AudioTools.readFully(it, vBytes) }
                            musicIn?.let { mi ->
                                var got = AudioTools.readFully(mi, mBytes)
                                if (got < mBytes.size) { // loop the music
                                    mi.close()
                                    val again = BufferedInputStream(FileInputStream(musicPcm!!), 1 shl 16)
                                    musicIn = again
                                    val rest = ByteArray(mBytes.size - got)
                                    val g2 = AudioTools.readFully(again, rest)
                                    System.arraycopy(rest, 0, mBytes, got, g2); got += g2
                                }
                            }
                            val vb = ByteBuffer.wrap(vBytes).order(ByteOrder.LITTLE_ENDIAN)
                            val mb = ByteBuffer.wrap(mBytes).order(ByteOrder.LITTLE_ENDIAN)
                            // voice loudness for ducking
                            var sum = 0.0
                            for (i in 0 until n * 2) { val s = vb.getShort(i * 2) / 32768.0; sum += s * s }
                            val rms = sqrt(sum / (n * 2))
                            val target = if (project.duckMusic && rms > 0.015) project.musicVolume * 0.35f else project.musicVolume
                            gain += (target - gain) * (if (target < gain) 0.35f else 0.06f)
                            mixed.clear()
                            for (i in 0 until n * 2) {
                                val v = vb.getShort(i * 2) / 32768f
                                val m = mb.getShort(i * 2) / 32768f
                                val x = v + m * gain
                                mixed.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                            }
                            val buf = enc.getInputBuffer(idx)!!
                            buf.clear(); buf.put(mixed.array(), 0, n * 4)
                            enc.queueInputBuffer(idx, 0, n * 4, framesIn * 1_000_000 / AudioTools.SAMPLE_RATE, 0)
                            framesIn += n
                            onProgress(0.6f + 0.4f * framesIn / max(1L, totalFrames))
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = enc.outputFormat
                } else if (o >= 0) {
                    val b = enc.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val arr = ByteArray(info.size)
                        b.position(info.offset); b.get(arr)
                        samples += Sample(arr, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            voiceIn?.close(); musicIn?.close()
            runCatching { enc.stop() }; enc.release()
            voicePcm?.delete(); musicPcm?.delete()
        }
        return outFormat to samples
    }

    companion object {
        /** Copies the finished MP4 into the phone gallery (Movies/Kahani3D). */
        fun saveToGallery(context: Context, file: File, title: String): Uri? {
            val name = title.replace(Regex("[^A-Za-z0-9_\\- ]"), "").ifBlank { "video" } + "_" + System.currentTimeMillis() / 1000 + ".mp4"
            return if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kahani3D")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val r = context.contentResolver
                val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                r.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os) } }
                values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                r.update(uri, values, null, null)
                uri
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
                val dest = File(dir, name)
                file.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        }
    }
}

/** Minimal EGL + GLES2 wrapper that draws a Bitmap full-screen onto an encoder Surface. */
private class GlSurface(surface: Surface) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val tex: Int
    private var texReady = false
    private val quad: FloatBuffer

    init {
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

        val vs = """
            attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;
            void main(){ gl_Position = vec4(aPos,0.0,1.0); vTex = aTex; }
        """.trimIndent()
        val fs = """
            precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
            void main(){ gl_FragColor = texture2D(uTex, vTex); }
        """.trimIndent()
        program = GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
        }
        // x, y, u, v  (v flipped because bitmap rows start at the top)
        val data = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        quad = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
    }

    fun drawBitmap(b: Bitmap) {
        GLES20.glViewport(0, 0, b.width, b.height)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        if (!texReady) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0); texReady = true }
        else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, b)
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun setPresentationTime(ns: Long) = EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns)
    fun swap() = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }
}
