package com.gk.viralradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gk.viralradar.data.ApiException
import com.gk.viralradar.data.FormatFilter
import com.gk.viralradar.data.KeywordReport
import com.gk.viralradar.data.Opportunity
import com.gk.viralradar.data.InsightReport
import com.gk.viralradar.data.Insights
import com.gk.viralradar.data.Prefs
import com.gk.viralradar.data.Scored
import com.gk.viralradar.data.TrackedVideo
import com.gk.viralradar.data.Tracker
import com.gk.viralradar.data.Snapshot
import com.gk.viralradar.data.Video
import com.gk.viralradar.data.ViralScore
import com.gk.viralradar.data.YouTubeApi
import com.gk.viralradar.work.TrackerWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ListState(
    val loading: Boolean = false,
    val error: String? = null,
    val items: List<Scored> = emptyList(),
    val updatedAt: Long = 0,
    val note: String? = null,
    /** True when the list comes from search (costs ~100 units), so auto-refresh slows down. */
    val expensive: Boolean = false,
)

enum class SortBy(val label: String) { SCORE("Viral score"), VELOCITY("Views/hour"), OUTLIER("Outlier x"), ENGAGEMENT("Engagement"), NEWEST("Newest") }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    val trending = MutableStateFlow(ListState())
    val discover = MutableStateFlow(ListState())
    val watchlist = MutableStateFlow(prefs.loadWatchlist())
    val watchBusy = MutableStateFlow(false)
    val watchMsg = MutableStateFlow<String?>(null)
    val categories = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val region = MutableStateFlow(prefs.region)
    val category = MutableStateFlow<String?>(null)
    val format = MutableStateFlow(FormatFilter.ALL)
    val sortBy = MutableStateFlow(SortBy.SCORE)
    val selected = MutableStateFlow<Scored?>(null)
    val selectedTrack = MutableStateFlow<String?>(null)
    val insights = MutableStateFlow<InsightReport?>(null)
    val hasKey = MutableStateFlow(prefs.apiKey.isNotBlank())

    init {
        if (hasKey.value) {
            refreshTrending()
            loadCategories()
            TrackerWorker.schedule(app, prefs.intervalMin)
        }
    }

    private fun scoreAll(v: List<Video>): List<Scored> {
        val now = System.currentTimeMillis()
        return v.map { Scored(it, ViralScore.score(it, now)) }
    }

    fun sorted(items: List<Scored>, f: FormatFilter, s: SortBy): List<Scored> {
        val filtered = when (f) {
            FormatFilter.ALL -> items
            FormatFilter.SHORTS -> items.filter { it.video.isShort }
            FormatFilter.LONG -> items.filter { !it.video.isShort && !it.video.wasLive }
            FormatFilter.LIVE -> items.filter { it.video.wasLive }
            FormatFilter.NO_LIVE -> items.filter { !it.video.wasLive }
        }
        return when (s) {
            SortBy.SCORE -> filtered.sortedByDescending { it.score.score }
            SortBy.VELOCITY -> filtered.sortedByDescending { it.score.viewsPerHour }
            SortBy.OUTLIER -> filtered.sortedByDescending { it.score.outlier ?: 0.0 }
            SortBy.ENGAGEMENT -> filtered.sortedByDescending { it.score.engagement }
            SortBy.NEWEST -> filtered.sortedByDescending { it.video.publishedAt }
        }
    }

    fun refreshTrending() {
        if (trending.value.loading) return
        trending.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val cat = category.value
            var note: String? = null
            var expensive = false
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    try {
                        YouTubeApi.trending(prefs.apiKey, region.value, cat)
                    } catch (e: ApiException) {
                        if (cat == null || e.code != 404) throw e
                        // YouTube has no trending chart for this category: fall back to the
                        // most-viewed uploads of the last 7 days in that category.
                        val name = categories.value.firstOrNull { it.first == cat }?.second ?: "Is category"
                        note = "$name ka official trending chart YouTube nahi deta, isliye pichhle 7 din ke sabse zyada dekhe gaye videos dikha rahe hain (har ghante refresh)."
                        expensive = true
                        YouTubeApi.search(prefs.apiKey, "", 168, region.value, categoryId = cat)
                    }
                }
            }
            r.onSuccess { v ->
                val scored = scoreAll(v)
                trending.value = ListState(items = scored, updatedAt = System.currentTimeMillis(), note = note, expensive = expensive)
                if (cat == null) insights.value = Insights.build(scored)
            }.onFailure { e -> trending.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun loadCategories() = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { YouTubeApi.categories(prefs.apiKey, region.value) } }
            .onSuccess { categories.value = it }
    }

    fun setRegion(r: String) {
        region.value = r; prefs.region = r; category.value = null
        trending.value = ListState()
        refreshTrending(); loadCategories()
    }

    fun setCategory(c: String?) { category.value = c; trending.value = ListState(); refreshTrending() }

    /** Keyword search OR, if the input is a YouTube link, analyse that single video. */
    fun discover(query: String, hours: Int, shortsOnly: Boolean) {
        val q = query.trim()
        if (q.isEmpty() || discover.value.loading) return
        discover.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val id = YouTubeApi.extractId(q)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (id != null && (q.contains("youtu") || q.length == 11)) YouTubeApi.withSubs(prefs.apiKey, YouTubeApi.videosByIds(prefs.apiKey, listOf(id)))
                    else YouTubeApi.search(prefs.apiKey, q, hours, region.value, duration = if (shortsOnly) "short" else null)
                }
            }
            r.onSuccess { v ->
                val scored = scoreAll(v)
                discover.value = ListState(items = scored, updatedAt = System.currentTimeMillis())
                if (id != null && scored.size == 1) selected.value = scored.first()
                if (scored.isEmpty()) discover.update { it.copy(error = "Kuch nahi mila. Doosra keyword ya bada time window try karo.") }
            }.onFailure { e -> discover.update { it.copy(loading = false, error = e.message) } }
        }
    }

    // ---------- Opportunity / keyword gap ----------
    val oppSuggestions = MutableStateFlow<List<String>>(emptyList())
    val oppReports = MutableStateFlow<List<KeywordReport>>(emptyList())
    val oppProgress = MutableStateFlow<Pair<Int, Int>?>(null)   // done / total
    val oppError = MutableStateFlow<String?>(null)
    val oppSuggestBusy = MutableStateFlow(false)
    private val oppCache = mutableMapOf<String, KeywordReport>()

    /** Free: asks YouTube autocomplete what people search around this seed. */
    fun loadSuggestions(seed: String) {
        val q = seed.trim()
        if (q.isEmpty()) return
        oppSuggestBusy.value = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val r = region.value
                val base = YouTubeApi.suggestions(q, r)
                val more = listOf("$q kaise", "$q in hindi", "how to $q", "$q ideas").flatMap { YouTubeApi.suggestions(it, r).take(4) }
                (listOf(q) + base + more).map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().take(25)
            }
            oppSuggestions.value = list
            if (list.size <= 1) oppError.value = "Autocomplete se suggestions nahi mile — seed keyword khud analyze kar sakte ho ya apne keywords comma se likho."
            else oppError.value = null
            oppSuggestBusy.value = false
        }
    }

    /** ~102 quota units per keyword. Results cached for 6 hours. */
    fun analyzeKeywords(keywords: List<String>, hoursBack: Int) {
        val kws = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (kws.isEmpty() || oppProgress.value != null) return
        oppError.value = null
        oppProgress.value = 0 to kws.size
        val autocomplete = oppSuggestions.value.toSet()
        viewModelScope.launch {
            kws.forEachIndexed { i, kw ->
                val ck = "$kw|$hoursBack|${region.value}"
                val cached = oppCache[ck]?.takeIf { System.currentTimeMillis() - it.updatedAt < 6 * 3_600_000L }
                val rep = cached ?: withContext(Dispatchers.IO) {
                    runCatching {
                        val (v, total) = YouTubeApi.searchWithTotal(prefs.apiKey, kw, hoursBack, region.value)
                        Opportunity.build(kw, v, total, kw.lowercase() in autocomplete, hoursBack)
                    }
                }.onFailure { oppError.value = it.message }.getOrNull()
                if (rep != null) {
                    oppCache[ck] = rep
                    oppReports.update { old -> (old.filterNot { it.keyword == kw } + rep).sortedByDescending { it.score } }
                } else if (oppError.value?.contains("quota", true) == true) {
                    oppProgress.value = null; return@launch
                }
                oppProgress.value = (i + 1) to kws.size
            }
            oppProgress.value = null
        }
    }

    fun clearReports() { oppReports.value = emptyList() }

    // ---------- Tracker ----------
    fun isTracked(id: String) = watchlist.value.any { it.id == id }

    fun track(v: Video) {
        if (isTracked(v.id)) return
        val now = System.currentTimeMillis()
        val t = TrackedVideo(v.id, v.title, v.channelTitle, v.thumbnail, v.publishedAt, v.subs, now,
            listOf(Snapshot(now, v.views, v.likes, v.comments)))
        val list = watchlist.value + t
        prefs.saveWatchlist(list); watchlist.value = list
        watchMsg.value = "Tracking shuru: har ${prefs.intervalMin} min me stats save honge"
    }

    fun untrack(id: String) {
        val list = watchlist.value.filterNot { it.id == id }
        prefs.saveWatchlist(list); watchlist.value = list
    }

    fun trackByUrl(input: String) {
        val id = YouTubeApi.extractId(input) ?: run { watchMsg.value = "Valid YouTube link nahi hai"; return }
        if (isTracked(id)) { watchMsg.value = "Ye video already track ho raha hai"; return }
        watchBusy.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { YouTubeApi.withSubs(prefs.apiKey, YouTubeApi.videosByIds(prefs.apiKey, listOf(id))) }
            }.onSuccess { v -> v.firstOrNull()?.let(::track) ?: run { watchMsg.value = "Video nahi mila" } }
                .onFailure { watchMsg.value = it.message }
            watchBusy.value = false
        }
    }

    fun refreshWatchlist() {
        if (watchBusy.value) return
        watchBusy.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { Tracker.refresh(prefs) } }
                .onSuccess { (list, _) -> watchlist.value = list; watchMsg.value = "Stats updated" }
                .onFailure { watchMsg.value = it.message }
            watchBusy.value = false
        }
    }

    fun saveSettings(key: String, interval: Int, threshold: Int, niche: String, nicheHours: Int) {
        val keyChanged = key.trim() != prefs.apiKey
        prefs.apiKey = key; prefs.intervalMin = interval; prefs.alertThreshold = threshold
        prefs.nicheKeywords = niche; prefs.nicheHoursBack = nicheHours
        hasKey.value = prefs.apiKey.isNotBlank()
        if (hasKey.value) {
            TrackerWorker.schedule(getApplication(), interval)
            if (keyChanged || trending.value.items.isEmpty()) { refreshTrending(); loadCategories() }
        }
    }
}
