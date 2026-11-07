package com.nova.iptv.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Insert
    suspend fun insert(sub: SubscriptionEntity): Long

    @Update
    suspend fun update(sub: SubscriptionEntity)

    @Delete
    suspend fun delete(sub: SubscriptionEntity)

    @Query("SELECT * FROM subscriptions ORDER BY createdAt")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY createdAt")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun get(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    fun observe(id: Long): Flow<SubscriptionEntity?>
}

@Dao
interface ContentDao {
    @Insert
    suspend fun insertContent(items: List<ContentEntity>)

    @Insert
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Query("DELETE FROM content WHERE subscriptionId = :subId AND type = :type")
    suspend fun deleteContent(subId: Long, type: String)

    @Query("DELETE FROM categories WHERE subscriptionId = :subId AND type = :type")
    suspend fun deleteCategories(subId: Long, type: String)

    @Query("DELETE FROM content WHERE subscriptionId = :subId")
    suspend fun deleteAllContent(subId: Long)

    @Query("DELETE FROM categories WHERE subscriptionId = :subId")
    suspend fun deleteAllCategories(subId: Long)

    @Query("SELECT remoteId FROM categories WHERE subscriptionId = :subId AND type = :type AND hidden = 1")
    suspend fun hiddenCategoryIds(subId: Long, type: String): List<String>

    @Query("SELECT * FROM categories WHERE subscriptionId = :subId AND type = :type ORDER BY sortOrder")
    fun observeCategories(subId: Long, type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE subscriptionId = :subId ORDER BY type, sortOrder")
    fun observeAllCategories(subId: Long): Flow<List<CategoryEntity>>

    @Query("UPDATE categories SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("SELECT * FROM content WHERE subscriptionId = :subId AND type = :type AND categoryId = :categoryId ORDER BY sortOrder")
    fun observeByCategory(subId: Long, type: String, categoryId: String): Flow<List<ContentEntity>>

    @Query(
        "SELECT * FROM content WHERE subscriptionId = :subId AND type = :type AND categoryId NOT IN " +
            "(SELECT remoteId FROM categories WHERE subscriptionId = :subId AND type = :type AND hidden = 1) " +
            "ORDER BY sortOrder LIMIT :limit"
    )
    fun observeAll(subId: Long, type: String, limit: Int): Flow<List<ContentEntity>>

    @Query(
        "SELECT * FROM content WHERE subscriptionId = :subId AND type = :type AND categoryId NOT IN " +
            "(SELECT remoteId FROM categories WHERE subscriptionId = :subId AND type = :type AND hidden = 1) " +
            "ORDER BY added DESC LIMIT :limit"
    )
    fun observeRecentlyAdded(subId: Long, type: String, limit: Int): Flow<List<ContentEntity>>

    @Query(
        "SELECT * FROM content WHERE subscriptionId = :subId " +
            "AND (:type = '' OR type = :type) " +
            "AND (:categoryId = '' OR categoryId = :categoryId) " +
            "AND name LIKE '%' || :query || '%' " +
            "AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.subscriptionId = content.subscriptionId " +
            "AND c.type = content.type AND c.remoteId = content.categoryId AND c.hidden = 1) " +
            "ORDER BY CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END, name LIMIT 300"
    )
    suspend fun search(subId: Long, type: String, categoryId: String, query: String): List<ContentEntity>

    @Query("SELECT * FROM content WHERE id = :id")
    suspend fun byId(id: Long): ContentEntity?

    @Query("SELECT * FROM content WHERE subscriptionId = :subId AND type = :type AND remoteId = :remoteId LIMIT 1")
    suspend fun byRemote(subId: Long, type: String, remoteId: String): ContentEntity?

    @Query("SELECT * FROM content WHERE subscriptionId = :subId AND type = 'live' AND categoryId = :categoryId ORDER BY sortOrder")
    suspend fun liveInCategory(subId: Long, categoryId: String): List<ContentEntity>

    @Query(
        "SELECT * FROM content WHERE subscriptionId = :subId AND type = 'live' AND categoryId NOT IN " +
            "(SELECT remoteId FROM categories WHERE subscriptionId = :subId AND type = 'live' AND hidden = 1) " +
            "ORDER BY sortOrder"
    )
    suspend fun allLive(subId: Long): List<ContentEntity>

    @Query("SELECT COUNT(*) FROM content WHERE subscriptionId = :subId AND type = :type")
    suspend fun count(subId: Long, type: String): Int

    @Query("SELECT DISTINCT epgId FROM content WHERE subscriptionId = :subId AND type = 'live' AND epgId != ''")
    suspend fun epgIds(subId: Long): List<String>
}

@Dao
interface EpgDao {
    @Insert
    suspend fun insert(items: List<EpgProgramEntity>)

    @Query("DELETE FROM epg WHERE subscriptionId = :subId")
    suspend fun deleteBySub(subId: Long)

    @Query("SELECT * FROM epg WHERE subscriptionId = :subId AND start <= :now AND stop > :now")
    suspend fun nowPlaying(subId: Long, now: Long): List<EpgProgramEntity>

    @Query("SELECT * FROM epg WHERE subscriptionId = :subId AND channelKey = :key AND stop > :from AND start < :to ORDER BY start")
    suspend fun forChannel(subId: Long, key: String, from: Long, to: Long): List<EpgProgramEntity>

    @Query("SELECT * FROM epg WHERE subscriptionId = :subId AND channelKey IN (:keys) AND stop > :from AND start < :to ORDER BY start")
    suspend fun forChannels(subId: Long, keys: List<String>, from: Long, to: Long): List<EpgProgramEntity>

    @Query("SELECT COUNT(*) FROM epg WHERE subscriptionId = :subId")
    suspend fun count(subId: Long): Int
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE subscriptionId = :subId ORDER BY position, addedAt")
    fun observe(subId: Long): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fav: FavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(favs: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE subscriptionId = :subId AND type = :type AND remoteId = :remoteId")
    suspend fun delete(subId: Long, type: String, remoteId: String)

    @Query("SELECT COUNT(*) FROM favorites WHERE subscriptionId = :subId AND type = :type AND remoteId = :remoteId")
    suspend fun exists(subId: Long, type: String, remoteId: String): Int

    @Query("SELECT MAX(position) FROM favorites WHERE subscriptionId = :subId")
    suspend fun maxPosition(subId: Long): Int?

    @Update
    suspend fun updateAll(favs: List<FavoriteEntity>)

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<FavoriteEntity>

    @Query("DELETE FROM favorites WHERE subscriptionId = :subId")
    suspend fun deleteBySub(subId: Long)
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HistoryEntity)

    @Query("SELECT * FROM history WHERE historyKey = :key")
    suspend fun get(key: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE subscriptionId = :subId AND type = :type ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(subId: Long, type: String, limit: Int): Flow<List<HistoryEntity>>

    @Query(
        "SELECT * FROM history WHERE subscriptionId = :subId AND type IN ('movie', 'series') " +
            "AND positionMs > 30000 AND (durationMs = 0 OR positionMs < durationMs * 0.95) " +
            "ORDER BY updatedAt DESC LIMIT 30"
    )
    fun observeContinue(subId: Long): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE subscriptionId = :subId AND type = 'series' AND remoteId = :seriesId ORDER BY updatedAt DESC")
    fun observeSeries(subId: Long, seriesId: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE subscriptionId = :subId AND type = 'movie' AND remoteId = :movieId")
    fun observeMovie(subId: Long, movieId: String): Flow<HistoryEntity?>

    @Query("DELETE FROM history WHERE subscriptionId = :subId")
    suspend fun clear(subId: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}
