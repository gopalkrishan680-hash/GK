package com.gk.channelradar.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gk.channelradar.data.Channel
import com.gk.channelradar.data.ChannelResult
import com.gk.channelradar.data.QuotaState
import com.gk.channelradar.data.ScanConfig
import com.gk.channelradar.data.ScanResult
import com.gk.channelradar.data.Settings
import com.gk.channelradar.data.Snapshot
import com.gk.channelradar.data.Store
import com.gk.channelradar.data.TrendsApi
import com.gk.channelradar.data.WatchItem
import com.gk.channelradar.data.YouTubeApi
import com.gk.channelradar.engine.DiscoveryEngine
import com.gk.channelradar.engine.Niches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

enum class Tab { DISCOVER, TRENDS, RESULTS, WATCHLIST, SETTINGS }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val api = YouTubeApi(store)
    private val engine = DiscoveryEngine(api, store)
    private val trendsApi = TrendsApi()

    var tab by mutableStateOf(if (store.settings.apiKeys.isEmpty()) Tab.SETTINGS else Tab.DISCOVER)
    var settings by mutableStateOf(store.settings); private set
    var config by mutableStateOf(store.scanConfig); private set
    var result by mutableStateOf(store.lastResult); private set
    var watchlist by mutableStateOf(store.watchlist); private set
    var hidden by mutableStateOf(store.hidden); private set
    var quota by mutableStateOf(store.quotaSnapshot()); private set

    var scanning by mutableStateOf(false); private set
    var progressText by mutableStateOf(""); private set
    var progress by mutableStateOf(0f); private set
    var message by mutableStateOf<String?>(null)

    var detail by mutableStateOf<ChannelResult?>(null); private set
    var detailLoading by mutableStateOf(false); private set
    var watchRefreshing by mutableStateOf(false); private set

    private var scanJob: Job? = null

    // trends / demand
    var ideas by mutableStateOf(store.ideas); private set
    var ideasTitle by mutableStateOf(if (store.ideas.isEmpty()) "" else "Last exploration"); private set
    var ideasLoading by mutableStateOf(false); private set
    var ideasProgress by mutableStateOf(0f); private set
    var googleTrends by mutableStateOf(store.trends); private set
    var trendsLoading by mutableStateOf(false); private set
    var reports by mutableStateOf(store.keywordReports); private set
    var analyzing by mutableStateOf(setOf<String>()); private set

    // ------------------------------------------------------------------ config / settings

    fun updateConfig(c: ScanConfig) { config = c; store.scanConfig = c }

    fun saveKeys(text: String) {
        val keys = text.split('\n', ',', ' ').map { it.trim() }.filter { it.length > 10 }.distinct()
        settings = Settings(apiKeys = keys)
        store.settings = settings
        message = if (keys.isEmpty()) "No valid key found" else "${keys.size} API key(s) saved ✔"
        refreshQuota()
    }

    fun refreshQuota() { quota = store.quotaSnapshot() }

    fun clearCache() { store.clearCache(); result = null; message = "Results cleared" }

    fun clearHidden() { store.hidden = emptySet(); hidden = emptySet(); message = "Hidden channels restored" }

    // ------------------------------------------------------------------ scanning

    fun startScan() {
        if (scanning) return
        scanJob = viewModelScope.launch {
            scanning = true
            progress = 0f
            progressText = "Starting…"
            try {
                val r = withContext(Dispatchers.IO) {
                    engine.scan(config) { text, p -> progressText = text; progress = p }
                }
                result = r
                store.lastResult = r
                message = "Found ${r.channels.size} small channels · used ${r.quotaUsed} quota units"
                tab = Tab.RESULTS
            } catch (e: CancellationException) {
                message = "Scan cancelled"
            } catch (e: Exception) {
                message = e.message ?: e.toString()
            } finally {
                scanning = false
                refreshQuota()
            }
        }
    }

    fun cancelScan() { scanJob?.cancel() }

    // ------------------------------------------------------------------ trends & demand

    /** Explore YouTube autocomplete. Empty seed = sample the hottest searches across all niches. */
    fun exploreIdeas(seed: String, deep: Boolean) {
        if (ideasLoading) return
        val seeds = if (seed.isBlank()) Niches.all.flatMap { it.keywords.take(2) } else listOf(seed.trim())
        viewModelScope.launch {
            ideasLoading = true
            ideasProgress = 0f
            try {
                val list = withContext(Dispatchers.IO) {
                    trendsApi.explore(seeds, config.region, config.language, deep && seed.isNotBlank()) { ideasProgress = it }
                }
                ideas = list
                store.ideas = list
                ideasTitle = if (seed.isBlank()) "Hot searches across all niches" else "Searches around \"${seed.trim()}\""
                if (list.isEmpty()) message = "No suggestions found. Try a broader word."
            } catch (e: Exception) {
                message = e.message ?: "Could not load suggestions"
            } finally {
                ideasLoading = false
            }
        }
    }

    fun loadGoogleTrends() {
        if (trendsLoading) return
        viewModelScope.launch {
            trendsLoading = true
            try {
                val list = withContext(Dispatchers.IO) { trendsApi.googleTrends(config.region) }
                googleTrends = list
                store.trends = list
                if (list.isEmpty()) message = "Google Trends returned nothing for ${config.region}"
            } catch (e: Exception) {
                message = "Could not load Google Trends: ${e.message}"
            } finally {
                trendsLoading = false
            }
        }
    }

    fun analyzeKeyword(k: String) {
        val kw = k.trim()
        if (kw.isBlank() || kw in analyzing) return
        viewModelScope.launch {
            analyzing = analyzing + kw
            try {
                val r = withContext(Dispatchers.IO) { engine.analyzeKeyword(kw, config.region, config.language) }
                reports = listOf(r) + reports.filter { !it.keyword.equals(kw, true) }.take(60)
                store.keywordReports = reports
                message = "\"$kw\": opportunity ${r.opportunity.toInt()}/100"
            } catch (e: Exception) {
                message = e.message
            } finally {
                analyzing = analyzing - kw
                refreshQuota()
            }
        }
    }

    fun deleteReport(k: String) {
        reports = reports.filter { it.keyword != k }
        store.keywordReports = reports
    }

    /** Adds a keyword to the scan's custom keywords so the next scan hunts small channels on it. */
    fun useInScan(k: String) {
        val existing = config.customKeywords.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }
        if (existing.none { it.equals(k, true) }) updateConfig(config.copy(customKeywords = (existing + k).joinToString(", ")))
        message = "\"$k\" added to scan keywords"
    }

    fun clearScanKeywords() = updateConfig(config.copy(customKeywords = ""))

    // ------------------------------------------------------------------ channel detail

    fun openDetail(r: ChannelResult) {
        detail = r
        if (r.recentUploads.isEmpty()) loadUploads()
    }

    fun openWatchItem(w: WatchItem) {
        val fromResult = result?.channels?.firstOrNull { it.channel.id == w.channel.id }
        openDetail(fromResult ?: ChannelResult(channel = w.channel, hits = emptyList(), score = 0.0))
    }

    fun closeDetail() { detail = null }

    fun loadUploads() {
        val d = detail ?: return
        if (detailLoading) return
        viewModelScope.launch {
            detailLoading = true
            try {
                val ups = withContext(Dispatchers.IO) { engine.loadUploads(d.channel) }
                val now = System.currentTimeMillis()
                val matured = ups.filter { now - it.publishedAt > 48 * 3_600_000L }
                val median = matured.map { it.views }.sorted().let { if (it.size >= 3) it[it.size / 2] else null }
                val cons = if (matured.size >= 3) matured.count { it.views >= max(d.channel.subs, 1) }.toDouble() / matured.size else null
                if (detail?.channel?.id == d.channel.id) {
                    detail = d.copy(recentUploads = ups, baselineMedian = median ?: d.baselineMedian, consistency = cons ?: d.consistency)
                }
            } catch (e: Exception) {
                message = e.message
            } finally {
                detailLoading = false
                refreshQuota()
            }
        }
    }

    fun hideChannel(id: String) {
        hidden = hidden + id
        store.hidden = hidden
        result?.let { r ->
            val nr = r.copy(channels = r.channels.filter { it.channel.id != id })
            result = nr; store.lastResult = nr
        }
        detail = null
        message = "Channel hidden – it won't appear in results again"
    }

    // ------------------------------------------------------------------ watchlist

    fun isWatched(id: String) = watchlist.any { it.channel.id == id }

    fun toggleWatch(c: Channel) {
        watchlist = if (isWatched(c.id)) {
            message = "Removed from watchlist"
            watchlist.filter { it.channel.id != c.id }
        } else {
            message = "Added to watchlist ⭐ – refresh later to track growth"
            val now = System.currentTimeMillis()
            watchlist + WatchItem(c, now, listOf(Snapshot(now, c.subs, c.totalViews, c.videoCount)))
        }
        store.watchlist = watchlist
    }

    fun refreshWatchlist() {
        if (watchRefreshing || watchlist.isEmpty()) return
        viewModelScope.launch {
            watchRefreshing = true
            try {
                val fresh = withContext(Dispatchers.IO) { engine.refreshChannels(watchlist.map { it.channel.id }) }
                    .associateBy { it.id }
                val now = System.currentTimeMillis()
                watchlist = watchlist.map { w ->
                    val c = fresh[w.channel.id] ?: return@map w
                    val snaps = (w.snapshots + Snapshot(now, c.subs, c.totalViews, c.videoCount)).takeLast(120)
                    w.copy(channel = c, snapshots = snaps)
                }
                store.watchlist = watchlist
                message = "Watchlist updated (${fresh.size} channels)"
            } catch (e: Exception) {
                message = e.message
            } finally {
                watchRefreshing = false
                refreshQuota()
            }
        }
    }

    // ------------------------------------------------------------------ export

    fun exportCsv(uri: Uri, rows: List<ChannelResult>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                sb.append("Channel,Channel URL,Subscribers,Breakout Score,Hits,Best Video,Video URL,Views,Views/Subs,Views per hour,Outlier x,Published,Niche,Tags\n")
                rows.forEach { r ->
                    val b = r.best
                    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
                    sb.append(listOf(
                        q(r.channel.title), r.channel.url, r.channel.subs, "%.0f".format(r.score), r.hits.size,
                        q(b.video.title), b.video.url, b.video.views, "%.2f".format(b.ratio), "%.0f".format(b.viewsPerHour),
                        b.outlier?.let { "%.1f".format(it) } ?: "", java.time.Instant.ofEpochMilli(b.video.publishedAt).toString(),
                        q(r.niches.joinToString(" / ")), q(r.tags.joinToString(" | ")),
                    ).joinToString(",")).append('\n')
                }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM for Excel
                    it.write(sb.toString().toByteArray())
                }
                message = "Exported ${rows.size} channels ✔"
            } catch (e: Exception) {
                message = "Export failed: ${e.message}"
            }
        }
    }

    fun estimateQuota(c: ScanConfig): Int {
        val custom = c.customKeywords.split(',', '\n').count { it.isNotBlank() }
        val kws = if (custom > 0 && c.niches.isEmpty()) max(custom, c.keywordsPerScan).coerceAtMost(30) else c.keywordsPerScan
        val search = kws * c.pagesPerKeyword * 100
        val trend = if (c.includeTrending) 17 else 0
        val details = (kws * c.pagesPerKeyword * 50 / 50) + 10
        val deep = if (c.deep) 60 else 0
        return search + trend + details + deep
    }

    fun quotaUsedToday(q: QuotaState = quota) = q.used.values.sum()
}
