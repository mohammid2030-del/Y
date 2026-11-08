@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nova.iptv.ui.home

import com.nova.iptv.ui.components.NovaChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
    // Scroll positions live in the ViewModel so they survive opening a title and coming back.
    val listState = vm.discoverState
    val gridState = vm.gridState
    LaunchedEffect(selected) {
        if (selected != SpecialCat.ALL && vm.gridCategory != selected) {
            vm.gridCategory = selected
            gridState.scrollToItem(0)
        }
    }
    // Back from a category returns to the rows, exactly where you were.
    androidx.activity.compose.BackHandler(enabled = selected != SpecialCat.ALL) { vm.select(SpecialCat.ALL) }
    OnReselect {
        when {
            selected == SpecialCat.ALL -> listState.smoothScrollToTop()
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 -> gridState.smoothScrollToTop()
            else -> vm.select(SpecialCat.ALL)
        }
    }
    if (selected == SpecialCat.ALL) {
        DiscoverView(vm, categories, listState, onOpen)
    } else if (selected.startsWith(SHELF)) {
        ShelfBrowser(vm, selected.removePrefix(SHELF), onOpen)
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
    val shelfRows by vm.shelfRows.collectAsStateWithLifecycle()
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
            ShelfChips(vm, SpecialCat.ALL)
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
        if (shelfRows.isNotEmpty()) {
            items(shelfRows, key = { "shelf_" + it.first.id }) { (shelf, list) ->
                if (list.isNotEmpty()) RowSection(shelf.title, count = shelf.count, onSeeAll = { vm.openShelf(shelf.id) }) {
                    LibRow(vm, list, favorites, onOpen)
                }
            }
        } else {
            items(categories, key = { "cat_" + it.id }) { cat ->
                CategoryRow(vm, cat, favorites, onOpen)
            }
        }
    }
}

@Composable
private fun CategoryRow(vm: VodViewModel, cat: CategoryEntity, favorites: Set<String>, onOpen: (Long) -> Unit) {
    val flow = remember(cat.remoteId) { vm.row(cat.remoteId) }
    val countFlow = remember(cat.remoteId) { vm.count(cat.remoteId) }
    val items by flow.collectAsStateWithLifecycle()
    val count by countFlow.collectAsStateWithLifecycle()
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

// ------------------------------------------------------------------ Netflix-style shelves

/** Platforms / regions (Netflix, Shahid, Turkish…) plus favourites and recently added. */
@Composable
internal fun ShelfChips(vm: VodViewModel, selected: String) {
    val idx by vm.index.collectAsStateWithLifecycle()
    val shelves = idx?.shelves.orEmpty()
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(listOf(SpecialCat.ALL, SpecialCat.FAV, SpecialCat.RECENT)) { id ->
            NovaChip(specialCategoryLabel(id, vm.type), selected == id, { vm.select(id) })
        }
        items(shelves, key = { it.id }) { sh ->
            NovaChip(sh.title, selected == SHELF + sh.id, { vm.openShelf(sh.id) })
        }
    }
}

@Composable
private fun LibRow(vm: VodViewModel, list: List<com.nova.iptv.data.db.LibItem>, favorites: Set<String>, onOpen: (Long) -> Unit) {
    val w = posterWidth()
    LaunchedEffect(list) { vm.identify(list) }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { c -> LibCard(vm, c, favorites, Modifier.width(w), onOpen) }
    }
}

@Composable
private fun LibCard(vm: VodViewModel, c: com.nova.iptv.data.db.LibItem, favorites: Set<String>, modifier: Modifier, onOpen: (Long) -> Unit) {
    val n = remember(c.id, vm.libraryTick.collectAsStateWithLifecycle().value) { vm.versionCount(c) }
    Box(modifier) {
        PosterCard(
            title = c.name, image = c.logo, subtitle = c.year, rating = c.rating,
            favorite = favKey(vm.type, c.remoteId) in favorites, type = vm.type, year = c.year,
            onClick = { vm.openLib(c, onOpen) },
        )
        if (n > 1) GlassChip(stringResource(R.string.versions_n, n), Modifier.align(Alignment.TopEnd).padding(10.dp))
    }
}

/** One shelf: its categories, genre filters (with reset), and one card per title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfBrowser(vm: VodViewModel, shelfId: String, onOpen: (Long) -> Unit) {
    val idx by vm.index.collectAsStateWithLifecycle()
    val list by vm.libItems.collectAsStateWithLifecycle()
    val cat by vm.libCategory.collectAsStateWithLifecycle()
    val genre by vm.libGenre.collectAsStateWithLifecycle()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val shelf = idx?.shelves?.firstOrNull { it.id == shelfId }
    val series = vm.type == ContentType.SERIES
    val w = posterWidth()
    LaunchedEffect(shelfId, cat, genre) { vm.libGridState.scrollToItem(0) }
    LaunchedEffect(list) { list?.let { vm.identify(it.take(240)) } }
    Column(Modifier.fillMaxSize().padding(top = homeTopInset())) {
        ShelfChips(vm, SHELF + shelfId)
        if (shelf != null) LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { NovaChip(stringResource(R.string.lib_all_of, shelf.title), cat == null, { vm.libCategory.value = null }) }
            items(shelf.categories, key = { it.id }) { c -> NovaChip(c.name, cat == c.remoteId, { vm.libCategory.value = c.remoteId }) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (genre != null) item { NovaChip("✕ " + stringResource(R.string.genre_reset), false, { vm.libGenre.value = null }) }
            items(com.nova.iptv.data.library.Genre.available(series)) { g ->
                NovaChip(genreLabel(g), genre == g, { vm.libGenre.value = if (genre == g) null else g })
            }
        }
        val l = list
        when {
            l == null -> LoadingView()
            l.isEmpty() -> EmptyView(stringResource(R.string.empty_content))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(w), state = vm.libGridState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(l.size, key = { l[it].id }) { i -> LibCard(vm, l[i], favorites, Modifier, onOpen) }
            }
        }
    }
}

@Composable
internal fun genreLabel(g: com.nova.iptv.data.library.Genre): String = stringResource(
    when (g) {
        com.nova.iptv.data.library.Genre.ACTION -> R.string.g_action
        com.nova.iptv.data.library.Genre.DRAMA -> R.string.g_drama
        com.nova.iptv.data.library.Genre.COMEDY -> R.string.g_comedy
        com.nova.iptv.data.library.Genre.ROMANCE -> R.string.g_romance
        com.nova.iptv.data.library.Genre.THRILLER -> R.string.g_thriller
        com.nova.iptv.data.library.Genre.HORROR -> R.string.g_horror
        com.nova.iptv.data.library.Genre.CRIME -> R.string.g_crime
        com.nova.iptv.data.library.Genre.SCIFI -> R.string.g_scifi
        com.nova.iptv.data.library.Genre.ADVENTURE -> R.string.g_adventure
        com.nova.iptv.data.library.Genre.ANIMATION -> R.string.g_animation
        com.nova.iptv.data.library.Genre.FAMILY -> R.string.g_family
        com.nova.iptv.data.library.Genre.MYSTERY -> R.string.g_mystery
        com.nova.iptv.data.library.Genre.WAR -> R.string.g_war
        com.nova.iptv.data.library.Genre.DOCUMENTARY -> R.string.g_documentary
    }
)
