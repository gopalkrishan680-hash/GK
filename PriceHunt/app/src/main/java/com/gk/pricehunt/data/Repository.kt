package com.gk.pricehunt.data

import android.content.Context
import com.gk.pricehunt.data.db.AppDatabase
import com.gk.pricehunt.data.db.PricePoint
import com.gk.pricehunt.data.db.ProductEntity
import com.gk.pricehunt.data.db.RecentSearch
import com.gk.pricehunt.scrape.Matcher
import com.gk.pricehunt.scrape.WebScraper
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class Repository(context: Context) {
    val dao = AppDatabase.get(context).dao()
    val scraper = WebScraper(context)

    sealed interface SearchOutcome {
        data class Ok(val items: List<ProductResult>) : SearchOutcome
        data object Blocked : SearchOutcome
        data class Error(val message: String) : SearchOutcome
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SearchOutcome.Ok>>()

    /** Search one store (cached for 15 min). Results are also saved so their prices start building history. */
    suspend fun searchStore(store: Store, query: String, useCache: Boolean = true): SearchOutcome {
        val ck = store.id + "|" + query.lowercase().trim()
        if (useCache) cache[ck]?.let { (t, r) -> if (System.currentTimeMillis() - t < 15 * 60_000) return r }
        val r = searchStoreUncached(store, query)
        if (r is SearchOutcome.Ok && r.items.isNotEmpty()) cache[ck] = System.currentTimeMillis() to r
        return r
    }

    data class CompareRow(val store: Store, val status: StoreStatus, val match: ProductResult? = null)

    /**
     * Looks for the same item (by title) on [stores] and emits the growing result map
     * as every store answers. Used by the product screen and the on-screen bubble.
     */
    fun compareFlow(title: String, stores: Collection<Store>): Flow<Map<Store, CompareRow>> = channelFlow {
        val query = Matcher.queryFromTitle(title)
        val state = MutableStateFlow(stores.associateWith { CompareRow(it, StoreStatus.Loading) })
        send(state.value)
        coroutineScope {
            stores.forEach { store ->
                launch {
                    val row = when (val r = searchStore(store, query)) {
                        is SearchOutcome.Ok -> {
                            val best = bestMatch(r.items, title)
                            CompareRow(store, StoreStatus.Done(if (best == null) 0 else 1), best)
                        }
                        SearchOutcome.Blocked -> CompareRow(store, StoreStatus.Blocked)
                        is SearchOutcome.Error -> CompareRow(store, StoreStatus.Failed(r.message))
                    }
                    state.update { it + (store to row) }
                    send(state.value)
                }
            }
        }
    }

    /** Best listing for a given product title: high word overlap, no variant conflict, then cheapest. */
    fun bestMatch(items: List<ProductResult>, refTitle: String): ProductResult? {
        val q = Matcher.queryFromTitle(refTitle)
        val scored = items.map { it to Matcher.score(q, it.title) }.filter { it.second >= 0.6 }
        if (scored.isEmpty()) return null
        val top = scored.maxOf { it.second }
        return scored.filter { it.second >= top - 0.08 }
            .sortedWith(compareBy<Pair<ProductResult, Double>> { it.first.sponsored }.thenBy { it.first.price })
            .first().first
    }

    private suspend fun searchStoreUncached(store: Store, query: String): SearchOutcome {
        val (json, _) = runCatching { scraper.search(store.id, store.productLinkRegex, store.searchUrl(query)) }
            .getOrElse { return SearchOutcome.Error(it.message ?: "error") }
        json ?: return SearchOutcome.Error("Page did not load")
        val arr = json.optJSONArray("items")
        if ((arr == null || arr.length() == 0) && json.optBoolean("blocked")) return SearchOutcome.Blocked
        val out = ArrayList<ProductResult>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: continue
            val key = store.productKey(url) ?: o.optString("key").ifBlank { null } ?: continue
            val title = o.optString("title").trim().takeIf { it.length >= 3 } ?: continue
            val price = o.optDouble("price", Double.NaN).takeIf { !it.isNaN() && it > 0 } ?: continue
            out += ProductResult(
                store = store, key = key, title = title, url = url, price = price,
                mrp = o.optDouble("mrp", Double.NaN).takeIf { !it.isNaN() && it > price },
                imageUrl = o.optString("image").takeIf { it.startsWith("http") },
                rating = o.optDouble("rating", Double.NaN).takeIf { !it.isNaN() && it in 0.5..5.0 },
                sponsored = o.optBoolean("sponsored"),
                relevance = Matcher.score(query, title),
            )
        }
        out.forEach { save(it) }
        return SearchOutcome.Ok(out)
    }

    private suspend fun save(r: ProductResult) {
        val now = System.currentTimeMillis()
        val old = dao.get(r.id)
        dao.upsert(
            ProductEntity(
                id = r.id, store = r.store.id, url = r.url, title = r.title,
                imageUrl = r.imageUrl ?: old?.imageUrl, price = r.price, mrp = r.mrp,
                rating = r.rating ?: old?.rating,
                tracked = old?.tracked ?: false, targetPrice = old?.targetPrice,
                firstSeen = old?.firstSeen ?: now, lastChecked = now, trackedSince = old?.trackedSince,
            )
        )
        addPoint(r.id, r.price, now)
    }

    /** Store a history point when the price changed, or at most once every 6 hours otherwise. */
    private suspend fun addPoint(id: String, price: Double, now: Long) {
        val last = dao.lastPoint(id)
        if (last == null || last.price != price || now - last.time > 6 * 3600_000L) {
            dao.insertPoint(PricePoint(productId = id, price = price, time = now))
        }
    }

    data class PageResult(val product: ProductEntity?, val error: String?)

    /**
     * Opens a product page (also works for short share links like amzn.in / fkrt.it),
     * reads its current price and stores it.
     */
    suspend fun fetchProductPage(url: String, knownId: String? = null): PageResult {
        val (json, finalUrl) = runCatching { scraper.product(url) }.getOrElse { return PageResult(null, it.message) }
        val resolved = finalUrl ?: url
        val store = Store.fromUrl(resolved) ?: Store.fromUrl(url)
            ?: return PageResult(null, "This store is not supported yet")
        val key = store.productKey(resolved) ?: store.productKey(url) ?: knownId?.substringAfter(':')
            ?: return PageResult(null, "Couldn't recognise a product page")
        val id = knownId ?: productId(store, key)
        val old = dao.get(id)
        if (json == null) return PageResult(old, "Page did not load")
        val price = json.optDouble("price", Double.NaN)
        if (price.isNaN() || price <= 0) {
            val err = if (json.optBoolean("blocked")) "Store asked for a captcha – try again later"
            else if (!json.optBoolean("inStock", true)) "Out of stock" else "Price not found on page"
            old?.let { dao.upsert(it.copy(lastError = err, inStock = json.optBoolean("inStock", true), lastChecked = System.currentTimeMillis())) }
            return PageResult(old, err)
        }
        val now = System.currentTimeMillis()
        val entity = ProductEntity(
            id = id, store = store.id,
            url = old?.url ?: canonical(store, key, resolved),
            title = json.str("title")?.takeIf { it.length > 3 } ?: old?.title ?: "Product",
            imageUrl = json.str("image") ?: old?.imageUrl,
            price = price,
            mrp = json.optDouble("mrp", Double.NaN).takeIf { !it.isNaN() && it > price },
            rating = old?.rating,
            tracked = old?.tracked ?: false, targetPrice = old?.targetPrice,
            inStock = json.optBoolean("inStock", true),
            firstSeen = old?.firstSeen ?: now, lastChecked = now, trackedSince = old?.trackedSince,
            lastError = null,
        )
        dao.upsert(entity)
        addPoint(id, price, now)
        return PageResult(entity, null)
    }

    private fun canonical(store: Store, key: String, url: String) = when (store) {
        Store.AMAZON -> "https://www.amazon.in/dp/$key"
        else -> url.substringBefore('#')
    }

    suspend fun setTracked(id: String, tracked: Boolean, target: Double?) =
        dao.setTracked(id, tracked, if (tracked) target else null, if (tracked) System.currentTimeMillis() else null)

    suspend fun addRecentSearch(q: String) = dao.addSearch(RecentSearch(q.trim(), System.currentTimeMillis()))

    suspend fun clearHistoryData() {
        dao.deleteUntracked()
        dao.deleteOrphanPoints()
        dao.clearSearches()
    }
}

private fun JSONObject.str(k: String): String? =
    if (isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
