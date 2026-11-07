package com.nova.iptv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object ContentType {
    const val LIVE = "live"
    const val MOVIE = "movie"
    const val SERIES = "series"
}

object SourceType {
    const val XTREAM = "xtream"
    const val M3U_URL = "m3u_url"
    const val M3U_FILE = "m3u_file"
}

/**
 * A provider subscription. Secrets (Xtream password, M3U URL / file URI, EPG URL)
 * are stored only in encrypted form (see CryptoManager).
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val serverUrl: String = "",
    val username: String = "",
    val secretCipher: String = "",
    val epgUrlCipher: String = "",
    val userAgent: String = "",
    val liveFormat: String = "ts",
    val lastSync: Long = 0,
    val lastStatus: String = "",
    val expiresAt: Long = 0,
    val maxConnections: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["subscriptionId", "type"])],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long,
    val type: String,
    val remoteId: String,
    val name: String,
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "content",
    indices = [
        Index(value = ["subscriptionId", "type", "categoryId"]),
        Index(value = ["subscriptionId", "type", "remoteId"]),
        Index(value = ["subscriptionId", "name"]),
        Index(value = ["subscriptionId", "type", "sortOrder"]),
        Index(value = ["subscriptionId", "type", "added"]),
    ],
)
data class ContentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long,
    val type: String,
    val remoteId: String,
    val name: String,
    val categoryId: String,
    val logo: String = "",
    /** Encrypted direct URL (M3U sources). Empty for Xtream (built at play time). */
    val streamUrlCipher: String = "",
    val containerExt: String = "",
    val epgId: String = "",
    val tvArchive: Boolean = false,
    val archiveDays: Int = 0,
    val rating: String = "",
    val year: String = "",
    val plot: String = "",
    val num: Int = 0,
    val added: Long = 0,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "epg",
    indices = [Index(value = ["subscriptionId", "channelKey", "start"])],
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long,
    val channelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String = "",
)

@Entity(tableName = "favorites", primaryKeys = ["subscriptionId", "type", "remoteId"])
data class FavoriteEntity(
    val subscriptionId: Long,
    val type: String,
    val remoteId: String,
    val name: String,
    val logo: String = "",
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

/** Watch history + resume position. historyKey = "subId|type|remoteId|episodeId". */
@Entity(tableName = "history", indices = [Index(value = ["subscriptionId", "type", "updatedAt"])])
data class HistoryEntity(
    @PrimaryKey val historyKey: String,
    val subscriptionId: Long,
    val type: String,
    val remoteId: String,
    val episodeId: String = "",
    val title: String,
    val subtitle: String = "",
    val logo: String = "",
    val containerExt: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun key(subId: Long, type: String, remoteId: String, episodeId: String = "") =
            "$subId|$type|$remoteId|$episodeId"
    }
}
