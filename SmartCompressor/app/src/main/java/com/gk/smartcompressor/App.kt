package com.gk.smartcompressor

import android.app.Application
import com.gk.smartcompressor.core.HistoryStore
import com.gk.smartcompressor.core.JobManager
import com.gk.smartcompressor.core.SettingsStore

class App : Application() {
    lateinit var history: HistoryStore
    lateinit var jobs: JobManager
    lateinit var settings: SettingsStore

    override fun onCreate() {
        super.onCreate()
        instance = this
        history = HistoryStore(this)
        settings = SettingsStore(this)
        jobs = JobManager(this, history)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
