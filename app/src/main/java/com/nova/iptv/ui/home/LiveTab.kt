@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nova.iptv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.NovaLive
import java.text.DateFormat
import java.util.Date

@Composable
fun specialCategoryLabel(id: String, type: String): String = when (id) {
    SpecialCat.ALL -> stringResource(R.string.cat_all)
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
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(specials) { id ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(specialCategoryLabel(id, type)) },
                modifier = Modifier.novaFocus(RoundedCornerShape(8.dp)),
            )
        }
        items(categories, key = { it.id }) { c ->
            FilterChip(
                selected = selected == c.remoteId,
                onClick = { onSelect(c.remoteId) },
                label = { Text(c.name, maxLines = 1) },
                modifier = Modifier.novaFocus(RoundedCornerShape(8.dp)),
            )
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
    LazyColumn(modifier, contentPadding = PaddingValues(8.dp)) {
        items(specials) { id -> CategoryItem(specialCategoryLabel(id, type), selected == id) { onSelect(id) } }
        items(categories, key = { it.id }) { c -> CategoryItem(c.name, selected == c.remoteId) { onSelect(c.remoteId) } }
    }
}

@Composable
private fun CategoryItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .novaFocus(shape)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
fun LiveTab(wide: Boolean, vm: LiveViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    val showEpg by vm.showEpg.collectAsStateWithLifecycle()
    val offset by vm.epgOffsetMs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val list: @Composable (Modifier) -> Unit = { modifier ->
        val items = channels
        when {
            items == null -> LoadingView(modifier)
            items.isEmpty() -> EmptyView(
                stringResource(if (selected == SpecialCat.FAV) R.string.empty_favorites else R.string.empty_channels),
                modifier,
                if (selected == SpecialCat.RECENT) Icons.Filled.History else Icons.Filled.Star,
            )
            else -> LazyColumn(modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(items, key = { i, c -> if (c.id != 0L) c.id else "k$i" }) { _, c ->
                    ChannelRow(
                        channel = c,
                        program = if (showEpg && c.epgId.isNotEmpty()) now[c.epgId] else null,
                        offsetMs = offset,
                        favorite = favKey(ContentType.LIVE, c.remoteId) in favorites,
                        onFavorite = { vm.toggleFavorite(c) },
                        onClick = {
                            vm.rememberLastChannel(c)
                            PlayerLauncher.play(
                                context,
                                PlaybackRequest(
                                    subscriptionId = c.subscriptionId,
                                    type = ContentType.LIVE,
                                    remoteId = c.remoteId,
                                    title = c.name,
                                    logo = c.logo,
                                    categoryId = c.categoryId,
                                ),
                                channelList = items,
                            )
                        },
                    )
                }
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            CategoryColumn(categories, selected, ContentType.LIVE, vm::select, Modifier.width(240.dp).fillMaxHeight())
            list(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            CategoryChips(categories, selected, ContentType.LIVE, vm::select)
            list(Modifier.weight(1f))
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
    val shape = RoundedCornerShape(12.dp)
    val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .novaFocus(shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(channel.logo, Modifier.size(width = 64.dp, height = 44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel.num > 0) {
                    Text(
                        "${channel.num}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (channel.tvArchive) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.History, stringResource(R.string.catchup), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (program != null) {
                val start = program.start + offsetMs
                val stop = program.stop + offsetMs
                val nowMs = System.currentTimeMillis()
                Text(
                    "${timeFmt.format(Date(start))}  ${program.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { ((nowMs - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                    color = NovaLive,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
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
