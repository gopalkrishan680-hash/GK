package com.gk.facelessstudio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.View
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.render.Fonts
import com.gk.facelessstudio.render.FrameRenderer
import com.gk.facelessstudio.render.ImageStore

/** Lightweight live preview: renders frames at low resolution and plays the voice + music in sync. */
class PreviewView(context: Context) : View(context) {
    private var renderer: FrameRenderer? = null
    private var frame: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var project: Project? = null
    private var voice: MediaPlayer? = null
    private var music: MediaPlayer? = null

    var playing = false; private set
    private var baseMs = 0L
    private var startedAt = 0L
    var onTime: ((Long, Boolean) -> Unit)? = null

    val positionMs: Long get() = if (playing) baseMs + (SystemClock.elapsedRealtime() - startedAt) else baseMs

    fun bind(p: Project, fonts: Fonts) {
        val old = project
        project = p
        val (w, h) = previewSize(p)
        renderer = FrameRenderer(w, h, p, fonts, ImageStore(p.images, 900))
        if (frame?.width != w || frame?.height != h) frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (old?.voicePath != p.voicePath || old?.musicPath != p.musicPath) {
            releasePlayers()
            voice = p.voicePath?.let { path -> runCatching { MediaPlayer().apply { setDataSource(path); prepare() } }.getOrNull() }
            music = p.musicPath?.let { path ->
                runCatching { MediaPlayer().apply { setDataSource(path); isLooping = true; prepare() } }.getOrNull()
            }
        }
        music?.setVolume(p.musicVolume * 0.6f, p.musicVolume * 0.6f)
        invalidate()
    }

    private fun previewSize(p: Project): Pair<Int, Int> = when (p.aspect) {
        com.gk.facelessstudio.model.Aspect.LANDSCAPE -> 640 to 360
        com.gk.facelessstudio.model.Aspect.PORTRAIT -> 360 to 640
        com.gk.facelessstudio.model.Aspect.SQUARE -> 480 to 480
    }

    fun toggle() = if (playing) pause() else play()

    fun play() {
        val p = project ?: return
        if (baseMs >= p.durationMs) baseMs = 0
        playing = true
        startedAt = SystemClock.elapsedRealtime()
        voice?.let { if (baseMs < it.duration) { it.seekTo(baseMs.toInt()); it.start() } }
        music?.let { it.seekTo((baseMs % it.duration.coerceAtLeast(1)).toInt()); it.start() }
        invalidate()
    }

    fun pause() {
        if (!playing) return
        baseMs = positionMs
        playing = false
        voice?.takeIf { it.isPlaying }?.pause()
        music?.takeIf { it.isPlaying }?.pause()
        onTime?.invoke(baseMs, false)
        invalidate()
    }

    fun seek(ms: Long) {
        val wasPlaying = playing
        if (playing) pause()
        baseMs = ms.coerceIn(0, project?.durationMs ?: 0)
        if (wasPlaying) play() else { onTime?.invoke(baseMs, false); invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val r = renderer ?: return
        val f = frame ?: return
        val p = project ?: return
        var t = positionMs
        if (t >= p.durationMs && playing) { pause(); baseMs = p.durationMs; t = baseMs }
        r.draw(Canvas(f), t)
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(f, null, dst, paint)
        onTime?.invoke(t, playing)
        if (playing) postInvalidateOnAnimation()
    }

    private fun releasePlayers() {
        voice?.release(); music?.release(); voice = null; music = null
    }

    fun release() { pause(); releasePlayers() }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); release() }
}
