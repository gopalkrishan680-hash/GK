package com.gk.pricehunt.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gk.pricehunt.PriceHuntApp
import com.gk.pricehunt.data.ProductResult
import com.gk.pricehunt.data.Repository
import com.gk.pricehunt.data.Store
import com.gk.pricehunt.data.StoreStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortMode(val label: String) {
    RELEVANCE("Best match"), PRICE_LOW("Price: low to high"), PRICE_HIGH("Price: high to low"),
    DISCOUNT("Biggest discount"), RATING("Top rated"),
}

data class SearchState(
    val query: String = "",
    val statuses: Map<Store, StoreStatus> = emptyMap(),
    val results: List<ProductResult> = emptyList(),
) {
    val loading get() = statuses.values.any { it is StoreStatus.Loading || it is StoreStatus.Waiting }
    val doneCount get() = statuses.values.count { it !is StoreStatus.Loading && it !is StoreStatus.Waiting }

    /** The single most relevant listing from each store → the "same item" comparison. */
    val bestPerStore: List<ProductResult>
        get() = results.filter { it.relevance >= MATCH_THRESHOLD }
            .groupBy { it.store }
            .mapNotNull { (_, list) ->
                val top = list.maxOf { it.relevance }
                // among equally good matches prefer non-sponsored & cheaper
                list.filter { it.relevance >= top - 0.05 }
                    .sortedWith(compareBy<ProductResult> { it.sponsored }.thenBy { it.price })
                    .firstOrNull()
            }
            .sortedBy { it.price }

    companion object { const val MATCH_THRESHOLD = 0.6 }
}

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: Repository = (app as PriceHuntApp).repo
    private val settings = app.let { (it as PriceHuntApp).settings }
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state
    private var job: Job? = null

    fun searchIfNew(query: String) {
        if (_state.value.query == query && _state.value.statuses.isNotEmpty()) return
        search(query)
    }

    fun search(query: String, onlyStore: Store? = null, fresh: Boolean = false) {
        val stores = onlyStore?.let { listOf(it) } ?: settings.enabledStores.value.sortedBy { it.ordinal }
        if (onlyStore == null) {
            job?.cancel()
            _state.value = SearchState(query = query, statuses = stores.associateWith { StoreStatus.Waiting })
            viewModelScope.launch { repo.addRecentSearch(query) }
        } else {
            _state.update { s -> s.copy(results = s.results.filter { it.store != onlyStore }) }
        }
        val parent = viewModelScope.launch {
            stores.forEach { store ->
                launch {
                    setStatus(store, StoreStatus.Loading)
                    when (val r = repo.searchStore(store, query, useCache = !fresh && onlyStore == null)) {
                        is Repository.SearchOutcome.Ok -> {
                            val items = if (settings.hideSponsored) r.items.filter { !it.sponsored } else r.items
                            _state.update { it.copy(results = it.results + items) }
                            setStatus(store, StoreStatus.Done(items.size))
                        }
                        Repository.SearchOutcome.Blocked -> setStatus(store, StoreStatus.Blocked)
                        is Repository.SearchOutcome.Error -> setStatus(store, StoreStatus.Failed(r.message))
                    }
                }
            }
        }
        if (onlyStore == null) job = parent
    }

    private fun setStatus(store: Store, s: StoreStatus) =
        _state.update { it.copy(statuses = it.statuses + (store to s)) }
}
