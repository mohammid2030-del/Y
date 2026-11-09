@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package com.nova.iptv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nova.iptv.data.repo.RECENT_ADDED
import com.nova.iptv.data.db.CategoryEntity
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.LibItem
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.EpgProgramEntity
import com.nova.iptv.data.db.FavoriteEntity
import com.nova.iptv.data.db.HistoryEntity
import com.nova.iptv.data.db.SubscriptionEntity
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.repo.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val SHELF = "\u0001shelf:"

object SpecialCat {
    const val ALL = "\u0001all"
    const val FAV = "\u0001fav"
    const val RECENT = "\u0001recent"
}

private fun FavoriteEntity.asContent() = ContentEntity(
    subscriptionId = subscriptionId, type = type, remoteId = remoteId, name = name, categoryId = "", logo = logo,
)

private fun HistoryEntity.asContent() = ContentEntity(
    subscriptionId = subscriptionId, type = type, remoteId = remoteId, name = title, categoryId = "", logo = logo,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val subs: SubscriptionRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val subscriptions: StateFlow<List<SubscriptionEntity>> =
        subs.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun switchTo(id: Long) {
        viewModelScope.launch { settings.setActiveSubscription(id) }
    }
}

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val content: ContentRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val subId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val selected = MutableStateFlow(SpecialCat.ALL)

    val categories: StateFlow<List<CategoryEntity>> = subId
        .flatMapLatest { content.visibleCategories(it, ContentType.LIVE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteKeys: StateFlow<Set<String>> = subId
        .flatMapLatest { content.favoriteKeys(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Paged: only visible rows are read from the database. */
    val channels: Flow<PagingData<ContentEntity>> = combine(subId, selected) { s, c -> s to c }
        .flatMapLatest { (s, c) ->
            when (c) {
                SpecialCat.ALL -> content.paged(s, ContentType.LIVE, "")
                SpecialCat.FAV -> content.favorites(s).map { l -> PagingData.from(l.filter { it.type == ContentType.LIVE }.map { it.asContent() }) }
                SpecialCat.RECENT -> content.recent(s, ContentType.LIVE, 50).map { l -> PagingData.from(l.map { it.asContent() }) }
                else -> content.paged(s, ContentType.LIVE, c)
            }
        }
        .cachedIn(viewModelScope)

    val lastWatched: StateFlow<ContentEntity?> = subId
        .flatMapLatest { content.recent(it, ContentType.LIVE, 1) }
        .map { l -> l.firstOrNull()?.asContent() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val gridMode = MutableStateFlow(false)

    private val _now = MutableStateFlow<Map<String, EpgProgramEntity>>(emptyMap())
    val nowPlaying: StateFlow<Map<String, EpgProgramEntity>> = _now

    val showEpg: StateFlow<Boolean> = settings.settings.map { it.showEpgInList }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val epgOffsetMs: StateFlow<Long> = settings.settings.map { it.epgOffsetMinutes * 60_000L }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    init {
        viewModelScope.launch {
            combine(subId, epgOffsetMs) { s, o -> s to o }.collectLatest { (s, off) ->
                while (true) {
                    _now.value = runCatching { content.nowPlaying(s, off) }.getOrDefault(emptyMap())
                    delay(60_000)
                }
            }
        }
    }

    fun select(category: String) {
        selected.value = category
    }


    fun toggleFavorite(c: ContentEntity) {
        viewModelScope.launch { content.toggleFavorite(c.subscriptionId, ContentType.LIVE, c.remoteId, c.name, c.logo) }
    }

    fun rememberLastChannel(c: ContentEntity) {
        viewModelScope.launch { settings.setLastChannel("${c.subscriptionId}|${c.remoteId}") }
    }
}

abstract class VodViewModel(
    val type: String,
    private val content: ContentRepository,
    settings: SettingsRepository,
    val library: com.nova.iptv.data.library.LibraryRepository,
) : ViewModel() {
    val subId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val selected = MutableStateFlow(SpecialCat.ALL)

    /** Scroll positions kept across navigation (detail screens, category grids). */
    val discoverState = androidx.compose.foundation.lazy.LazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.LazyGridState()
    var gridCategory: String = ""

    val categories: StateFlow<List<CategoryEntity>> = subId
        .flatMapLatest { content.visibleCategories(it, type) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteKeys: StateFlow<Set<String>> = subId
        .flatMapLatest { content.favoriteKeys(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val items: Flow<PagingData<ContentEntity>> = combine(subId, selected) { s, c -> s to c }
        .flatMapLatest { (s, c) ->
            when (c) {
                SpecialCat.ALL -> content.paged(s, type, "")
                SpecialCat.FAV -> content.favorites(s).map { l -> PagingData.from(l.filter { it.type == type }.map { it.asContent() }) }
                SpecialCat.RECENT -> content.paged(s, type, RECENT_ADDED)
                else -> content.paged(s, type, c)
            }
        }
        .cachedIn(viewModelScope)

    val continueWatching: StateFlow<List<HistoryEntity>> = subId
        .flatMapLatest { content.continueWatching(it) }
        .map { l -> l.filter { it.type == type } }
        .map { l -> l.distinctBy { "${it.subscriptionId}|${it.type}|${it.remoteId}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Hero carousel: newest titles that have artwork. */
    val featured: StateFlow<List<ContentEntity>> = subId
        .flatMapLatest { content.recentlyAdded(it, type, 40) }
        .map { l -> l.filter { it.logo.isNotBlank() }.take(8) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentRow: StateFlow<List<ContentEntity>> = subId
        .flatMapLatest { content.recentlyAdded(it, type, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun heroInfo(c: ContentEntity) = content.heroInfo(c)

    suspend fun backdropOf(h: HistoryEntity): String? = content.heroInfo(h.asContent())?.backdrop?.takeIf { it.isNotBlank() }

    /** Items of one category row (collected only while the row is on screen). */
    // Rows are cached as StateFlows so that, when you come back from a title, every row already has its
    // items — otherwise rows start empty, the list becomes shorter than the screen and jumps to the top.
    private val rowFlows = HashMap<String, StateFlow<List<ContentEntity>>>()
    private val countFlows = HashMap<String, StateFlow<Int>>()

    fun row(categoryId: String): StateFlow<List<ContentEntity>> = rowFlows.getOrPut(categoryId) {
        subId.flatMapLatest { content.row(it, type, categoryId, 20) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun count(categoryId: String): StateFlow<Int> = countFlows.getOrPut(categoryId) {
        subId.flatMapLatest { content.categoryCount(it, type, categoryId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    }

    fun toggleFavorite(c: ContentEntity) {
        viewModelScope.launch { content.toggleFavorite(c.subscriptionId, type, c.remoteId, c.name, c.logo) }
    }

    fun select(category: String) {
        selected.value = category
    }

    // ---- Netflix-style library: shelves (platforms / regions), one card per title, genre filters

    val index: StateFlow<com.nova.iptv.data.library.LibraryRepository.Index?> = subId
        .flatMapLatest { library.indexFlow(it, type) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Bumps when TMDB identified more titles (copies merge, genres become known). */
    val libraryTick = MutableStateFlow(0)

    /** Discover rows: each shelf with its newest titles. */
    val shelfRows: StateFlow<List<Pair<com.nova.iptv.data.library.Shelf, List<LibItem>>>> = combine(index, libraryTick) { idx, _ -> idx }
        .map { idx ->
            if (idx == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                idx.shelves.map { sh -> sh to library.collapse(idx, idx.items.filter { idx.shelfOfCategory[it.categoryId] == sh.id }.sortedByDescending { it.added }.take(80)).take(20) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Current shelf browser state. */
    val libCategory = MutableStateFlow<String?>(null)
    val libGenre = MutableStateFlow<com.nova.iptv.data.library.Genre?>(null)
    val libGridState = androidx.compose.foundation.lazy.grid.LazyGridState()

    val libItems: StateFlow<List<LibItem>?> = combine(index, selected, libCategory, libGenre, libraryTick) { idx, sel, cat, g, _ ->
        arrayOf(idx, sel, cat, g)
    }.map { a ->
        val idx = a[0] as? com.nova.iptv.data.library.LibraryRepository.Index ?: return@map null
        val sel = a[1] as String
        if (!sel.startsWith(SHELF)) return@map null
        val g = a[3] as? com.nova.iptv.data.library.Genre
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val list = library.browse(idx, sel.removePrefix(SHELF), a[2] as String?)
            if (g == null) list else list.filter { library.matchesGenre(idx, it, g) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var identifying: kotlinx.coroutines.Job? = null
    /** Looks up the titles on screen with TMDB (once), then merges copies / applies genres. */
    fun identify(list: List<LibItem>) {
        val idx = index.value ?: return
        identifying?.cancel()
        identifying = viewModelScope.launch {
            val before = library.tmdbVersion()
            library.identify(idx, list)
            if (library.tmdbVersion() != before) libraryTick.value++
        }
    }

    fun openShelf(id: String) {
        libCategory.value = null; libGenre.value = null
        selected.value = SHELF + id
    }

    fun versionCount(item: LibItem): Int {
        val idx = index.value ?: return 1
        return library.versionCountFast(idx, item)
    }

    fun openLib(item: LibItem, onId: (Long) -> Unit) = onId(item.id)

    /** Resolves a DB row id for navigation (favorites only carry remote ids). */
    fun open(item: ContentEntity, onId: (Long) -> Unit) {
        if (item.id != 0L) {
            onId(item.id); return
        }
        viewModelScope.launch { content.byRemote(item.subscriptionId, type, item.remoteId)?.let { onId(it.id) } }
    }
}

@HiltViewModel
class MoviesViewModel @Inject constructor(content: ContentRepository, settings: SettingsRepository, library: com.nova.iptv.data.library.LibraryRepository) :
    VodViewModel(ContentType.MOVIE, content, settings, library)

@HiltViewModel
class SeriesViewModel @Inject constructor(content: ContentRepository, settings: SettingsRepository, library: com.nova.iptv.data.library.LibraryRepository) :
    VodViewModel(ContentType.SERIES, content, settings, library)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val content: ContentRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val subId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val filter = MutableStateFlow("")

    private val allFavorites: StateFlow<List<FavoriteEntity>> = subId
        .flatMapLatest { content.favorites(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<FavoriteEntity>> = combine(allFavorites, filter) { list, f ->
        if (f.isEmpty()) list else list.filter { it.type == f }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun move(item: FavoriteEntity, delta: Int) {
        val list = allFavorites.value
        val from = list.indexOf(item)
        val to = from + delta
        viewModelScope.launch { content.moveFavorite(list, from, to) }
    }

    fun remove(item: FavoriteEntity) {
        viewModelScope.launch { content.removeFavorite(item) }
    }

    fun open(item: FavoriteEntity, onContent: (ContentEntity) -> Unit) {
        viewModelScope.launch {
            content.byRemote(item.subscriptionId, item.type, item.remoteId)?.let(onContent)
        }
    }
}

data class SearchState(
    val query: String = "",
    val type: String = "",
    val categoryId: String = "",
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val content: ContentRepository,
    settings: SettingsRepository,
    private val tmdb: com.nova.iptv.data.tmdb.TmdbRepository,
    private val library: com.nova.iptv.data.library.LibraryRepository,
) : ViewModel() {
    private val subId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val state = MutableStateFlow(SearchState())

    val categories: StateFlow<List<CategoryEntity>> = combine(subId, state.map { it.type }) { s, t -> s to t }
        .flatMapLatest { (s, t) -> if (t.isEmpty()) flowOf(emptyList<CategoryEntity>()) else content.visibleCategories(s, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val results: StateFlow<List<ContentEntity>?> = combine(subId, state) { s, st -> s to st }
        .debounce(300)
        .transformLatest { (s, st) ->
            val q = st.query.trim()
            if (q.length < 2) { emit(null); return@transformLatest }
            val local = content.search(s, st.query, st.type, st.categoryId)
            emit(local)
            // titles known by another name: ask TMDB (Arabic / English / original names) and show the ones in the subscription
            if (st.type == ContentType.LIVE || st.categoryId.isNotEmpty() || !tmdb.enabled.value) return@transformLatest
            val seen = local.map { it.id }.toHashSet()
            val extra = ArrayList<ContentEntity>()
            for (w in tmdb.searchAll(q)) {
                val type = if (w.isTv) ContentType.SERIES else ContentType.MOVIE
                if (st.type.isNotEmpty() && st.type != type) continue
                val id = runCatching { library.find(s, w) }.getOrNull() ?: continue
                if (!seen.add(id)) continue
                content.byId(id)?.let { extra += it }
            }
            if (extra.isNotEmpty()) emit(extra + local)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setQuery(q: String) { state.value = state.value.copy(query = q) }
    fun setType(t: String) { state.value = state.value.copy(type = t, categoryId = "") }
    fun setCategory(c: String) { state.value = state.value.copy(categoryId = c) }

    fun open(item: ContentEntity, onContent: (ContentEntity) -> Unit) = onContent(item)
}

/** Helper used by screens to build "type|remoteId" favourite keys. */
fun favKey(type: String, remoteId: String) = "$type|$remoteId"

@Suppress("unused")
private fun <T> Flow<T>.ignore() = this


/** "Home" tab: a mixed showcase across live, movies and series. */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val content: ContentRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val subId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val featured: StateFlow<List<ContentEntity>> = subId
        .flatMapLatest { s ->
            combine(content.recentlyAdded(s, ContentType.MOVIE, 30), content.recentlyAdded(s, ContentType.SERIES, 30)) { m, se ->
                val movies = m.filter { it.logo.isNotBlank() }
                val series = se.filter { it.logo.isNotBlank() }
                val out = ArrayList<ContentEntity>()
                for (i in 0 until maxOf(movies.size, series.size)) {
                    movies.getOrNull(i)?.let { out += it }
                    series.getOrNull(i)?.let { out += it }
                    if (out.size >= 8) break
                }
                out.take(8)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val continueWatching: StateFlow<List<HistoryEntity>> = subId
        .flatMapLatest { content.continueWatching(it) }
        .map { l -> l.distinctBy { "${it.subscriptionId}|${it.type}|${it.remoteId}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentChannels: StateFlow<List<ContentEntity>> = subId
        .flatMapLatest { content.recent(it, ContentType.LIVE, 20) }
        .map { l -> l.map { it.asContent() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val latestMovies: StateFlow<List<ContentEntity>> = subId
        .flatMapLatest { content.recentlyAdded(it, ContentType.MOVIE, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val latestSeries: StateFlow<List<ContentEntity>> = subId
        .flatMapLatest { content.recentlyAdded(it, ContentType.SERIES, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<FavoriteEntity>> = subId
        .flatMapLatest { content.favorites(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteKeys: StateFlow<Set<String>> = subId
        .flatMapLatest { content.favoriteKeys(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    suspend fun heroInfo(c: ContentEntity) = content.heroInfo(c)

    suspend fun backdropOf(h: HistoryEntity): String? = content.heroInfo(h.asContent())?.backdrop?.takeIf { it.isNotBlank() }

    /** Home scroll position kept across navigation. */
    val listState = androidx.compose.foundation.lazy.LazyListState()

    fun open(item: ContentEntity, onId: (Long) -> Unit) {
        if (item.id != 0L) { onId(item.id); return }
        viewModelScope.launch { content.byRemote(item.subscriptionId, item.type, item.remoteId)?.let { onId(it.id) } }
    }

    fun toggleFavorite(c: ContentEntity) {
        viewModelScope.launch { content.toggleFavorite(c.subscriptionId, c.type, c.remoteId, c.name, c.logo) }
    }
}
