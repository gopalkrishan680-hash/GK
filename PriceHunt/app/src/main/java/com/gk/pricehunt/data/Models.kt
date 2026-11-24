package com.gk.pricehunt.data

/** One product as found on a store (search result or product page). */
data class ProductResult(
    val store: Store,
    val key: String,
    val title: String,
    val url: String,
    val price: Double,
    val mrp: Double?,
    val imageUrl: String?,
    val rating: Double?,
    val sponsored: Boolean = false,
    /** 0..1 how well the title matches what the user searched for. */
    val relevance: Double = 0.0,
) {
    val id: String get() = productId(store, key)
    val discountPercent: Int?
        get() = mrp?.takeIf { it > price }?.let { (((it - price) / it) * 100).toInt() }
}

fun productId(store: Store, key: String) = "${store.id}:$key"

sealed interface StoreStatus {
    data object Waiting : StoreStatus
    data object Loading : StoreStatus
    data class Done(val count: Int) : StoreStatus
    data object Blocked : StoreStatus
    data class Failed(val reason: String) : StoreStatus
}
