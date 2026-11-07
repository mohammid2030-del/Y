package com.nova.iptv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SubscriptionEntity::class,
        CategoryEntity::class,
        ContentEntity::class,
        EpgProgramEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class NovaDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun contentDao(): ContentDao
    abstract fun epgDao(): EpgDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
}
