@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nova.iptv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nova.iptv.R
import com.nova.iptv.data.db.CategoryEntity
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.EpgProgramEntity
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.player.PlayerLauncher
import com.nova.iptv.ui.components.ChannelLogo
import com.nova.iptv.ui.components.EmptyView
import com.nova.iptv.ui.components.LoadingView
import com.nova.iptv.ui.components.NetImage
import com.nova.iptv.ui.components.NovaChip
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.LocalNovaFlags
import com.nova.iptv.ui.theme.NovaBlue
import com.nova.iptv.ui.theme.NovaGradient
import com.nova.iptv.ui.theme.NovaLive
import com.nova.iptv.ui.theme.NovaViolet
import java.text.DateFormat
import java.util.Date

@Composable
fun specialCategoryLabel(id: String, type: String): String = when (id) {
    SpecialCat.ALL -> stringResource(if (type == ContentType.LIVE) R.string.cat_all else R.string.discover)
    SpecialCat.FAV -> stringResource(R.string.cat_favorites)
    SpecialCat.RECENT -> stringResource(if (type == ContentType.LIVE) R.string.cat_recent_watched else R.string.cat_recently_added)
    else -> id
}

@Composable
fun CategoryChips(
    categories: List<CategoryEntity>,
    selected: String,
    type: String,
    onSelect: (String) -> Unit,
) {
    val specials = listOf(SpecialCat.ALL, SpecialCat.FAV, SpecialCat.RECENT)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(specials) { id ->
            NovaChip(specialCategoryLabel(id, type), selected == id, { onSelect(id) })
        }
        items(categories, key = { it.id }) { c ->
            NovaChip(c.name, selected == c.remoteId, { onSelect(c.remoteId) })
        }
    }
}

@Composable
private fun CategoryColumn(
    categories: List<CategoryEntity>,
    selected: String,
    type: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val specials = listOf(SpecialCat.ALL, SpecialCat.FAV, SpecialCat.RECENT)
    LazyColumn(modifier, contentPadding = PaddingValues(10.dp)) {
        items(specials) { id -> CategoryItem(specialCategoryLabel(id, type), selected == id) { onSelect(id) } }
        items(categories, key = { it.id }) { c -> CategoryItem(c.name, selected == c.remoteId) { onSelect(c.remoteId) } }
    }
}

@Composable
private fun CategoryItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .novaFocus(shape)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp))
                .background(if (selected) NovaGradient else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun LiveTab(wide: Boolean, vm: LiveViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val showEpg by vm.showEpg.collectAsStateWithLifecycle()
    val offset by vm.epgOffsetMs.collectAsStateWithLifecycle()
    val last by vm.lastWatched.collectAsStateWithLifecycle()
    val grid by vm.gridMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tv = LocalNovaFlags.current.isTv
    val smallList = selected == SpecialCat.FAV || selected == SpecialCat.RECENT

    fun play(c: ContentEntity) {
        vm.rememberLastChannel(c)
        PlayerLauncher.play(
            context,
            PlaybackRequest(
                subscriptionId = c.subscriptionId,
                type = ContentType.LIVE,
                remoteId = c.remoteId,
                title = c.name,
                logo = c.logo,
                categoryId = when {
                    selected == SpecialCat.ALL -> ""
                    smallList -> c.categoryId
                    else -> selected
                },
            ),
            channelList = if (smallList) channels.itemSnapshotList.items else null,
        )
    }

    val header: @Composable () -> Unit = {
        Column {
            val l = last
            if (selected == SpecialCat.ALL && l != null) {
                Spotlight(l, if (l.epgId.isNotEmpty()) now[l.epgId] else null, offset) { play(l) }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(NovaLive))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (selected.startsWith("\u0001")) specialCategoryLabel(selected, ContentType.LIVE)
                    else categories.firstOrNull { it.remoteId == selected }?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                IconButton(onClick = { vm.gridMode.value = !grid }) {
                    Icon(
                        if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                        stringResource(if (grid) R.string.view_list else R.string.view_grid),
                    )
                }
            }
        }
    }

    val list: @Composable (Modifier) -> Unit = { modifier ->
        when {
            channels.itemCount == 0 && channels.loadState.refresh is LoadState.Loading -> LoadingView(modifier)
            channels.itemCount == 0 -> Column(modifier) {
                header()
                EmptyView(
                    stringResource(if (selected == SpecialCat.FAV) R.string.empty_favorites else R.string.empty_channels),
                    Modifier.weight(1f),
                    if (selected == SpecialCat.RECENT) Icons.Filled.History else Icons.Filled.Star,
                )
            }
            grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (tv) 200.dp else 150.dp),
                modifier = modifier,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
                items(
                    count = channels.itemCount,
                    key = channels.itemKey { c -> if (c.id != 0L) c.id else "${c.type}|${c.remoteId}" },
                ) { index ->
                    val c = channels[index] ?: return@items
                    ChannelTile(
                        channel = c,
                        program = if (showEpg && c.epgId.isNotEmpty()) now[c.epgId] else null,
                        offsetMs = offset,
                        favorite = favKey(ContentType.LIVE, c.remoteId) in favorites,
                        onClick = { play(c) },
                    )
                }
            }
            else -> LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "header") { header() }
                items(
                    count = channels.itemCount,
                    key = channels.itemKey { c -> if (c.id != 0L) c.id else "${c.type}|${c.remoteId}" },
                ) { index ->
                    val c = channels[index] ?: return@items
                    ChannelRow(
                        channel = c,
                        program = if (showEpg && c.epgId.isNotEmpty()) now[c.epgId] else null,
                        offsetMs = offset,
                        favorite = favKey(ContentType.LIVE, c.remoteId) in favorites,
                        onFavorite = { vm.toggleFavorite(c) },
                        onClick = { play(c) },
                    )
                }
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            CategoryColumn(
                categories, selected, ContentType.LIVE, vm::select,
                Modifier.width(250.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
            )
            list(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            CategoryChips(categories, selected, ContentType.LIVE, vm::select)
            list(Modifier.weight(1f))
        }
    }
}

private fun progressOf(p: EpgProgramEntity, offsetMs: Long): Float {
    val start = p.start + offsetMs
    val stop = p.stop + offsetMs
    return ((System.currentTimeMillis() - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f)
}

private fun minutesLeft(p: EpgProgramEntity, offsetMs: Long): Int =
    (((p.stop + offsetMs) - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)

@Composable
private fun GradientProgress(value: Float, modifier: Modifier = Modifier, height: Int = 4) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(value).clip(RoundedCornerShape(50)).background(NovaGradient))
    }
}

/** "Continue watching" hero for the last channel the user watched. */
@Composable
private fun Spotlight(channel: ContentEntity, program: EpgProgramEntity?, offsetMs: Long, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .novaFocus(shape)
            .shadow(16.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1B1E46), Color(0xFF2A1748), Color(0xFF111118))))
            .clickable(onClick = onPlay)
    ) {
        // big faded logo as artwork
        NetImage(
            channel.logo,
            Modifier.align(Alignment.CenterEnd).size(170.dp).padding(18.dp),
            androidx.compose.ui.layout.ContentScale.Fit,
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(listOf(Color(0xF0111118), Color(0xB3111118), Color.Transparent))
            )
        )
        Column(Modifier.padding(20.dp).fillMaxWidth(0.72f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.live_badge),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(NovaLive).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.continue_watching),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                channel.name, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (program != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    program.title, color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                GradientProgress(progressOf(program, offsetMs))
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.watch_now), color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun ChannelRow(
    channel: ContentEntity,
    program: EpgProgramEntity?,
    offsetMs: Long,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .novaFocus(shape)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainerHigh)
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ChannelLogo(channel.logo, Modifier.size(width = 76.dp, height = 54.dp))
            if (channel.num > 0) {
                Text(
                    "${channel.num}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 5.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (channel.tvArchive) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.History, stringResource(R.string.catchup), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (program != null) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeFmt.format(Date(program.start + offsetMs)),
                        style = MaterialTheme.typography.labelMedium,
                        color = NovaBlue,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        program.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.minutes_left, minutesLeft(program, offsetMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = NovaViolet,
                    )
                }
                Spacer(Modifier.height(6.dp))
                GradientProgress(progressOf(program, offsetMs), height = 3)
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                stringResource(R.string.favorite),
                tint = if (favorite) Color(0xFFFFC857) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelTile(
    channel: ContentEntity,
    program: EpgProgramEntity?,
    offsetMs: Long,
    favorite: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .novaFocus(shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(
                    Brush.radialGradient(listOf(NovaViolet.copy(alpha = 0.28f), MaterialTheme.colorScheme.surfaceContainerHigh))
                ),
            contentAlignment = Alignment.Center,
        ) {
            NetImage(channel.logo, Modifier.fillMaxSize().padding(18.dp), androidx.compose.ui.layout.ContentScale.Fit, Icons.Filled.PlayArrow)
            if (channel.num > 0) {
                Text(
                    "${channel.num}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 5.dp),
                )
            }
            if (favorite) {
                Icon(
                    Icons.Filled.Star, null, tint = Color(0xFFFFC857),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp),
                )
            }
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp).size(7.dp).clip(CircleShape).background(NovaLive))
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                program?.title ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (program != null) {
                Spacer(Modifier.height(6.dp))
                GradientProgress(progressOf(program, offsetMs), height = 3)
            }
        }
    }
}
