package com.kahani.studio3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kahani.studio3d.data.CaptionStyle
import com.kahani.studio3d.data.Motion
import com.kahani.studio3d.data.ProjectRepository
import com.kahani.studio3d.data.SplitMode
import com.kahani.studio3d.data.Transition
import com.kahani.studio3d.logic.AudioTools
import com.kahani.studio3d.logic.PromptGenerator
import com.kahani.studio3d.logic.SceneLogic
import com.kahani.studio3d.render.FrameRenderer
import com.kahani.studio3d.render.VideoExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * End-to-end check of the on-device engine: voice analysis, auto-sync,
 * frame rendering and the MP4 export (video + mixed audio).
 */
@RunWith(AndroidJUnit4::class)
class ExportTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun asset(name: String, dir: File) = File(dir, name).also { f ->
        assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    @Test
    fun fullPipeline() {
        val repo = ProjectRepository(ctx)
        var p = repo.create("Emulator Test")
        val dir = repo.dir(p.id)
        val voice = asset("voice.mp3", dir)
        val music = asset("music.m4a", dir)
        val imgs = (1..4).map { asset("$it.png", dir) }

        val script = "Ek gaon me Raju rehta tha. Wo roz school jata tha! Ek din usne jaadui patthar dekha. Patthar chamak raha tha. Aur kahani shuru hui."
        val texts = SceneLogic.split(script, SplitMode.SENTENCE)
        assertEquals(5, texts.size)
        p = p.copy(
            script = script,
            voicePath = voice.absolutePath, voiceName = "voice.mp3",
            voiceDurationMs = AudioTools.durationMs(voice.absolutePath),
            musicPath = music.absolutePath, musicName = "music.m4a",
            scenes = SceneLogic.buildScenes(texts, emptyList()),
            captionStyle = CaptionStyle.KARAOKE_YELLOW,
            transition = Transition.CROSSFADE,
            watermark = "@KahaniTest",
        )
        assertTrue("voice duration ${p.voiceDurationMs}", abs(p.voiceDurationMs - 15_000) < 300)

        // voice analysis -> pauses every 3 s (silence 2.5..3.0 s)
        val pcm = File(ctx.cacheDir, "t.pcm")
        val frames = AudioTools.decodeToPcm(voice.absolutePath, pcm)
        assertTrue("frames $frames", abs(frames - 15L * 44100) < 44100 / 4)
        val pauses = AudioTools.findPauses(AudioTools.envelope(pcm))
        pcm.delete()
        android.util.Log.i("ExportTest", "pauses=$pauses")
        assertTrue("pauses $pauses", pauses.size >= 4)
        assertTrue("pause near 2750: $pauses", pauses.any { abs(it - 2750) < 200 })

        val timed = SceneLogic.autoTime(p, pauses)
        android.util.Log.i("ExportTest", "durations=${timed.map { it.durationMs }}")
        assertEquals(p.voiceDurationMs, timed.sumOf { it.durationMs })
        val motions = listOf(Motion.TILT_3D, Motion.ZOOM_IN, Motion.ORBIT_3D, Motion.PAN_LEFT, Motion.AUTO)
        val trans = listOf(null, Transition.CUBE_3D, Transition.SLIDE, Transition.ZOOM_THROUGH, Transition.FLASH)
        p = p.copy(scenes = timed.mapIndexed { i, s ->
            s.copy(imagePath = imgs.getOrNull(i)?.absolutePath, motion = motions[i], transition = trans[i],
                prompt = PromptGenerator.offline(p, i, s))
        })
        repo.save(p)

        // render sample frames for visual inspection
        val out = ctx.getExternalFilesDir(null)!!
        val (w, h) = p.aspect.size(480)
        val r = FrameRenderer(p, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var acc = 0L
        val starts = p.scenes.map { val s = acc; acc += it.durationMs; s }
        listOf(1500L, starts[1] + 200, starts[2] + 150, starts[3] + 1000, starts[4] + 100, 14_000L).forEachIndexed { k, t ->
            r.draw(Canvas(bmp), t)
            File(out, "frame_$k.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // full export
        val mp4 = File(out, "test_export.mp4")
        val t0 = System.currentTimeMillis()
        VideoExporter(ctx, p, 480, 24).export(mp4) { _, _ -> }
        android.util.Log.i("ExportTest", "export took ${System.currentTimeMillis() - t0} ms, size=${mp4.length()}")
        assertTrue(mp4.length() > 50_000)

        val ex = MediaExtractor()
        ex.setDataSource(mp4.absolutePath)
        assertEquals(2, ex.trackCount)
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! }
        assertTrue(mimes.toString(), mimes.contains("video/avc") && mimes.contains("audio/mp4a-latm"))
        val v = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.first { it.getString(MediaFormat.KEY_MIME) == "video/avc" }
        assertEquals(w, v.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(h, v.getInteger(MediaFormat.KEY_HEIGHT))
        ex.release()
        val dur = AudioTools.durationMs(mp4.absolutePath)
        assertTrue("duration $dur", abs(dur - 15_000) < 500)
    }
}
