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
import androidx.compose.foundation.lazy.LazyRow
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
    Column(Modifier.fillMaxSize()) {
        CategoryChips(categories, selected, vm.type, vm::select)
        if (selected == SpecialCat.ALL) DiscoverView(vm, categories, onOpen)
        else CategoryGrid(vm, selected, onOpen)
    }
}

@Composable
private fun posterWidth(): Dp {
    val tv = LocalNovaFlags.current.isTv
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    return when {
        tv -> 160.dp
        wide -> 140.dp
        else -> 118.dp
    }
}

@Composable
private fun DiscoverView(vm: VodViewModel, categories: List<CategoryEntity>, onOpen: (Long) -> Unit) {
    val featured by vm.featured.collectAsStateWithLifecycle()
    val recent by vm.recentRow.collectAsStateWithLifecycle()
    val continueList by vm.continueWatching.collectAsStateWithLifecycle()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isMovie = vm.type == ContentType.MOVIE

    if (featured.isEmpty() && recent.isEmpty() && categories.isEmpty()) {
        EmptyView(stringResource(R.string.empty_content))
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (featured.isNotEmpty()) {
            item(key = "hero") {
                HeroCarousel(
                    items = featured,
                    playLabel = stringResource(if (isMovie) R.string.play else R.string.details),
                    onPlay = { c ->
                        if (isMovie) {
                            PlayerLauncher.play(context, PlaybackRequest(c.subscriptionId, ContentType.MOVIE, c.remoteId, c.name, c.logo))
                        } else vm.open(c, onOpen)
                    },
                    onDetails = { c -> vm.open(c, onOpen) },
                )
            }
        }
        if (continueList.isNotEmpty()) {
            item(key = "continue") {
                RowSection(stringResource(R.string.continue_watching), accent = true) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueList, key = { it.historyKey }) { h -> ContinueCard(h) { PlayerLauncher.play(context, resumeRequest(h)) } }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                RowSection(stringResource(R.string.cat_recently_added), onSeeAll = { vm.select(SpecialCat.RECENT) }) {
                    PosterRow(recent, favorites, vm.type) { vm.open(it, onOpen) }
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
        PosterRow(items, favorites, vm.type) { vm.open(it, onOpen) }
    }
}

@Composable
private fun RowSection(
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
private fun PosterRow(list: List<ContentEntity>, favorites: Set<String>, type: String, onClick: (ContentEntity) -> Unit) {
    val w = posterWidth()
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { c ->
            PosterCard(
                title = c.name,
                image = c.logo,
                subtitle = c.year,
                rating = c.rating,
                favorite = favKey(type, c.remoteId) in favorites,
                modifier = Modifier.width(w),
                onClick = { onClick(c) },
            )
        }
    }
}

@Composable
private fun ContinueCard(h: HistoryEntity, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val tv = LocalNovaFlags.current.isTv
    Column(Modifier.width(if (tv) 300.dp else 240.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .novaFocus(shape)
                .shadow(10.dp, shape, clip = false)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
        ) {
            NetImage(h.logo, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))
                )
            )
            Box(
                Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White) }
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(h.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                if (h.subtitle.isNotBlank()) {
                    Text(h.subtitle, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                val p = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(NovaGradient))
                }
            }
        }
    }
}

@Composable
private fun HeroCarousel(
    items: List<ContentEntity>,
    playLabel: String,
    onPlay: (ContentEntity) -> Unit,
    onDetails: (ContentEntity) -> Unit,
) {
    val tv = LocalNovaFlags.current.isTv
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val lowEnd = LocalNovaFlags.current.lowEnd
    val pager = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(6_500)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }

    Column {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = if (wide || tv) 24.dp else 14.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth().height(if (tv) 360.dp else if (wide) 300.dp else 230.dp),
            key = { items[it].id },
        ) { page ->
            HeroCard(items[page], playLabel, lowEnd, onPlay, onDetails)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            items.indices.forEach { i ->
                val active = i == pager.currentPage
                val width by animateDpAsState(if (active) 22.dp else 7.dp, label = "dot")
                val color by animateColorAsState(
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, label = "dotColor",
                )
                Box(
                    Modifier.padding(horizontal = 3.dp).height(7.dp).width(width)
                        .clip(RoundedCornerShape(50)).background(color)
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    c: ContentEntity,
    playLabel: String,
    lowEnd: Boolean,
    onPlay: (ContentEntity) -> Unit,
    onDetails: (ContentEntity) -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            .fillMaxSize()
            .novaFocus(shape)
            .shadow(18.dp, shape)
            .clip(shape)
            .background(Color(0xFF0D0D16))
            .clickable { onDetails(c) }
    ) {
        // Ambient artwork: the poster, blurred and darkened (blur needs Android 12+, elsewhere it's just dimmed)
        NetImage(c.logo, Modifier.fillMaxSize().then(if (lowEnd) Modifier else Modifier.blur(28.dp)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF20A0A12), Color(0xCC0A0A12), Color(0x660A0A12)))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x990A0A12)))
            )
        )
        Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(
                    stringResource(R.string.featured_new),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(NovaGradient)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    c.name,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = c.meta()
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
                }
                if (c.plot.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        c.plot, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pill = RoundedCornerShape(50)
                    Row(
                        Modifier.novaFocus(pill).clip(pill).background(Color.White)
                            .clickable { onPlay(c) }.padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(playLabel, color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                    Row(
                        Modifier.novaFocus(pill).clip(pill).background(Color.White.copy(alpha = 0.16f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), pill)
                            .clickable { onDetails(c) }.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Info, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.details), color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.fillMaxHeight().aspectRatio(2f / 3f)
                    .shadow(20.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
            ) {
                NetImage(c.logo, Modifier.fillMaxSize())
                if (c.rating.isNotBlank()) GlassChip("★ " + c.rating.take(3), Modifier.align(Alignment.TopStart).padding(6.dp))
            }
        }
    }
}

@Composable
private fun CategoryGrid(vm: VodViewModel, selected: String, onOpen: (Long) -> Unit) {
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
                    onClick = { vm.open(c, onOpen) },
                )
            }
        }
    }
}
