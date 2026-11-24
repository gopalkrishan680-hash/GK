package com.gk.pricehunt

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.gk.pricehunt.data.Repository
import com.gk.pricehunt.data.Settings
import com.gk.pricehunt.work.PriceCheckWorker

class PriceHuntApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        settings = Settings(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DROPS, "Price drop alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Alerts when a tracked product gets cheaper" }
        )
        PriceCheckWorker.schedule(this, settings.checkIntervalHours)
    }

    companion object {
        const val CHANNEL_DROPS = "price_drops"
    }
}
