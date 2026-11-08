@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nova.iptv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nova.iptv.R
import com.nova.iptv.data.db.CategoryEntity
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.HistoryEntity
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.player.PlayerLauncher
import com.nova.iptv.ui.components.EmptyView
import com.nova.iptv.ui.components.GlassChip
import com.nova.iptv.ui.components.LoadingView
import com.nova.iptv.ui.components.NetImage
import com.nova.iptv.ui.components.PosterCard
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.LocalNovaFlags
import com.nova.iptv.ui.theme.NovaGradient
import kotlinx.coroutines.delay

private fun ContentEntity.meta(): String =
    listOf(year, rating.takeIf { it.isNotBlank() }?.let { "★ ${it.take(3)}" }.orEmpty())
        .filter { it.isNotBlank() }.joinToString("  ·  ")

/**
 * Movies / Series catalog.
 * "Discover" shows a hero carousel and Netflix-style rows per category; a selected category shows a paged grid.
 */
@Composable
fun VodTab(vm: VodViewModel, onOpen: (Long) -> Unit) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    LaunchedEffect(selected) { if (selected != SpecialCat.ALL) gridState.scrollToItem(0) }
    OnReselect {
        when {
            selected == SpecialCat.ALL -> listState.smoothScrollToTop()
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 -> gridState.smoothScrollToTop()
            else -> vm.select(SpecialCat.ALL)
        }
    }
    if (selected == SpecialCat.ALL) {
        DiscoverView(vm, categories, listState, onOpen)
    } else {
        Column(Modifier.fillMaxSize().padding(top = homeTopInset())) {
            CategoryChips(categories, selected, vm.type, vm::select)
            CategoryGrid(vm, selected, gridState, onOpen)
        }
    }
}

@Composable
internal fun posterWidth(): Dp {
    val tv = LocalNovaFlags.current.isTv
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    return when {
        tv -> 160.dp
        wide -> 140.dp
        else -> 118.dp
    }
}

@Composable
private fun DiscoverView(vm: VodViewModel, categories: List<CategoryEntity>, listState: LazyListState, onOpen: (Long) -> Unit) {
    val featured by vm.featured.collectAsStateWithLifecycle()
    val recent by vm.recentRow.collectAsStateWithLifecycle()
    val continueList by vm.continueWatching.collectAsStateWithLifecycle()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isMovie = vm.type == ContentType.MOVIE

    if (featured.isEmpty() && recent.isEmpty() && categories.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(top = homeTopInset())) {
            CategoryChips(categories, SpecialCat.ALL, vm.type, vm::select)
            EmptyView(stringResource(R.string.empty_content))
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "hero") {
            if (featured.isNotEmpty()) {
                FullBleedHero(
                    items = featured,
                    favorites = favorites,
                    heroInfo = vm::heroInfo,
                    onPlay = { c ->
                        if (isMovie) {
                            PlayerLauncher.play(context, PlaybackRequest(c.subscriptionId, ContentType.MOVIE, c.remoteId, c.name, c.logo))
                        } else vm.open(c, onOpen)
                    },
                    onDetails = { c -> vm.open(c, onOpen) },
                    onFavorite = vm::toggleFavorite,
                )
            } else {
                Spacer(Modifier.height(homeTopInset()))
            }
        }
        item(key = "chips") {
            CategoryChips(categories, SpecialCat.ALL, vm.type, vm::select)
        }
        if (continueList.isNotEmpty()) {
            item(key = "continue") {
                RowSection(stringResource(R.string.continue_watching), accent = true) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueList, key = { it.historyKey }) { h -> WideContinueCard(h, artwork = { vm.backdropOf(h) }) { PlayerLauncher.play(context, resumeRequest(h)) } }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                RowSection(stringResource(R.string.cat_recently_added), onSeeAll = { vm.select(SpecialCat.RECENT) }) {
                    PosterRow(recent, favorites) { vm.open(it, onOpen) }
                }
            }
        }
        items(categories, key = { "cat_" + it.id }) { cat ->
            CategoryRow(vm, cat, favorites, onOpen)
        }
    }
}

@Composable
private fun CategoryRow(vm: VodViewModel, cat: CategoryEntity, favorites: Set<String>, onOpen: (Long) -> Unit) {
    val flow = remember(cat.remoteId) { vm.row(cat.remoteId) }
    val countFlow = remember(cat.remoteId) { vm.count(cat.remoteId) }
    val items by flow.collectAsStateWithLifecycle(emptyList())
    val count by countFlow.collectAsStateWithLifecycle(0)
    if (items.isEmpty()) {
        Spacer(Modifier.height(1.dp))
        return
    }
    RowSection(cat.name, count = count, onSeeAll = { vm.select(cat.remoteId) }) {
        PosterRow(items, favorites) { vm.open(it, onOpen) }
    }
}

@Composable
internal fun RowSection(
    title: String,
    count: Int = 0,
    accent: Boolean = false,
    onSeeAll: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(top = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(4.dp).height(20.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (accent) Brush.verticalGradient(listOf(Color(0xFFFF4D6D), Color(0xFF9B6CFF))) else NovaGradient)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (onSeeAll != null) {
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier
                        .novaFocus(shape)
                        .clip(shape)
                        .clickable(onClick = onSeeAll)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (count > 0) stringResource(R.string.see_all_n, count) else stringResource(R.string.see_all),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        content()
    }
}

@Composable
internal fun PosterRow(list: List<ContentEntity>, favorites: Set<String>, onClick: (ContentEntity) -> Unit) {
    val w = posterWidth()
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { "${it.type}|${it.remoteId}" }) { c ->
            PosterCard(
                title = c.name,
                image = c.logo,
                subtitle = c.year,
                rating = c.rating,
                favorite = favKey(c.type, c.remoteId) in favorites,
                type = c.type,
                year = c.year,
                modifier = Modifier.width(w),
                onClick = { onClick(c) },
            )
        }
    }
}

@Composable
private fun CategoryGrid(vm: VodViewModel, selected: String, gridState: LazyGridState, onOpen: (Long) -> Unit) {
    val paged = vm.items.collectAsLazyPagingItems()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val w = posterWidth()
    when {
        paged.itemCount == 0 && paged.loadState.refresh is LoadState.Loading -> LoadingView()
        paged.itemCount == 0 -> EmptyView(
            stringResource(if (selected == SpecialCat.FAV) R.string.empty_favorites else R.string.empty_content)
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(w),
            state = gridState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = paged.itemCount,
                key = paged.itemKey { c -> if (c.id != 0L) c.id else "${c.type}|${c.remoteId}" },
            ) { index ->
                val c = paged[index] ?: return@items
                PosterCard(
                    title = c.name,
                    image = c.logo,
                    subtitle = c.year,
                    rating = c.rating,
                    favorite = favKey(vm.type, c.remoteId) in favorites,
                    type = vm.type,
                    year = c.year,
                    onClick = { vm.open(c, onOpen) },
                )
            }
        }
    }
}
