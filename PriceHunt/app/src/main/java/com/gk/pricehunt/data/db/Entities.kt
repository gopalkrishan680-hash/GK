package com.gk.pricehunt.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val store: String,
    val url: String,
    val title: String,
    val imageUrl: String?,
    val price: Double,
    val mrp: Double?,
    val rating: Double?,
    val tracked: Boolean = false,
    val targetPrice: Double? = null,
    val inStock: Boolean = true,
    val firstSeen: Long,
    val lastChecked: Long,
    val trackedSince: Long? = null,
    val lastError: String? = null,
)

@Entity(tableName = "price_points", indices = [Index("productId")])
data class PricePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    val price: Double,
    val time: Long,
)

@Entity(tableName = "recent_searches")
data class RecentSearch(
    @PrimaryKey val query: String,
    val time: Long,
)
