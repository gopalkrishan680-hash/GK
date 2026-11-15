package com.gk.channelradar.data

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Simple JSON-file persistence. Everything stays on the phone. */
class Store(context: Context) {
    private val dir = context.filesDir
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun <T> read(name: String, ser: KSerializer<T>, default: T): T = try {
        val f = File(dir, name)
        if (f.exists()) json.decodeFromString(ser, f.readText()) else default
    } catch (e: Exception) {
        default
    }

    private fun <T> write(name: String, ser: KSerializer<T>, value: T) {
        try {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json.encodeToString(ser, value))
            tmp.renameTo(File(dir, name))
        } catch (_: Exception) {
        }
    }

    var settings: Settings = read("settings.json", Settings.serializer(), Settings())
        set(value) { field = value; write("settings.json", Settings.serializer(), value) }

    var scanConfig: ScanConfig = read("scan_config.json", ScanConfig.serializer(), ScanConfig())
        set(value) { field = value; write("scan_config.json", ScanConfig.serializer(), value) }

    var lastResult: ScanResult? = read("last_result.json", ScanResult.serializer().nullable, null)
        set(value) { field = value; if (value != null) write("last_result.json", ScanResult.serializer(), value) }

    var watchlist: List<WatchItem> = read("watchlist.json", ListSerializer(WatchItem.serializer()), emptyList())
        set(value) { field = value; write("watchlist.json", ListSerializer(WatchItem.serializer()), value) }

    var hidden: Set<String> = read("hidden.json", SetSerializer(String.serializer()), emptySet())
        set(value) { field = value; write("hidden.json", SetSerializer(String.serializer()), value) }

    var seen: Set<String> = read("seen.json", SetSerializer(String.serializer()), emptySet())
        set(value) { field = value; write("seen.json", SetSerializer(String.serializer()), value) }

    // ---------------- quota tracking (YouTube resets quota at midnight Pacific time) -------------

    private var quota: QuotaState = read("quota.json", QuotaState.serializer(), QuotaState())

    private fun today(): String = LocalDate.now(ZoneId.of("America/Los_Angeles")).toString()

    private fun freshQuota(): QuotaState {
        if (quota.day != today()) {
            quota = QuotaState(day = today())
            write("quota.json", QuotaState.serializer(), quota)
        }
        return quota
    }

    @Synchronized
    fun addQuota(key: String, units: Int) {
        val q = freshQuota()
        val k = keyLabel(key)
        quota = q.copy(used = q.used + (k to ((q.used[k] ?: 0) + units)))
        write("quota.json", QuotaState.serializer(), quota)
    }

    @Synchronized
    fun markExhausted(key: String) {
        val q = freshQuota()
        quota = q.copy(exhausted = q.exhausted + keyLabel(key))
        write("quota.json", QuotaState.serializer(), quota)
    }

    @Synchronized
    fun isExhausted(key: String): Boolean = keyLabel(key) in freshQuota().exhausted

    @Synchronized
    fun quotaSnapshot(): QuotaState = freshQuota()

    @Synchronized
    fun totalUsedToday(): Int = freshQuota().used.values.sum()

    fun clearCache() {
        File(dir, "last_result.json").delete()
        lastResult = null
        seen = emptySet()
    }

    companion object {
        fun keyLabel(key: String): String = "…" + key.takeLast(6)
    }
}
