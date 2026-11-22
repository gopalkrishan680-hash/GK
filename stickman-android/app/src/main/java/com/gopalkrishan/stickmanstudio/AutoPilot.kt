package com.gopalkrishan.stickmanstudio

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar

/** The daily Auto Pilot alarm, its notifications, and starting the app when the alarm rings. */
object AutoPilot {
    private const val TAG = "SSAPP"
    const val CHANNEL = "autopilot"
    private const val REQ_ALARM = 41
    const val NOTE_STATUS = 7001
    const val NOTE_ACTION = 7002

    fun schedule(c: Context) {
        val p = FlowService.prefs(c)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, REQ_ALARM, Intent(c, AutoPilotReceiver::class.java).setAction(ACTION_RUN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        if (!p.getBoolean("daily", false)) { Log.i(TAG, "Auto Pilot daily run off"); return }
        val h = p.getInt("hour", 13); val m = p.getInt("minute", 0)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 30_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        p.edit().putLong("nextRun", cal.timeInMillis).apply()
        Log.i(TAG, "Auto Pilot next run ${cal.time} exact=$exact")
    }

    fun channel(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Auto Pilot", NotificationManager.IMPORTANCE_HIGH).apply { description = "Daily video status" })
    }

    fun notify(c: Context, id: Int, title: String, text: String, autopilot: Boolean = false) {
        try {
            channel(c)
            val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, "auto").putExtra(MainActivity.EXTRA_AUTOPILOT, autopilot)
            val pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        } catch (e: SecurityException) { Log.w(TAG, "notify: ${e.message}") }
    }

    const val ACTION_RUN = "com.gopalkrishan.stickmanstudio.AUTOPILOT_RUN"
}

class AutoPilotReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        AutoPilot.schedule(c)   // the next day (and after a reboot)
        if (i.action != AutoPilot.ACTION_RUN) return
        Log.i("SSAPP", "Auto Pilot alarm")
        @Suppress("DEPRECATION")
        val wl = (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "stickman:autopilot")
        try { wl.acquire(20_000) } catch (_: Exception) {}
        val svc = FlowService.instance
        if (svc != null) svc.openForAutoPilot()
        else try {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_AUTOPILOT, true))
        } catch (_: Exception) {}
        // if Android blocked the start, the user can still start it from here
        AutoPilot.notify(c, AutoPilot.NOTE_ACTION, "Auto Pilot: today's video", "Making today's video. If the app did not open, tap here.", autopilot = true)
    }
}
