package com.desijokes.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Daily "joke of the day" notification at ~8 PM, rescheduled after reboot. */
object DailyJoke {
    private const val ACTION = "com.desijokes.app.DAILY_JOKE"
    private const val CHANNEL = "daily"
    const val EXTRA_OPEN_TODAY = "open_today"

    fun isEnabled(ctx: Context) = JokeRepo.prefs(ctx).getBoolean("daily_on", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        JokeRepo.prefs(ctx).edit().putBoolean("daily_on", on).apply()
        if (on) schedule(ctx) else ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
    }

    fun schedule(ctx: Context) {
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        ctx.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.RTC_WAKEUP, next.timeInMillis, AlarmManager.INTERVAL_DAY, pending(ctx)
        )
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, DailyJokeReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun notify(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aaj ka joke", NotificationManager.IMPORTANCE_DEFAULT))
        val joke = JokeRepo.jokeOfDay(ctx)
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_TODAY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("☀️ Aaj ka joke 😂")
            .setContentText(joke.text.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(joke.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(100, n)
    }

    fun isDailyAction(action: String?) = action == ACTION
}

class DailyJokeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when {
            DailyJoke.isDailyAction(intent.action) -> DailyJoke.notify(ctx)
            intent.action == Intent.ACTION_BOOT_COMPLETED && DailyJoke.isEnabled(ctx) -> DailyJoke.schedule(ctx)
        }
    }
}
