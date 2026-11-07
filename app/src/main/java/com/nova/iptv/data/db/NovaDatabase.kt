package com.nova.iptv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SubscriptionEntity::class,
        CategoryEntity::class,
        ContentEntity::class,
        EpgProgramEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class NovaDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun contentDao(): ContentDao
    abstract fun epgDao(): EpgDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
}

/** v2: indexes that make "All" lists and "Recently added" fast on large playlists. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_subscriptionId_type_sortOrder` ON `content` (`subscriptionId`, `type`, `sortOrder`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_content_subscriptionId_type_added` ON `content` (`subscriptionId`, `type`, `added`)")
    }
}
