package com.gk.viralradar.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gk.viralradar.MainActivity
import com.gk.viralradar.data.AutoScan
import com.gk.viralradar.data.Fmt
import com.gk.viralradar.data.Prefs
import com.gk.viralradar.data.Tracker
import com.gk.viralradar.data.ViralScore
import com.gk.viralradar.data.YouTubeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs in the background: snapshots tracked videos (to measure real velocity/acceleration)
 * and scans niche keywords for breakout videos, then notifies.
 */
class TrackerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        if (prefs.apiKey.isBlank()) return@withContext Result.success()
        try {
            val (_, alerts) = Tracker.refresh(prefs)
            alerts.forEach { notify(applicationContext, "Tracked video accelerating", it) }

            // Niche scan costs ~102 units per keyword, so run it at most every 3 hours.
            val keywords = prefs.nicheKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(5)
            if (keywords.isNotEmpty() && System.currentTimeMillis() - prefs.lastNicheCheck > 3 * 3_600_000L) {
                val alerted = prefs.alertedIds.toMutableSet()
                keywords.forEach { kw ->
                    val vids = YouTubeApi.search(prefs.apiKey, kw, prefs.nicheHoursBack, prefs.region)
                    vids.map { it to ViralScore.score(it) }
                        .filter { it.second.score >= prefs.alertThreshold && it.first.id !in alerted }
                        .sortedByDescending { it.second.score }.take(3)
                        .forEach { (v, s) ->
                            alerted += v.id
                            notify(applicationContext, "${s.label} in \"$kw\" — score ${s.score}",
                                "${v.title.take(80)} • ${Fmt.count(s.viewsPerHour.toLong())} views/hr • ${v.channelTitle}")
                        }
                }
                prefs.alertedIds = alerted
                prefs.lastNicheCheck = System.currentTimeMillis()
            }
            // Automatic opportunity scan (~850 units) at most every 12 hours.
            if (prefs.autoScanBg && System.currentTimeMillis() - prefs.lastAutoScan > 12 * 3_600_000L) {
                val golden = AutoScan.run(applicationContext, prefs) { _, _, _ -> }.filter { it.score >= 65 }
                if (golden.isNotEmpty()) {
                    notify(applicationContext, "💎 ${golden.size} low-competition topics mile",
                        golden.take(5).joinToString("\n") { "${it.keyword} — score ${it.score}" } +
                            "\nIdeas tab kholo poori detail ke liye.")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "viral_tracker"
        private const val CHANNEL = "viral_alerts"

        fun schedule(ctx: Context, intervalMin: Int) {
            val req = PeriodicWorkRequestBuilder<TrackerWorker>(intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun notify(ctx: Context, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Viral alerts", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.star_big_on)
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build()
            runCatching { NotificationManagerCompat.from(ctx).notify((title + text).hashCode(), n) }
        }
    }
}
