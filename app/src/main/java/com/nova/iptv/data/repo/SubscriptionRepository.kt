package com.nova.iptv.data.repo

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.room.withTransaction
import com.nova.iptv.R
import com.nova.iptv.data.ErrorKind
import com.nova.iptv.data.NovaException
import com.nova.iptv.data.db.CategoryEntity
import com.nova.iptv.data.db.ContentDao
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.EpgDao
import com.nova.iptv.data.db.EpgProgramEntity
import com.nova.iptv.data.db.FavoriteDao
import com.nova.iptv.data.db.HistoryDao
import com.nova.iptv.data.db.NovaDatabase
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.db.SubscriptionDao
import com.nova.iptv.data.db.SubscriptionEntity
import com.nova.iptv.data.network.HttpFetcher
import com.nova.iptv.data.network.XAuth
import com.nova.iptv.data.network.XCategory
import com.nova.iptv.data.network.XtreamParser
import com.nova.iptv.data.network.XtreamUrls
import com.nova.iptv.data.parser.M3uEntry
import com.nova.iptv.data.parser.M3uParser
import com.nova.iptv.data.parser.XmltvParser
import com.nova.iptv.data.security.CryptoManager
import com.nova.iptv.data.toNovaException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncStage { CONNECTING, CATEGORIES, CONTENT, LIVE, MOVIES, SERIES, PLAYLIST, EPG, SAVING, DONE }

data class SyncProgress(val stage: SyncStage, val percent: Int, val count: Int = 0)

data class SubscriptionInput(
    val id: Long = 0,
    val name: String = "",
    val type: String = SourceType.XTREAM,
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val fileUri: String = "",
    val epgUrl: String = "",
    val userAgent: String = "",
    val liveFormat: String = "ts",
)

data class TestResult(
    val ok: Boolean,
    val error: NovaException? = null,
    val auth: XAuth? = null,
    val sampleCount: Int = 0,
)

data class XtreamCreds(val server: String, val user: String, val pass: String)

private class StopParsing : RuntimeException()

@Singleton
class SubscriptionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: NovaDatabase,
    private val subDao: SubscriptionDao,
    private val contentDao: ContentDao,
    private val epgDao: EpgDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val fetcher: HttpFetcher,
    private val crypto: CryptoManager,
    private val settings: SettingsRepository,
) {
    private val syncMutex = Mutex()
    private val epgMutex = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeAll(): Flow<List<SubscriptionEntity>> = subDao.observeAll()
    fun observe(id: Long): Flow<SubscriptionEntity?> = subDao.observe(id)
    suspend fun get(id: Long): SubscriptionEntity? = subDao.get(id)
    suspend fun getAll(): List<SubscriptionEntity> = subDao.getAll()

    fun creds(sub: SubscriptionEntity) = XtreamCreds(sub.serverUrl, sub.username, crypto.decrypt(sub.secretCipher))
    fun epgUrl(sub: SubscriptionEntity): String = crypto.decrypt(sub.epgUrlCipher)

    fun toInput(sub: SubscriptionEntity): SubscriptionInput {
        val secret = crypto.decrypt(sub.secretCipher)
        return SubscriptionInput(
            id = sub.id,
            name = sub.name,
            type = sub.type,
            server = sub.serverUrl,
            username = sub.username,
            password = if (sub.type == SourceType.XTREAM) secret else "",
            m3uUrl = if (sub.type == SourceType.M3U_URL) secret else "",
            fileUri = if (sub.type == SourceType.M3U_FILE) secret else "",
            epgUrl = crypto.decrypt(sub.epgUrlCipher),
            userAgent = sub.userAgent,
            liveFormat = sub.liveFormat,
        )
    }

    suspend fun save(input: SubscriptionInput): Long = withContext(Dispatchers.IO) {
        val secret = when (input.type) {
            SourceType.XTREAM -> input.password
            SourceType.M3U_URL -> input.m3uUrl.trim()
            else -> input.fileUri
        }
        val server = if (input.type == SourceType.XTREAM) XtreamUrls.normalizeServer(input.server) else ""
        val name = input.name.trim().ifBlank {
            when (input.type) {
                SourceType.XTREAM -> Uri.parse(server).host ?: "Xtream"
                SourceType.M3U_URL -> Uri.parse(input.m3uUrl.trim()).host ?: "M3U"
                else -> "M3U"
            }
        }
        val existing = if (input.id != 0L) subDao.get(input.id) else null
        val entity = (existing ?: SubscriptionEntity(name = name, type = input.type)).copy(
            name = name,
            type = input.type,
            serverUrl = server,
            username = if (input.type == SourceType.XTREAM) input.username.trim() else "",
            secretCipher = crypto.encrypt(secret),
            epgUrlCipher = crypto.encrypt(input.epgUrl.trim()),
            userAgent = input.userAgent.trim(),
            liveFormat = input.liveFormat,
        )
        val id = if (existing != null) {
            subDao.update(entity); entity.id
        } else {
            subDao.insert(entity)
        }
        val s = settings.current()
        if (s.activeSubscriptionId == 0L || subDao.get(s.activeSubscriptionId) == null) {
            settings.setActiveSubscription(id)
        }
        id
    }

    suspend fun delete(sub: SubscriptionEntity) = withContext(Dispatchers.IO) {
        db.withTransaction {
            contentDao.deleteAllContent(sub.id)
            contentDao.deleteAllCategories(sub.id)
            epgDao.deleteBySub(sub.id)
            favoriteDao.deleteBySub(sub.id)
            historyDao.clear(sub.id)
            subDao.delete(sub)
        }
        if (settings.current().activeSubscriptionId == sub.id) {
            settings.setActiveSubscription(subDao.getAll().firstOrNull()?.id ?: 0L)
        }
    }

    /** Validates credentials / playlist without touching stored content. */
    suspend fun test(input: SubscriptionInput): TestResult = withContext(Dispatchers.IO) {
        try {
            when (input.type) {
                SourceType.XTREAM -> {
                    val server = XtreamUrls.normalizeServer(input.server)
                    if (server.isBlank() || input.username.isBlank()) throw NovaException(ErrorKind.INVALID_URL)
                    val auth = fetcher.text(XtreamUrls.api(server, input.username.trim(), input.password), input.userAgent) {
                        XtreamParser.parseAuth(it)
                    }
                    when {
                        !auth.authenticated -> TestResult(false, NovaException(ErrorKind.AUTH_FAILED), auth)
                        auth.status.isNotEmpty() && !auth.status.equals("Active", true) ->
                            TestResult(false, NovaException(ErrorKind.EXPIRED), auth)
                        else -> TestResult(true, auth = auth)
                    }
                }
                SourceType.M3U_URL -> {
                    val n = fetcher.bytes(input.m3uUrl.trim(), input.userAgent) { sampleM3u(it) }
                    TestResult(true, sampleCount = n)
                }
                else -> {
                    val n = openFile(input.fileUri).use { sampleM3u(it) }
                    TestResult(true, sampleCount = n)
                }
            }
        } catch (e: Exception) {
            TestResult(false, e.toNovaException())
        }
    }

    private fun sampleM3u(stream: InputStream): Int {
        var n = 0
        try {
            M3uParser.parse(BufferedReader(InputStreamReader(stream, Charsets.UTF_8))) {
                n++
                if (n >= 25) throw StopParsing()
            }
        } catch (e: StopParsing) {
            // enough entries to prove the playlist is valid
        } catch (e: IllegalStateException) {
            throw NovaException(ErrorKind.NOT_PLAYLIST)
        }
        if (n == 0) throw NovaException(ErrorKind.NOT_PLAYLIST)
        return n
    }

    private fun openFile(uri: String): InputStream = try {
        context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw NovaException(ErrorKind.FILE)
    } catch (e: NovaException) {
        throw e
    } catch (e: Exception) {
        throw NovaException(ErrorKind.FILE, cause = e)
    }

    /** Full refresh of a subscription. Existing content stays intact if anything fails. */
    suspend fun sync(subId: Long, onProgress: (SyncProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val sub = subDao.get(subId) ?: throw NovaException(ErrorKind.UNKNOWN)
            try {
                val hidden = mapOf(
                    ContentType.LIVE to contentDao.hiddenCategoryIds(sub.id, ContentType.LIVE).toHashSet(),
                    ContentType.MOVIE to contentDao.hiddenCategoryIds(sub.id, ContentType.MOVIE).toHashSet(),
                    ContentType.SERIES to contentDao.hiddenCategoryIds(sub.id, ContentType.SERIES).toHashSet(),
                )
                var expires = sub.expiresAt
                var maxConn = sub.maxConnections
                var advertisedEpg = ""
                val bundle = if (sub.type == SourceType.XTREAM) {
                    val (b, auth) = loadXtream(sub, hidden, onProgress)
                    expires = auth.expiresAtMs
                    maxConn = auth.maxConnections
                    b
                } else {
                    val (b, epg) = loadM3u(sub, hidden, onProgress)
                    advertisedEpg = epg
                    b
                }

                onProgress(SyncProgress(SyncStage.SAVING, 80, bundle.content.size))
                db.withTransaction {
                    contentDao.deleteAllContent(sub.id)
                    contentDao.deleteAllCategories(sub.id)
                    bundle.categories.chunked(500).forEach { contentDao.insertCategories(it) }
                    bundle.content.chunked(1000).forEach { contentDao.insertContent(it) }
                }

                val epgUrl = crypto.decrypt(sub.epgUrlCipher).ifBlank {
                    if (sub.type == SourceType.XTREAM) {
                        val c = creds(sub); XtreamUrls.xmltv(c.server, c.user, c.pass)
                    } else advertisedEpg
                }

                subDao.update(
                    sub.copy(
                        lastSync = System.currentTimeMillis(),
                        lastStatus = "OK",
                        expiresAt = expires,
                        maxConnections = maxConn,
                    )
                )
                onProgress(SyncProgress(SyncStage.DONE, 100, bundle.content.size))

                // The TV guide can be huge; load it in the background so the user gets
                // into the app immediately. A broken guide never fails the subscription.
                if (epgUrl.isNotBlank()) {
                    backgroundScope.launch {
                        epgMutex.withLock { runCatching { syncEpg(sub, epgUrl) {} } }
                    }
                }
            } catch (e: Exception) {
                val ne = e.toNovaException()
                subDao.update(sub.copy(lastStatus = ne.kind.name))
                throw ne
            }
        }
    }

    /** Refreshes only the EPG of a subscription. */
    suspend fun syncEpgOnly(subId: Long) = withContext(Dispatchers.IO) {
        val sub = subDao.get(subId) ?: return@withContext
        val url = crypto.decrypt(sub.epgUrlCipher).ifBlank {
            if (sub.type == SourceType.XTREAM) {
                val c = creds(sub); XtreamUrls.xmltv(c.server, c.user, c.pass)
            } else ""
        }
        if (url.isNotBlank()) epgMutex.withLock { syncEpg(sub, url) {} }
    }

    private class Bundle(val categories: List<CategoryEntity>, val content: List<ContentEntity>)

    private fun uncategorizedName() = context.getString(R.string.uncategorized)

    private fun buildCategories(
        subId: Long,
        type: String,
        remote: List<XCategory>,
        items: List<ContentEntity>,
        hidden: Set<String>,
    ): List<CategoryEntity> {
        val out = ArrayList<CategoryEntity>(remote.size + 1)
        val known = HashSet<String>()
        remote.forEachIndexed { i, c ->
            if (known.add(c.id)) out += CategoryEntity(
                subscriptionId = subId, type = type, remoteId = c.id, name = c.name,
                hidden = c.id in hidden, sortOrder = i,
            )
        }
        val orphan = items.asSequence().map { it.categoryId }.filter { it !in known }.toSet()
        orphan.forEachIndexed { i, id ->
            out += CategoryEntity(
                subscriptionId = subId, type = type, remoteId = id,
                name = id.ifBlank { uncategorizedName() },
                hidden = id in hidden, sortOrder = remote.size + i,
            )
        }
        return out
    }

    private suspend fun loadXtream(
        sub: SubscriptionEntity,
        hidden: Map<String, Set<String>>,
        onProgress: (SyncProgress) -> Unit,
    ): Pair<Bundle, XAuth> {
        val c = creds(sub)
        val ua = sub.userAgent
        onProgress(SyncProgress(SyncStage.CONNECTING, 3))
        val auth = fetcher.text(XtreamUrls.api(c.server, c.user, c.pass), ua) { XtreamParser.parseAuth(it) }
        if (!auth.authenticated) throw NovaException(ErrorKind.AUTH_FAILED)
        if (auth.status.isNotEmpty() && !auth.status.equals("Active", true)) throw NovaException(ErrorKind.EXPIRED)

        // Fetch everything in parallel (3 small category calls, then 3 big lists) to cut wall-clock time.
        onProgress(SyncProgress(SyncStage.CATEGORIES, 8))
        fun api(action: String) = XtreamUrls.api(c.server, c.user, c.pass, action)
        val (liveCats, vodCats, seriesCats) = coroutineScope {
            val l = async { fetcher.text(api("get_live_categories"), ua) { XtreamParser.readCategories(it) } }
            val v = async { fetcher.text(api("get_vod_categories"), ua) { XtreamParser.readCategories(it) } }
            val s = async { fetcher.text(api("get_series_categories"), ua) { XtreamParser.readCategories(it) } }
            Triple(l.await(), v.await(), s.await())
        }

        val total = AtomicInteger(0)
        val finishedLists = AtomicInteger(0)
        onProgress(SyncProgress(SyncStage.CONTENT, 10))
        fun tick() {
            val n = total.incrementAndGet()
            if (n % 2000 == 0) onProgress(SyncProgress(SyncStage.CONTENT, 10 + finishedLists.get() * 20, n))
        }
        fun listDone() {
            val d = finishedLists.incrementAndGet()
            onProgress(SyncProgress(SyncStage.CONTENT, 10 + d * 20, total.get()))
        }

        val (live, movies, series) = coroutineScope {
            val liveD = async {
                val live = ArrayList<ContentEntity>()
                fetcher.text(api("get_live_streams"), ua) { reader ->
                    XtreamParser.readObjects(reader) { m ->
                        val id = m["stream_id"] ?: return@readObjects
                        live += ContentEntity(
                            subscriptionId = sub.id,
                            type = ContentType.LIVE,
                            remoteId = id,
                            name = m["name"].orEmpty().trim(),
                            categoryId = m["category_id"].orEmpty(),
                            logo = m["stream_icon"].orEmpty(),
                            epgId = m["epg_channel_id"].orEmpty().trim().lowercase(),
                            tvArchive = m["tv_archive"] == "1",
                            archiveDays = m["tv_archive_duration"]?.toIntOrNull() ?: 0,
                            num = m["num"]?.toIntOrNull() ?: 0,
                            added = m["added"]?.toLongOrNull() ?: 0L,
                            sortOrder = live.size,
                        )
                        tick()
                    }
                }
                listDone()
                live
            }
            val moviesD = async {
                val movies = ArrayList<ContentEntity>()
                fetcher.text(api("get_vod_streams"), ua) { reader ->
                    XtreamParser.readObjects(reader) { m ->
                        val id = m["stream_id"] ?: return@readObjects
                        movies += ContentEntity(
                            subscriptionId = sub.id,
                            type = ContentType.MOVIE,
                            remoteId = id,
                            name = m["name"].orEmpty().trim(),
                            categoryId = m["category_id"].orEmpty(),
                            logo = m["stream_icon"].orEmpty(),
                            containerExt = m["container_extension"].orEmpty(),
                            rating = m["rating"].orEmpty().takeIf { it != "0" }.orEmpty(),
                            year = m["year"].orEmpty().ifBlank { m["releaseDate"].orEmpty().take(4) },
                            plot = m["plot"].orEmpty(),
                            added = m["added"]?.toLongOrNull() ?: 0L,
                            sortOrder = movies.size,
                            tmdb = (m["tmdb"] ?: m["tmdb_id"]).orEmpty().filter { it.isDigit() }.takeIf { it.isNotEmpty() && it != "0" }.orEmpty(),
                        )
                        tick()
                    }
                }
                listDone()
                movies
            }
            val seriesD = async {
                val series = ArrayList<ContentEntity>()
                fetcher.text(api("get_series"), ua) { reader ->
                    XtreamParser.readObjects(reader) { m ->
                        val id = m["series_id"] ?: return@readObjects
                        series += ContentEntity(
                            subscriptionId = sub.id,
                            type = ContentType.SERIES,
                            remoteId = id,
                            name = m["name"].orEmpty().trim(),
                            categoryId = m["category_id"].orEmpty(),
                            logo = m["cover"].orEmpty(),
                            rating = m["rating"].orEmpty().takeIf { it != "0" }.orEmpty(),
                            year = m["year"].orEmpty().ifBlank { m["releaseDate"].orEmpty().ifBlank { m["release_date"].orEmpty() }.take(4) },
                            plot = m["plot"].orEmpty(),
                            added = m["last_modified"]?.toLongOrNull() ?: 0L,
                            sortOrder = series.size,
                            tmdb = (m["tmdb"] ?: m["tmdb_id"]).orEmpty().filter { it.isDigit() }.takeIf { it.isNotEmpty() && it != "0" }.orEmpty(),
                        )
                        tick()
                    }
                }
                listDone()
                series
            }
            Triple(liveD.await(), moviesD.await(), seriesD.await())
        }

        val categories = buildCategories(sub.id, ContentType.LIVE, liveCats, live, hidden[ContentType.LIVE].orEmpty()) +
            buildCategories(sub.id, ContentType.MOVIE, vodCats, movies, hidden[ContentType.MOVIE].orEmpty()) +
            buildCategories(sub.id, ContentType.SERIES, seriesCats, series, hidden[ContentType.SERIES].orEmpty())
        return Bundle(categories, live + movies + series) to auth
    }

    private suspend fun loadM3u(
        sub: SubscriptionEntity,
        hidden: Map<String, Set<String>>,
        onProgress: (SyncProgress) -> Unit,
    ): Pair<Bundle, String> {
        val secret = crypto.decrypt(sub.secretCipher)
        if (secret.isBlank()) throw NovaException(ErrorKind.INVALID_URL)
        onProgress(SyncProgress(SyncStage.CONNECTING, 5))

        val live = ArrayList<ContentEntity>()
        val vod = ArrayList<ContentEntity>()
        val liveGroups = LinkedHashMap<String, Int>()
        val vodGroups = LinkedHashMap<String, Int>()
        val sha = MessageDigest.getInstance("SHA-1")
        val encrypt = crypto.bulkEncryptor()

        val handle: (InputStream) -> String = { stream ->
            onProgress(SyncProgress(SyncStage.PLAYLIST, 15))
            try {
                val header = M3uParser.parse(BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 64 * 1024)) { e: M3uEntry ->
                    val isVod = e.isVod
                    val groups = if (isVod) vodGroups else liveGroups
                    groups.getOrPut(e.group) { groups.size }
                    val id = sha.digest(e.url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
                    val item = ContentEntity(
                        subscriptionId = sub.id,
                        type = if (isVod) ContentType.MOVIE else ContentType.LIVE,
                        remoteId = id,
                        name = e.name,
                        categoryId = e.group,
                        logo = e.logo,
                        streamUrlCipher = encrypt(e.url),
                        containerExt = e.url.substringBefore('?').substringAfterLast('.', "").take(5),
                        epgId = e.tvgId.trim().lowercase(),
                        sortOrder = live.size + vod.size,
                        num = if (isVod) 0 else live.size + 1,
                    )
                    if (isVod) vod += item else live += item
                    val total = live.size + vod.size
                    if (total % 1000 == 0) onProgress(SyncProgress(SyncStage.PLAYLIST, 40, total))
                }
                header.epgUrl
            } catch (e: IllegalStateException) {
                throw NovaException(ErrorKind.NOT_PLAYLIST)
            }
        }

        val epg = if (sub.type == SourceType.M3U_URL) {
            fetcher.bytes(secret, sub.userAgent, handle)
        } else {
            openFile(secret).use { handle(it) }
        }
        if (live.isEmpty() && vod.isEmpty()) throw NovaException(ErrorKind.NOT_PLAYLIST)

        fun cats(type: String, groups: Map<String, Int>, h: Set<String>) = groups.map { (name, order) ->
            CategoryEntity(
                subscriptionId = sub.id, type = type, remoteId = name,
                name = name.ifBlank { uncategorizedName() }, hidden = name in h, sortOrder = order,
            )
        }
        val categories = cats(ContentType.LIVE, liveGroups, hidden[ContentType.LIVE].orEmpty()) +
            cats(ContentType.MOVIE, vodGroups, hidden[ContentType.MOVIE].orEmpty())
        return Bundle(categories, live + vod) to epg
    }

    private suspend fun syncEpg(sub: SubscriptionEntity, url: String, onProgress: (SyncProgress) -> Unit) {
        val keys = contentDao.epgIds(sub.id).toHashSet()
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        val from = now - 3 * DAY
        val to = now + 3 * DAY
        val programmes = ArrayList<EpgProgramEntity>()
        val parser = XmltvParser { Xml.newPullParser() }
        fetcher.bytes(url, sub.userAgent) { stream ->
            parser.parse(stream, from, to) { p ->
                val key = p.channel.trim().lowercase()
                if (key in keys) {
                    programmes += EpgProgramEntity(
                        subscriptionId = sub.id,
                        channelKey = key,
                        start = p.start,
                        stop = p.stop,
                        title = p.title.take(200),
                        description = p.description.take(400),
                    )
                    if (programmes.size % 5000 == 0) onProgress(SyncProgress(SyncStage.EPG, 90, programmes.size))
                }
            }
        }
        db.withTransaction {
            epgDao.deleteBySub(sub.id)
            programmes.chunked(1000).forEach { epgDao.insert(it) }
        }
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}
