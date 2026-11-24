package com.gk.pricehunt.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun get(id: String): ProductEntity?

    @Query("SELECT * FROM products WHERE id = :id")
    fun observe(id: String): Flow<ProductEntity?>

    @Query("SELECT * FROM products WHERE tracked = 1 ORDER BY trackedSince DESC")
    fun observeTracked(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE tracked = 1")
    suspend fun tracked(): List<ProductEntity>

    @Query("SELECT * FROM products ORDER BY lastChecked DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProductEntity>>

    @Upsert
    suspend fun upsert(p: ProductEntity)

    @Query("UPDATE products SET tracked = :tracked, targetPrice = :target, trackedSince = :since WHERE id = :id")
    suspend fun setTracked(id: String, tracked: Boolean, target: Double?, since: Long?)

    @Query("DELETE FROM products WHERE tracked = 0")
    suspend fun deleteUntracked()

    // --- price history ---
    @Insert
    suspend fun insertPoint(p: PricePoint)

    @Query("SELECT * FROM price_points WHERE productId = :id ORDER BY time ASC")
    fun observeHistory(id: String): Flow<List<PricePoint>>

    @Query("SELECT * FROM price_points WHERE productId = :id ORDER BY time DESC LIMIT 1")
    suspend fun lastPoint(id: String): PricePoint?

    @Query("SELECT MIN(price) FROM price_points WHERE productId = :id")
    suspend fun lowest(id: String): Double?

    @Query("DELETE FROM price_points WHERE productId NOT IN (SELECT id FROM products)")
    suspend fun deleteOrphanPoints()

    // --- recent searches ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSearch(s: RecentSearch)

    @Query("SELECT * FROM recent_searches ORDER BY time DESC LIMIT 12")
    fun observeSearches(): Flow<List<RecentSearch>>

    @Query("DELETE FROM recent_searches WHERE `query` = :q")
    suspend fun deleteSearch(q: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clearSearches()
}
