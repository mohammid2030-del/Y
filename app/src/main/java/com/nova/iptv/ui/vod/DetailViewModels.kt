@file:OptIn(ExperimentalCoroutinesApi::class)

package com.nova.iptv.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nova.iptv.data.NovaException
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.HistoryEntity
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.network.XSeriesInfo
import com.nova.iptv.data.network.XVodInfo
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.SubscriptionRepository
import com.nova.iptv.data.toNovaException
import com.nova.iptv.data.tmdb.TmdbEpisode
import com.nova.iptv.data.tmdb.TmdbInfo
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val content: ContentRepository,
    private val subs: SubscriptionRepository,
    library: com.nova.iptv.data.library.LibraryRepository,
    tmdbRepo: com.nova.iptv.data.tmdb.TmdbRepository,
) : ViewModel() {
    val extras = DetailExtras(library, tmdbRepo, viewModelScope)
    private val id: Long = savedState.get<Long>("id") ?: 0L
    private val itemFlow = MutableStateFlow<ContentEntity?>(null)
    val item: StateFlow<ContentEntity?> = itemFlow

    var info by mutableStateOf<XVodInfo?>(null)
        private set
    var tmdb by mutableStateOf<TmdbInfo?>(null)
        private set
    var notFound by mutableStateOf(false)
        private set

    val history: StateFlow<HistoryEntity?> = itemFlow
        .flatMapLatest { c -> if (c == null) flowOf<HistoryEntity?>(null) else content.movieHistory(c.subscriptionId, c.remoteId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isFavorite: StateFlow<Boolean> = itemFlow
        .flatMapLatest { c ->
            if (c == null) flowOf(false)
            else content.favoriteKeys(c.subscriptionId).map { "${ContentType.MOVIE}|${c.remoteId}" in it }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val similar: StateFlow<List<ContentEntity>> = itemFlow
        .flatMapLatest { c ->
            if (c == null) flowOf(emptyList<ContentEntity>())
            else content.row(c.subscriptionId, ContentType.MOVIE, c.categoryId, 21).map { l -> l.filter { it.id != c.id }.take(20) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val c = content.byId(id)
            if (c == null) {
                notFound = true; return@launch
            }
            show(c)
        }
    }

    private suspend fun show(c: ContentEntity) {
        itemFlow.value = c
        extras.load(c)
        info = null
        val sub = subs.get(c.subscriptionId)
        if (sub != null && sub.type == SourceType.XTREAM) info = content.vodInfo(sub, c.remoteId)
        tmdb = content.tmdbFor(c, info?.tmdbId.orEmpty())
    }

    /** Switches the page to another copy of the title (another language / quality). */
    fun pick(id: Long) {
        extras.choosing = false
        if (id == itemFlow.value?.id) return
        viewModelScope.launch { content.byId(id)?.let { show(it) } }
    }

    fun toggleFavorite() {
        val c = itemFlow.value ?: return
        viewModelScope.launch { content.toggleFavorite(c.subscriptionId, ContentType.MOVIE, c.remoteId, c.name, c.logo) }
    }
}

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val content: ContentRepository,
    private val subs: SubscriptionRepository,
    library: com.nova.iptv.data.library.LibraryRepository,
    tmdbRepo: com.nova.iptv.data.tmdb.TmdbRepository,
) : ViewModel() {
    val extras = DetailExtras(library, tmdbRepo, viewModelScope)
    private val id: Long = savedState.get<Long>("id") ?: 0L
    private val itemFlow = MutableStateFlow<ContentEntity?>(null)
    val item: StateFlow<ContentEntity?> = itemFlow

    var info by mutableStateOf<XSeriesInfo?>(null)
        private set
    var tmdb by mutableStateOf<TmdbInfo?>(null)
        private set
    /** TMDB episodes of the selected season, by episode number. */
    var tmdbEpisodes by mutableStateOf<Map<Int, TmdbEpisode>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<NovaException?>(null)
        private set
    var selectedSeason by mutableIntStateOf(-1)

    /** episodeId -> history entry */
    val progress: StateFlow<Map<String, HistoryEntity>> = itemFlow
        .flatMapLatest { c ->
            if (c == null) flowOf(emptyList<HistoryEntity>()) else content.seriesHistory(c.subscriptionId, c.remoteId)
        }
        .map { list -> list.associateBy { it.episodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val lastWatched: StateFlow<HistoryEntity?> = itemFlow
        .flatMapLatest { c ->
            if (c == null) flowOf(emptyList<HistoryEntity>()) else content.seriesHistory(c.subscriptionId, c.remoteId)
        }
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isFavorite: StateFlow<Boolean> = itemFlow
        .flatMapLatest { c ->
            if (c == null) flowOf(false)
            else content.favoriteKeys(c.subscriptionId).map { "${ContentType.SERIES}|${c.remoteId}" in it }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val similar: StateFlow<List<ContentEntity>> = itemFlow
        .flatMapLatest { c ->
            if (c == null) flowOf(emptyList<ContentEntity>())
            else content.row(c.subscriptionId, ContentType.SERIES, c.categoryId, 21).map { l -> l.filter { it.id != c.id }.take(20) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        load(false)
        viewModelScope.launch {
            snapshotFlow { selectedSeason to tmdb?.id }
                .distinctUntilChanged()
                .collect { (season, id) ->
                    tmdbEpisodes = if (id != null && season >= 0) content.tmdbSeason(id, season) else emptyMap()
                }
        }
    }

    fun load(force: Boolean) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val c = itemFlow.value ?: content.byId(id) ?: throw NovaException(com.nova.iptv.data.ErrorKind.UNKNOWN)
                if (itemFlow.value == null) extras.load(c)
                itemFlow.value = c
                val sub = subs.get(c.subscriptionId) ?: throw NovaException(com.nova.iptv.data.ErrorKind.UNKNOWN)
                if (sub.type != SourceType.XTREAM) throw NovaException(com.nova.iptv.data.ErrorKind.UNKNOWN)
                val i = content.seriesInfo(sub, c.remoteId, force)
                info = i
                if (selectedSeason !in i.seasons.keys) selectedSeason = i.seasons.keys.firstOrNull() ?: -1
                if (tmdb == null) launch { tmdb = content.tmdbFor(c, i.tmdbId) }
            } catch (e: Exception) {
                error = e.toNovaException()
            } finally {
                loading = false
            }
        }
    }

    /** Switches the page to another copy of the series (another language / quality). */
    fun pick(id: Long) {
        extras.choosing = false
        if (id == itemFlow.value?.id) return
        viewModelScope.launch {
            val c = content.byId(id) ?: return@launch
            itemFlow.value = c
            info = null; tmdb = null; tmdbEpisodes = emptyMap(); selectedSeason = -1
            load(false)
        }
    }

    fun toggleFavorite() {
        val c = itemFlow.value ?: return
        viewModelScope.launch { content.toggleFavorite(c.subscriptionId, ContentType.SERIES, c.remoteId, c.name, c.logo) }
    }
}
