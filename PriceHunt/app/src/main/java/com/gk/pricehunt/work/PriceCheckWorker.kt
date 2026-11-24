package com.gk.pricehunt.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gk.pricehunt.MainActivity
import com.gk.pricehunt.PriceHuntApp
import com.gk.pricehunt.R
import com.gk.pricehunt.data.Store
import com.gk.pricehunt.util.rupees
import java.util.concurrent.TimeUnit

/** Re-checks the price of every tracked product and sends a notification on a drop. */
class PriceCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PriceHuntApp
        val repo = app.repo
        for (p in repo.dao.tracked()) {
            val before = p.price
            val res = repo.fetchProductPage(p.url, knownId = p.id)
            val now = res.product ?: continue
            if (res.error != null) continue
            val hitTarget = p.targetPrice != null && now.price <= p.targetPrice && before > p.targetPrice
            val dropped = now.price < before
            if (hitTarget || (dropped && (app.settings.alertOnAnyDrop || p.targetPrice == null))) {
                val store = Store.byId(now.store)?.displayName ?: now.store
                val title = if (hitTarget) "Target price reached on $store!" else "Price drop on $store"
                notify(now.id, title, "${now.title.take(60)} — now ${rupees(now.price)} (was ${rupees(before)})")
            }
        }
        return Result.success()
    }

    private fun notify(productId: String, title: String, text: String) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PRODUCT_ID, productId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, productId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, PriceHuntApp.CHANNEL_DROPS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(productId.hashCode(), n)
    }

    companion object {
        private const val PERIODIC = "price_check"
        private const val NOW = "price_check_now"

        fun schedule(ctx: Context, hours: Int) {
            val wm = WorkManager.getInstance(ctx)
            if (hours <= 0) { wm.cancelUniqueWork(PERIODIC); return }
            val req = PeriodicWorkRequestBuilder<PriceCheckWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PriceCheckWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}
