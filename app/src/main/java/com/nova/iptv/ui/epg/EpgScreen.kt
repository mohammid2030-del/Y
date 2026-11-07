@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nova.iptv.ui.epg

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nova.iptv.R
import com.nova.iptv.data.db.CategoryEntity
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.EpgProgramEntity
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.repo.SubscriptionRepository
import com.nova.iptv.player.PlayerLauncher
import com.nova.iptv.ui.components.ChannelLogo
import com.nova.iptv.ui.components.EmptyView
import com.nova.iptv.ui.components.LoadingView
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.home.CategoryChips
import com.nova.iptv.ui.home.SpecialCat
import com.nova.iptv.ui.theme.NovaLive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

private const val HOUR_MS = 3_600_000L
private val HOUR_WIDTH: Dp = 240.dp
private val CHANNEL_COL: Dp = 150.dp
private val ROW_HEIGHT: Dp = 64.dp

data class EpgState(
    val loading: Boolean = true,
    val channels: List<ContentEntity> = emptyList(),
    val programmes: Map<String, List<EpgProgramEntity>> = emptyMap(),
    val windowStart: Long = 0,
    val windowEnd: Long = 0,
    val offsetMs: Long = 0,
    val hasGuide: Boolean = true,
)

@HiltViewModel
class EpgViewModel @Inject constructor(
    private val content: ContentRepository,
    private val settings: SettingsRepository,
    private val subs: SubscriptionRepository,
) : ViewModel() {
    private val subId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val categories: StateFlow<List<CategoryEntity>> = subId
        .flatMapLatest { content.visibleCategories(it, ContentType.LIVE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selected = MutableStateFlow(SpecialCat.ALL)
    val state = MutableStateFlow(EpgState())
    var refreshing by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            // default to first real category to keep the grid light
            val first = categories.value.firstOrNull() ?: run {
                kotlinx.coroutines.delay(300); categories.value.firstOrNull()
            }
            if (first != null) selected.value = first.remoteId
            reload()
        }
    }

    fun select(category: String) {
        selected.value = category
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true)
            val s = subId.value
            val offset = settings.current().epgOffsetMinutes * 60_000L
            val now = System.currentTimeMillis()
            val start = (now - 2 * HOUR_MS) / (HOUR_MS / 2) * (HOUR_MS / 2)
            val end = start + 16 * HOUR_MS
            val channels = when (selected.value) {
                SpecialCat.ALL, SpecialCat.FAV, SpecialCat.RECENT -> content.channelsFor(s, "").take(500)
                else -> content.channelsFor(s, selected.value)
            }
            val keys = channels.map { it.epgId }.filter { it.isNotEmpty() }
            val programmes = content.programmesFor(s, keys, start - offset, end - offset)
            state.value = EpgState(false, channels, programmes, start, end, offset, programmes.isNotEmpty() || keys.isEmpty())
        }
    }

    fun refreshGuide() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            runCatching { subs.syncEpgOnly(subId.value) }
            refreshing = false
            reload()
        }
    }
}

@Composable
fun EpgScreen(onBack: () -> Unit, vm: EpgViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<Pair<ContentEntity, EpgProgramEntity>?>(null) }

    val pxPerMs = with(density) { HOUR_WIDTH.toPx() } / HOUR_MS
    fun scrollToNow() {
        val x = ((System.currentTimeMillis() - state.windowStart - HOUR_MS / 2) * pxPerMs).toInt().coerceAtLeast(0)
        scope.launch { scroll.animateScrollTo(x) }
    }
    LaunchedEffect(state.windowStart, state.loading) { if (!state.loading) scrollToNow() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tv_guide)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { scrollToNow() }) { Icon(Icons.Filled.MyLocation, stringResource(R.string.now)) }
                    IconButton(onClick = vm::refreshGuide, enabled = !vm.refreshing) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.refresh_guide))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            CategoryChips(categories, selected, ContentType.LIVE, vm::select)
            when {
                state.loading -> LoadingView()
                state.channels.isEmpty() -> EmptyView(stringResource(R.string.empty_channels))
                else -> {
                    if (!state.hasGuide) {
                        Text(
                            stringResource(R.string.no_guide_data),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    TimeHeader(state, scroll)
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(state.channels, key = { _, c -> c.id }) { _, ch ->
                            EpgRow(
                                channel = ch,
                                programmes = state.programmes[ch.epgId].orEmpty(),
                                state = state,
                                scroll = scroll,
                                onChannel = {
                                    PlayerLauncher.play(
                                        context,
                                        PlaybackRequest(ch.subscriptionId, ContentType.LIVE, ch.remoteId, ch.name, ch.logo, categoryId = ch.categoryId),
                                        channelList = state.channels,
                                    )
                                },
                                onProgramme = { p -> dialog = ch to p },
                            )
                        }
                    }
                }
            }
        }
    }

    dialog?.let { (ch, p) ->
        val now = System.currentTimeMillis()
        val start = p.start + state.offsetMs
        val stop = p.stop + state.offsetMs
        val isNow = now in start until stop
        val catchupOk = ch.tvArchive && stop < now && now - start < (ch.archiveDays.coerceAtLeast(1)) * 24 * HOUR_MS
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(p.title) },
            text = {
                Column {
                    Text("${ch.name} • ${fmt.format(Date(start))} – ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(stop))}",
                        style = MaterialTheme.typography.labelLarge)
                    if (p.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(p.description)
                    }
                }
            },
            confirmButton = {
                when {
                    isNow -> TextButton(onClick = {
                        dialog = null
                        PlayerLauncher.play(
                            context,
                            PlaybackRequest(ch.subscriptionId, ContentType.LIVE, ch.remoteId, ch.name, ch.logo, categoryId = ch.categoryId),
                            channelList = state.channels,
                        )
                    }) { Text(stringResource(R.string.watch_live)) }
                    catchupOk -> TextButton(onClick = {
                        dialog = null
                        PlayerLauncher.play(
                            context,
                            PlaybackRequest(
                                ch.subscriptionId, ContentType.LIVE, ch.remoteId, ch.name, ch.logo,
                                subtitle = p.title,
                                catchupStartMs = p.start,
                                catchupDurationMin = ((p.stop - p.start) / 60_000L).toInt().coerceAtLeast(1),
                            ),
                        )
                    }) { Text(stringResource(R.string.watch_catchup)) }
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun TimeHeader(state: EpgState, scroll: ScrollState) {
    val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    val hours = ((state.windowEnd - state.windowStart) / HOUR_MS).toInt()
    Row(Modifier.fillMaxWidth().height(32.dp)) {
        Spacer(Modifier.width(CHANNEL_COL))
        Row(Modifier.horizontalScroll(scroll)) {
            for (i in 0 until hours * 2) {
                Text(
                    fmt.format(Date(state.windowStart + i * HOUR_MS / 2)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(HOUR_WIDTH / 2).padding(start = 6.dp, top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun EpgRow(
    channel: ContentEntity,
    programmes: List<EpgProgramEntity>,
    state: EpgState,
    scroll: ScrollState,
    onChannel: () -> Unit,
    onProgramme: (EpgProgramEntity) -> Unit,
) {
    val totalWidth = HOUR_WIDTH * ((state.windowEnd - state.windowStart).toFloat() / HOUR_MS)
    val now = System.currentTimeMillis()
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
        Row(
            Modifier
                .width(CHANNEL_COL)
                .fillMaxHeight()
                .padding(horizontal = 4.dp)
                .novaFocus(RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onChannel)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(channel.logo, Modifier.size(36.dp))
            Spacer(Modifier.width(6.dp))
            Text(channel.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxHeight().horizontalScroll(scroll)) {
            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                if (programmes.isEmpty()) {
                    Text(
                        stringResource(R.string.no_info),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                    )
                }
                programmes.forEach { p ->
                    val start = (p.start + state.offsetMs).coerceAtLeast(state.windowStart)
                    val stop = (p.stop + state.offsetMs).coerceAtMost(state.windowEnd)
                    if (stop > start) {
                        val x = HOUR_WIDTH * ((start - state.windowStart).toFloat() / HOUR_MS)
                        val w = HOUR_WIDTH * ((stop - start).toFloat() / HOUR_MS)
                        val live = now in (p.start + state.offsetMs) until (p.stop + state.offsetMs)
                        val shape = RoundedCornerShape(8.dp)
                        Column(
                            Modifier
                                .offset(x = x)
                                .width(w)
                                .fillMaxHeight()
                                .padding(horizontal = 2.dp)
                                .novaFocus(shape)
                                .clip(shape)
                                .background(
                                    if (live) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { onProgramme(p) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (channel.tvArchive && p.stop + state.offsetMs < now) {
                                    Icon(Icons.Filled.History, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.tertiary)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(p.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(p.start + state.offsetMs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (now in state.windowStart until state.windowEnd) {
                    val x = HOUR_WIDTH * ((now - state.windowStart).toFloat() / HOUR_MS)
                    Box(Modifier.offset(x = x).width(2.dp).fillMaxHeight().background(NovaLive))
                }
            }
        }
    }
}
