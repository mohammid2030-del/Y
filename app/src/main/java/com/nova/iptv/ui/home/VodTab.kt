@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nova.iptv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nova.iptv.R
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.HistoryEntity
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.player.PlayerLauncher
import com.nova.iptv.ui.components.ChannelLogo
import com.nova.iptv.ui.components.EmptyView
import com.nova.iptv.ui.components.LoadingView
import com.nova.iptv.ui.components.PosterCard
import com.nova.iptv.ui.components.SectionTitle
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.LocalNovaFlags

fun resumeRequest(h: HistoryEntity) = PlaybackRequest(
    subscriptionId = h.subscriptionId,
    type = h.type,
    remoteId = h.remoteId,
    title = h.title,
    logo = h.logo,
    episodeId = h.episodeId,
    episodeExt = h.containerExt,
    subtitle = h.subtitle,
)

@Composable
fun VodTab(vm: VodViewModel, onOpen: (Long) -> Unit) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val paged = vm.items.collectAsLazyPagingItems()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val continueList by vm.continueWatching.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cell = if (LocalNovaFlags.current.isTv) 150.dp else 112.dp

    Column(Modifier.fillMaxSize()) {
        CategoryChips(categories, selected, vm.type, vm::select)
        when {
            paged.itemCount == 0 && continueList.isEmpty() && paged.loadState.refresh is LoadState.Loading -> LoadingView()
            paged.itemCount == 0 && continueList.isEmpty() -> EmptyView(
                stringResource(if (selected == SpecialCat.FAV) R.string.empty_favorites else R.string.empty_content)
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(cell),
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (continueList.isNotEmpty() && selected == SpecialCat.ALL) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        Column {
                            SectionTitle(stringResource(R.string.continue_watching), Modifier.padding(horizontal = 0.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(continueList, key = { it.historyKey }) { h ->
                                    PosterCard(
                                        title = h.title,
                                        image = h.logo,
                                        subtitle = h.subtitle,
                                        progress = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else null,
                                        modifier = Modifier.width(cell),
                                        onClick = { PlayerLauncher.play(context, resumeRequest(h)) },
                                    )
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "all_title") {
                        SectionTitle(stringResource(if (vm.type == ContentType.MOVIE) R.string.tab_movies else R.string.tab_series))
                    }
                }
                items(
                    count = paged.itemCount,
                    key = paged.itemKey { c -> if (c.id != 0L) c.id else "${c.type}|${c.remoteId}" },
                ) { index ->
                    val c = paged[index] ?: return@items
                    PosterCard(
                        title = c.name,
                        image = c.logo,
                        subtitle = listOf(c.year, c.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }.orEmpty())
                            .filter { it.isNotBlank() }.joinToString("  "),
                        favorite = favKey(vm.type, c.remoteId) in favorites,
                        onClick = { vm.open(c, onOpen) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeLabel(type: String): String = stringResource(
    when (type) {
        ContentType.LIVE -> R.string.tab_live
        ContentType.MOVIE -> R.string.type_movie
        else -> R.string.type_series
    }
)

@Composable
private fun ContentRow(
    title: String,
    subtitle: String,
    logo: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
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
        ChannelLogo(logo, Modifier.size(width = 56.dp, height = 56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

private fun openContent(
    context: android.content.Context,
    c: ContentEntity,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
) {
    when (c.type) {
        ContentType.LIVE -> PlayerLauncher.play(
            context,
            PlaybackRequest(c.subscriptionId, ContentType.LIVE, c.remoteId, c.name, c.logo, categoryId = c.categoryId),
        )
        ContentType.MOVIE -> onOpenMovie(c.id)
        else -> onOpenSeries(c.id)
    }
}

@Composable
fun FavoritesTab(onOpenMovie: (Long) -> Unit, onOpenSeries: (Long) -> Unit, vm: FavoritesViewModel = hiltViewModel()) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filters = listOf("" to R.string.cat_all, ContentType.LIVE to R.string.tab_live, ContentType.MOVIE to R.string.tab_movies, ContentType.SERIES to R.string.tab_series)

    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(12.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filters) { (t, label) ->
                FilterChip(
                    selected = filter == t,
                    onClick = { vm.filter.value = t },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.novaFocus(RoundedCornerShape(8.dp)),
                )
            }
        }
        if (favorites.isEmpty()) {
            EmptyView(stringResource(R.string.empty_favorites))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(favorites, key = { _, f -> "${f.type}|${f.remoteId}" }) { i, f ->
                    ContentRow(
                        title = f.name,
                        subtitle = TypeLabel(f.type),
                        logo = f.logo,
                        onClick = { vm.open(f) { c -> openContent(context, c, onOpenMovie, onOpenSeries) } },
                        trailing = {
                            if (filter.isEmpty()) {
                                IconButton(onClick = { vm.move(f, -1) }, enabled = i > 0) {
                                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up))
                                }
                                IconButton(onClick = { vm.move(f, 1) }, enabled = i < favorites.lastIndex) {
                                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down))
                                }
                            }
                            IconButton(onClick = { vm.remove(f) }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.remove))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SearchTab(onOpenMovie: (Long) -> Unit, onOpenSeries: (Long) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val types = listOf("" to R.string.cat_all, ContentType.LIVE to R.string.tab_live, ContentType.MOVIE to R.string.tab_movies, ContentType.SERIES to R.string.tab_series)
    var catMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, stringResource(R.string.clear)) }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(types) { (t, label) ->
                FilterChip(
                    selected = state.type == t,
                    onClick = { vm.setType(t) },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.novaFocus(RoundedCornerShape(8.dp)),
                )
            }
            if (state.type.isNotEmpty() && categories.isNotEmpty()) {
                item {
                    Box {
                        AssistChip(
                            onClick = { catMenu = true },
                            label = {
                                Text(
                                    categories.firstOrNull { it.remoteId == state.categoryId }?.name
                                        ?: stringResource(R.string.all_categories),
                                    maxLines = 1,
                                )
                            },
                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                            modifier = Modifier.novaFocus(RoundedCornerShape(8.dp)),
                        )
                        DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.all_categories)) },
                                onClick = { catMenu = false; vm.setCategory("") },
                            )
                            categories.forEach { c ->
                                DropdownMenuItem(text = { Text(c.name) }, onClick = { catMenu = false; vm.setCategory(c.remoteId) })
                            }
                        }
                    }
                }
            }
        }
        val list = results
        when {
            list == null -> EmptyView(stringResource(R.string.search_prompt), icon = Icons.Filled.Search)
            list.isEmpty() -> EmptyView(stringResource(R.string.no_results), icon = Icons.Filled.Search)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { _, c -> c.id }) { _, c ->
                    ContentRow(
                        title = c.name,
                        subtitle = TypeLabel(c.type) + if (c.year.isNotBlank()) " • ${c.year}" else "",
                        logo = c.logo,
                        onClick = { openContent(context, c, onOpenMovie, onOpenSeries) },
                    )
                }
            }
        }
    }
}
