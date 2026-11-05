package com.gk.metrosaathi

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Small persistent store: recent routes, favourite stations, active trip alarm. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("metro_saathi", Context.MODE_PRIVATE)

    fun recents(): List<Pair<String, String>> =
        sp.getString("recents", "").orEmpty().split("\n")
            .filter { "→" in it }
            .map { it.substringBefore("→") to it.substringAfter("→") }

    fun addRecent(from: String, to: String) {
        val list = (listOf(from to to) + recents().filter { it != from to to }).take(8)
        sp.edit().putString("recents", list.joinToString("\n") { "${it.first}→${it.second}" }).apply()
    }

    fun clearRecents() = sp.edit().remove("recents").apply()

    fun favorites(): Set<String> = sp.getString("favs", "").orEmpty().split("\n").filter { it.isNotBlank() }.toSet()

    fun toggleFavorite(name: String): Set<String> {
        val f = favorites().toMutableSet()
        if (!f.remove(name)) f += name
        sp.edit().putString("favs", f.joinToString("\n")).apply()
        return f
    }

    var alarmStation: String?
        get() = sp.getString("alarm_station", null)
        set(v) = sp.edit().putString("alarm_station", v).apply()

    var alarmAt: Long
        get() = sp.getLong("alarm_at", 0L)
        set(v) = sp.edit().putLong("alarm_at", v).apply()
}

/** "Get-off alarm" – wakes you up shortly before your destination station. */
object TripAlarm {
    private const val CHANNEL = "trip_alarm"
    private const val EXTRA_STATION = "station"

    private fun pending(context: Context, station: String?): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java)
        if (station != null) i.putExtra(EXTRA_STATION, station)
        return PendingIntent.getBroadcast(
            context, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, station: String, triggerAtMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, station)
        val show = PendingIntent.getActivity(
            context, 8, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
        Prefs(context).apply {
            alarmStation = station
            alarmAt = triggerAtMillis
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, null))
        Prefs(context).apply {
            alarmStation = null
            alarmAt = 0L
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Get-off alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alarm before your destination station"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
        nm.createNotificationChannel(ch)
    }

    internal fun notify(context: Context, station: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 9, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Wake up! $station is coming up")
            .setContentText("Your stop is in the next 1–2 stations. Move towards the door.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 800, 400, 800, 400, 800))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(42, n)
        } catch (e: SecurityException) {
            // Notification permission denied – nothing else we can do.
        }
    }

    const val STATION_KEY = EXTRA_STATION
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val station = intent.getStringExtra(TripAlarm.STATION_KEY) ?: "Your station"
        TripAlarm.notify(context, station)
        Prefs(context).apply {
            alarmStation = null
            alarmAt = 0L
        }
    }
}

/** One-shot location fix using the platform LocationManager (no Google services needed). */
object LocationHelper {
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = try { lm.getProviders(true) } catch (e: Exception) { emptyList() }
        if (providers.isEmpty()) return null
        val last = providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 3 * 60_000) return last

        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> providers.first()
        }
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }

                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
        return fresh ?: last
    }
}
