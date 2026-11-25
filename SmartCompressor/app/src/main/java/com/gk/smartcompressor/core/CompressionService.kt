package com.gk.smartcompressor.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.gk.smartcompressor.App
import com.gk.smartcompressor.MainActivity
import com.gk.smartcompressor.R
import kotlinx.coroutines.launch

/** App background me ho tab bhi compression chalta rahe – notification me progress dikhata hai. */
@android.annotation.SuppressLint("MissingPermission") // canNotify() se check hota hai
class CompressionService : LifecycleService() {
    private var collecting = false
    private val seen = mutableSetOf<Long>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel()
        val n = build("Compress ho raha hai…", 0, true)
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (!collecting) {
            collecting = true
            lifecycleScope.launch {
                App.instance.jobs.jobs.collect { list ->
                    list.filter { it.state is JobState.Running || it.state == JobState.Queued }.forEach { seen += it.id }
                    val running = list.firstOrNull { it.state is JobState.Running }
                    val queued = list.count { it.state == JobState.Queued }
                    if (running == null && queued == 0) {
                        val done = list.filter { it.state is JobState.Done && it.id in seen }
                        if (done.isNotEmpty()) notifyFinished(done)
                        ServiceCompat.stopForeground(this@CompressionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    if (running != null) {
                        val p = (running.state as JobState.Running).progress
                        val title = "${running.kind.emoji} ${running.name}"
                        val extra = if (queued > 0) " • $queued aur line me" else ""
                        if (canNotify()) runCatching {
                            NotificationManagerCompat.from(this@CompressionService)
                                .notify(NOTIF_ID, build(title, (p * 100).toInt(), false, "${(p * 100).toInt()}%$extra"))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Compression", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Compression done", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(title: String, progress: Int, indeterminate: Boolean, text: String = "") =
        NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(openApp())
            .build()

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun notifyFinished(done: List<Job>) {
        if (!canNotify()) return
        val orig = done.sumOf { it.size }
        val now = done.sumOf { (it.state as JobState.Done).outSize }
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("✅ ${done.size} file compress ho gayi")
            .setContentText("${Fmt.size(orig)} → ${Fmt.size(now)} (${Fmt.saved(orig, now)}% bachaya)")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_DONE_ID, n) }
    }

    companion object {
        const val CH = "compress"
        const val CH_DONE = "compress_done"
        const val NOTIF_ID = 11
        const val NOTIF_DONE_ID = 12
    }
}
