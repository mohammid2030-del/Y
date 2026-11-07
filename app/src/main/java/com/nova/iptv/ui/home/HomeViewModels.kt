@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package com.nova.iptv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nova.iptv.data.db.CategoryEntity
import com.nova.iptv.data.db.ContentEntity
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    val channels: StateFlow<List<ContentEntity>?> = combine(subId, selected) { s, c -> s to c }
        .flatMapLatest { (s, c) ->
            when (c) {
                SpecialCat.ALL -> content.all(s, ContentType.LIVE, 50_000)
                SpecialCat.FAV -> content.favorites(s).map { l -> l.filter { it.type == ContentType.LIVE }.map { it.asContent() } }
                SpecialCat.RECENT -> content.recent(s, ContentType.LIVE, 50).map { l -> l.map { it.asContent() } }
                else -> content.byCategory(s, ContentType.LIVE, c)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
) : ViewModel() {
    val subId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val selected = MutableStateFlow(SpecialCat.ALL)

    val categories: StateFlow<List<CategoryEntity>> = subId
        .flatMapLatest { content.visibleCategories(it, type) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteKeys: StateFlow<Set<String>> = subId
        .flatMapLatest { content.favoriteKeys(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val items: StateFlow<List<ContentEntity>?> = combine(subId, selected) { s, c -> s to c }
        .flatMapLatest { (s, c) ->
            when (c) {
                SpecialCat.ALL -> content.all(s, type, 50_000)
                SpecialCat.FAV -> content.favorites(s).map { l -> l.filter { it.type == type }.map { it.asContent() } }
                SpecialCat.RECENT -> content.recentlyAdded(s, type, 200)
                else -> content.byCategory(s, type, c)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val continueWatching: StateFlow<List<HistoryEntity>> = subId
        .flatMapLatest { content.continueWatching(it) }
        .map { l -> l.filter { it.type == type } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun select(category: String) {
        selected.value = category
    }

    /** Resolves a DB row id for navigation (favorites only carry remote ids). */
    fun open(item: ContentEntity, onId: (Long) -> Unit) {
        if (item.id != 0L) {
            onId(item.id); return
        }
        viewModelScope.launch { content.byRemote(item.subscriptionId, type, item.remoteId)?.let { onId(it.id) } }
    }
}

@HiltViewModel
class MoviesViewModel @Inject constructor(content: ContentRepository, settings: SettingsRepository) :
    VodViewModel(ContentType.MOVIE, content, settings)

@HiltViewModel
class SeriesViewModel @Inject constructor(content: ContentRepository, settings: SettingsRepository) :
    VodViewModel(ContentType.SERIES, content, settings)

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
) : ViewModel() {
    private val subId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val state = MutableStateFlow(SearchState())

    val categories: StateFlow<List<CategoryEntity>> = combine(subId, state.map { it.type }) { s, t -> s to t }
        .flatMapLatest { (s, t) -> if (t.isEmpty()) flowOf(emptyList<CategoryEntity>()) else content.visibleCategories(s, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val results: StateFlow<List<ContentEntity>?> = combine(subId, state) { s, st -> s to st }
        .debounce(300)
        .mapLatest { (s, st) ->
            if (st.query.trim().length < 2) null else content.search(s, st.query, st.type, st.categoryId)
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
