package com.nova.iptv.data.repo

import android.util.LruCache
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.nova.iptv.data.ErrorKind
import com.nova.iptv.data.NovaException
import com.nova.iptv.data.db.CategoryEntity
import com.nova.iptv.data.db.ContentDao
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.EpgDao
import com.nova.iptv.data.db.EpgProgramEntity
import com.nova.iptv.data.db.FavoriteDao
import com.nova.iptv.data.db.FavoriteEntity
import com.nova.iptv.data.db.HistoryDao
import com.nova.iptv.data.db.HistoryEntity
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.db.SubscriptionEntity
import com.nova.iptv.data.network.HttpFetcher
import com.nova.iptv.data.network.XSeriesInfo
import com.nova.iptv.data.network.XVodInfo
import com.nova.iptv.data.network.XtreamParser
import com.nova.iptv.data.network.XtreamUrls
import com.nova.iptv.data.security.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackRequest(
    val subscriptionId: Long,
    val type: String,
    val remoteId: String,
    val title: String,
    val logo: String = "",
    val episodeId: String = "",
    val episodeExt: String = "",
    val subtitle: String = "",
    val categoryId: String = "",
    val catchupStartMs: Long = 0,
    val catchupDurationMin: Int = 0,
)

data class EpisodeContext(
    val tmdbId: String,
    val current: com.nova.iptv.data.network.XEpisode,
    val next: com.nova.iptv.data.network.XEpisode?,
)

data class ResolvedStream(val url: String, val userAgent: String, val isLive: Boolean)

const val RECENT_ADDED = "\u0001recent_added"

@Singleton
class ContentRepository @Inject constructor(
    private val contentDao: ContentDao,
    private val epgDao: EpgDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val subs: SubscriptionRepository,
    private val fetcher: HttpFetcher,
    private val crypto: CryptoManager,
) {
    private val seriesCache = LruCache<String, XSeriesInfo>(20)
    private val vodCache = LruCache<String, XVodInfo>(50)

    fun categories(subId: Long, type: String): Flow<List<CategoryEntity>> = contentDao.observeCategories(subId, type)
    fun visibleCategories(subId: Long, type: String): Flow<List<CategoryEntity>> =
        contentDao.observeCategories(subId, type).map { list -> list.filter { !it.hidden } }
    fun allCategories(subId: Long): Flow<List<CategoryEntity>> = contentDao.observeAllCategories(subId)
    suspend fun setCategoryHidden(id: Long, hidden: Boolean) = contentDao.setHidden(id, hidden)

    fun byCategory(subId: Long, type: String, categoryId: String) = contentDao.observeByCategory(subId, type, categoryId)
    fun all(subId: Long, type: String, limit: Int = 5000) = contentDao.observeAll(subId, type, limit)
    fun recentlyAdded(subId: Long, type: String, limit: Int = 30) = contentDao.observeRecentlyAdded(subId, type, limit)
    private val pagingConfig = PagingConfig(pageSize = 60, prefetchDistance = 90, initialLoadSize = 120, enablePlaceholders = false)

    /** categoryId: real category id, "" for all (minus hidden), or RECENT_ADDED. */
    fun paged(subId: Long, type: String, categoryId: String): Flow<PagingData<ContentEntity>> =
        Pager(pagingConfig) {
            when (categoryId) {
                "" -> contentDao.pageAll(subId, type)
                RECENT_ADDED -> contentDao.pageRecentlyAdded(subId, type)
                else -> contentDao.pageByCategory(subId, type, categoryId)
            }
        }.flow

    fun row(subId: Long, type: String, categoryId: String, limit: Int = 20) = contentDao.observeRow(subId, type, categoryId, limit)
    fun categoryCount(subId: Long, type: String, categoryId: String) = contentDao.observeCategoryCount(subId, type, categoryId)

    suspend fun byId(id: Long) = contentDao.byId(id)
    suspend fun byRemote(subId: Long, type: String, remoteId: String) = contentDao.byRemote(subId, type, remoteId)
    suspend fun search(subId: Long, query: String, type: String, categoryId: String) =
        contentDao.search(subId, type, categoryId, query.trim())

    suspend fun channelsFor(subId: Long, categoryId: String): List<ContentEntity> =
        if (categoryId.isNotEmpty()) contentDao.liveInCategory(subId, categoryId) else contentDao.allLive(subId)

    // ---- EPG ----
    suspend fun nowPlaying(subId: Long, offsetMs: Long): Map<String, EpgProgramEntity> {
        val now = System.currentTimeMillis() - offsetMs
        return epgDao.nowPlaying(subId, now).associateBy { it.channelKey }
    }

    suspend fun programmes(subId: Long, key: String, from: Long, to: Long) = epgDao.forChannel(subId, key, from, to)

    suspend fun programmesFor(subId: Long, keys: List<String>, from: Long, to: Long): Map<String, List<EpgProgramEntity>> {
        if (keys.isEmpty()) return emptyMap()
        val out = ArrayList<EpgProgramEntity>()
        keys.distinct().chunked(400).forEach { out += epgDao.forChannels(subId, it, from, to) }
        return out.groupBy { it.channelKey }
    }

    // ---- Favorites ----
    fun favorites(subId: Long): Flow<List<FavoriteEntity>> = favoriteDao.observe(subId)
    fun favoriteKeys(subId: Long): Flow<Set<String>> =
        favoriteDao.observe(subId).map { list -> list.map { "${it.type}|${it.remoteId}" }.toSet() }

    suspend fun toggleFavorite(subId: Long, type: String, remoteId: String, name: String, logo: String) {
        if (favoriteDao.exists(subId, type, remoteId) > 0) {
            favoriteDao.delete(subId, type, remoteId)
        } else {
            val pos = (favoriteDao.maxPosition(subId) ?: 0) + 1
            favoriteDao.upsert(FavoriteEntity(subId, type, remoteId, name, logo, pos))
        }
    }

    suspend fun removeFavorite(f: FavoriteEntity) = favoriteDao.delete(f.subscriptionId, f.type, f.remoteId)

    suspend fun moveFavorite(list: List<FavoriteEntity>, from: Int, to: Int) {
        if (from !in list.indices || to !in list.indices) return
        val m = list.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        favoriteDao.updateAll(m.mapIndexed { i, f -> f.copy(position = i) })
    }

    // ---- History / resume ----
    fun recent(subId: Long, type: String, limit: Int = 20) = historyDao.observeRecent(subId, type, limit)
    fun continueWatching(subId: Long) = historyDao.observeContinue(subId)
    fun seriesHistory(subId: Long, seriesId: String) = historyDao.observeSeries(subId, seriesId)
    fun movieHistory(subId: Long, movieId: String) = historyDao.observeMovie(subId, movieId)
    suspend fun history(key: String) = historyDao.get(key)
    suspend fun saveHistory(item: HistoryEntity) = historyDao.upsert(item)
    suspend fun clearHistory(subId: Long) = historyDao.clear(subId)
    suspend fun clearAllHistory() = historyDao.clearAll()

    // ---- Details (Xtream) ----
    suspend fun seriesInfo(sub: SubscriptionEntity, seriesId: String, force: Boolean = false): XSeriesInfo {
        val cacheKey = "${sub.id}|$seriesId"
        if (!force) seriesCache.get(cacheKey)?.let { return it }
        if (sub.type != SourceType.XTREAM) throw NovaException(ErrorKind.UNKNOWN)
        val c = subs.creds(sub)
        val info = fetcher.text(
            XtreamUrls.api(c.server, c.user, c.pass, "get_series_info", "series_id=$seriesId"), sub.userAgent,
        ) { XtreamParser.parseSeriesInfo(it) }
        seriesCache.put(cacheKey, info)
        return info
    }

    /** Current + next episode (across seasons) and the series' TMDB id, for skip/up-next features. */
    suspend fun episodeContext(subId: Long, seriesId: String, episodeId: String): EpisodeContext? {
        val sub = subs.get(subId) ?: return null
        if (sub.type != SourceType.XTREAM || episodeId.isEmpty()) return null
        val info = runCatching { seriesInfo(sub, seriesId) }.getOrNull() ?: return null
        val all = info.seasons.toSortedMap().values.flatten()
        val i = all.indexOfFirst { it.id == episodeId }
        if (i < 0) return null
        return EpisodeContext(info.tmdbId, all[i], all.getOrNull(i + 1))
    }

    suspend fun movieTmdbId(subId: Long, movieId: String): String {
        val sub = subs.get(subId) ?: return ""
        return vodInfo(sub, movieId)?.tmdbId.orEmpty()
    }

    suspend fun vodInfo(sub: SubscriptionEntity, streamId: String): XVodInfo? {
        if (sub.type != SourceType.XTREAM) return null
        val cacheKey = "${sub.id}|$streamId"
        vodCache.get(cacheKey)?.let { return it }
        val c = subs.creds(sub)
        return runCatching {
            fetcher.text(XtreamUrls.api(c.server, c.user, c.pass, "get_vod_info", "vod_id=$streamId"), sub.userAgent) {
                XtreamParser.parseVodInfo(it)
            }
        }.getOrNull()?.also { vodCache.put(cacheKey, it) }
    }

    // ---- Stream URL resolution (credentials only exist in memory here) ----
    suspend fun resolve(req: PlaybackRequest): ResolvedStream = withContext(Dispatchers.IO) {
        val sub = subs.get(req.subscriptionId) ?: throw NovaException(ErrorKind.UNKNOWN)
        val ua = sub.userAgent
        if (sub.type == SourceType.XTREAM) {
            val c = subs.creds(sub)
            when {
                req.catchupStartMs > 0 -> ResolvedStream(
                    XtreamUrls.timeshift(c.server, c.user, c.pass, req.remoteId, req.catchupStartMs, req.catchupDurationMin, TimeZone.getDefault()),
                    ua, false,
                )
                req.type == ContentType.LIVE -> ResolvedStream(XtreamUrls.live(c.server, c.user, c.pass, req.remoteId, sub.liveFormat), ua, true)
                req.type == ContentType.MOVIE -> {
                    val ext = contentDao.byRemote(sub.id, ContentType.MOVIE, req.remoteId)?.containerExt.orEmpty()
                    ResolvedStream(XtreamUrls.movie(c.server, c.user, c.pass, req.remoteId, ext), ua, false)
                }
                else -> ResolvedStream(XtreamUrls.episode(c.server, c.user, c.pass, req.episodeId, req.episodeExt), ua, false)
            }
        } else {
            val item = contentDao.byRemote(sub.id, req.type, req.remoteId) ?: throw NovaException(ErrorKind.UNKNOWN)
            val url = crypto.decrypt(item.streamUrlCipher)
            if (url.isBlank()) throw NovaException(ErrorKind.INVALID_URL)
            ResolvedStream(url, ua, req.type == ContentType.LIVE)
        }
    }
}
