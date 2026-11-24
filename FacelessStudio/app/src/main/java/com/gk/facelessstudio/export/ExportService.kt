package com.gk.facelessstudio.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gk.facelessstudio.MainActivity
import com.gk.facelessstudio.R
import com.gk.facelessstudio.data.Repository
import com.gk.facelessstudio.model.Quality
import com.gk.facelessstudio.render.Fonts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ExportState {
    data object Idle : ExportState()
    data class Running(val projectId: String, val progress: Float, val message: String) : ExportState()
    data class Done(val projectId: String, val uri: Uri) : ExportState()
    data class Failed(val projectId: String, val error: String) : ExportState()
}

object ExportManager {
    val state = MutableStateFlow<ExportState>(ExportState.Idle)
    val flow: StateFlow<ExportState> get() = state
    @Volatile internal var current: VideoExporter? = null

    fun start(context: Context, projectId: String, quality: Quality) {
        if (state.value is ExportState.Running) return
        state.value = ExportState.Running(projectId, 0f, "Starting…")
        val i = Intent(context, ExportService::class.java).putExtra("id", projectId).putExtra("q", quality.name)
        ContextCompat.startForegroundService(context, i)
    }

    fun cancel() { current?.cancelled = true }
    fun reset() { if (state.value !is ExportState.Running) state.value = ExportState.Idle }
}

/** Keeps rendering alive while the screen is off or the user switches apps. */
class ExportService : Service() {
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id") ?: return START_NOT_STICKY.also { stopSelf() }
        val quality = runCatching { Quality.valueOf(intent.getStringExtra("q")!!) }.getOrDefault(Quality.HD)
        channel()
        val notif = notification(0, "Rendering video…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIF_ID, notif)

        if (thread?.isAlive == true) return START_NOT_STICKY
        thread = Thread {
            val wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FacelessStudio:export").apply { acquire(3 * 60 * 60 * 1000L) }
            val repo = Repository(applicationContext)
            try {
                val project = repo.load(id) ?: error("Project not found")
                val out = repo.tempVideo(project)
                val exporter = VideoExporter(project, quality, Fonts(assets))
                ExportManager.current = exporter
                var lastPct = -1
                exporter.export(out) { p, msg ->
                    ExportManager.state.value = ExportState.Running(id, p, msg)
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) { lastPct = pct; nm().notify(NOTIF_ID, notification(pct, msg)) }
                }
                val uri = repo.publishToGallery(out, project.title)
                repo.save(project.copy(lastExport = uri.toString()))
                ExportManager.state.value = ExportState.Done(id, uri)
                nm().notify(DONE_ID, NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat).setContentTitle("Video ready! 🎉")
                    .setContentText(project.title.ifBlank { "Your video" } + " was saved to the gallery")
                    .setContentIntent(openApp()).setAutoCancel(true).build())
            } catch (e: ExportCancelled) {
                ExportManager.state.value = ExportState.Idle
            } catch (e: Throwable) {
                ExportManager.state.value = ExportState.Failed(id, e.message ?: e.javaClass.simpleName)
            } finally {
                ExportManager.current = null
                if (wake.isHeld) wake.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.apply { priority = Thread.MAX_PRIORITY; start() }
        return START_NOT_STICKY
    }

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun channel() {
        nm().createNotificationChannel(NotificationChannel(CHANNEL, "Video export", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notification(pct: Int, msg: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("Video render: $pct%")
        .setContentText(msg)
        .setProgress(100, pct, false)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    companion object {
        const val CHANNEL = "export"
        const val NOTIF_ID = 42
        const val DONE_ID = 43
    }
}
