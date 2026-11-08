@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nova.iptv.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.R
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.HistoryEntity
import com.nova.iptv.data.repo.HeroInfo
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.player.PlayerLauncher
import com.nova.iptv.ui.components.EmptyView
import com.nova.iptv.ui.components.NetImage
import com.nova.iptv.ui.components.cleanTitle
import com.nova.iptv.ui.components.seasonEpisodeOf
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.LocalNovaFlags
import com.nova.iptv.ui.theme.NovaGradient
import kotlinx.coroutines.delay

/**
 * "Home" — a cinematic showcase: a full-bleed hero that runs under the floating
 * navigation pill, wide continue-watching cards, and rows across live, movies and series.
 */
@Composable
fun DiscoverHome(
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onGoTo: (HomeTab) -> Unit,
    vm: DiscoverViewModel = hiltViewModel(),
) {
    val featured by vm.featured.collectAsStateWithLifecycle()
    val continueList by vm.continueWatching.collectAsStateWithLifecycle()
    val channels by vm.recentChannels.collectAsStateWithLifecycle()
    val movies by vm.latestMovies.collectAsStateWithLifecycle()
    val series by vm.latestSeries.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val favKeys by vm.favoriteKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun open(c: ContentEntity) = vm.open(c) { id ->
        if (c.type == ContentType.SERIES) onOpenSeries(id) else onOpenMovie(id)
    }

    fun playLive(c: ContentEntity) = PlayerLauncher.play(
        context,
        PlaybackRequest(c.subscriptionId, ContentType.LIVE, c.remoteId, c.name, c.logo),
        channelList = channels,
    )

    if (featured.isEmpty() && movies.isEmpty() && series.isEmpty() && channels.isEmpty() && continueList.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(top = homeTopInset())) {
            EmptyView(stringResource(R.string.empty_content), Modifier.weight(1f))
            HomeActionPill(stringResource(R.string.browse_live), Icons.Filled.PlayArrow) { onGoTo(HomeTab.LIVE) }
            Spacer(Modifier.height(32.dp))
        }
        return
    }

    val listState = rememberLazyListState()
    OnReselect { listState.smoothScrollToTop() }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "hero") {
            if (featured.isNotEmpty()) {
                FullBleedHero(
                    items = featured,
                    favorites = favKeys,
                    heroInfo = vm::heroInfo,
                    onPlay = { c ->
                        if (c.type == ContentType.MOVIE) {
                            PlayerLauncher.play(context, PlaybackRequest(c.subscriptionId, ContentType.MOVIE, c.remoteId, c.name, c.logo))
                        } else open(c)
                    },
                    onDetails = ::open,
                    onFavorite = vm::toggleFavorite,
                )
            } else {
                Spacer(Modifier.height(homeTopInset()))
            }
        }
        if (continueList.isNotEmpty()) {
            item(key = "continue") {
                RowSection(stringResource(R.string.continue_watching), accent = true) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(continueList, key = { it.historyKey }) { h ->
                            WideContinueCard(h, artwork = { vm.backdropOf(h) }) { PlayerLauncher.play(context, resumeRequest(h)) }
                        }
                    }
                }
            }
        }
        if (channels.isNotEmpty()) {
            item(key = "live") {
                RowSection(stringResource(R.string.recent_channels), onSeeAll = { onGoTo(HomeTab.LIVE) }) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(channels, key = { "l_" + it.remoteId }) { c -> LiveChannelCard(c) { playLive(c) } }
                    }
                }
            }
        }
        if (movies.isNotEmpty()) {
            item(key = "movies") {
                RowSection(stringResource(R.string.latest_movies), onSeeAll = { onGoTo(HomeTab.MOVIES) }) {
                    PosterRow(movies, favKeys, ::open)
                }
            }
        }
        if (series.isNotEmpty()) {
            item(key = "series") {
                RowSection(stringResource(R.string.latest_series), onSeeAll = { onGoTo(HomeTab.SERIES) }) {
                    PosterRow(series, favKeys, ::open)
                }
            }
        }
        val favContent = favs.filter { it.type != ContentType.LIVE }.map {
            ContentEntity(subscriptionId = it.subscriptionId, type = it.type, remoteId = it.remoteId, name = it.name, categoryId = "", logo = it.logo)
        }
        if (favContent.isNotEmpty()) {
            item(key = "favs") {
                RowSection(stringResource(R.string.cat_favorites), onSeeAll = { onGoTo(HomeTab.FAVORITES) }) {
                    PosterRow(favContent, favKeys, ::open)
                }
            }
        }
    }
}

/** Space reserved at the top of every tab for the status bar + floating navigation pill. */
@Composable
fun homeTopInset(): Dp {
    val density = LocalDensity.current
    val status = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    return status + if (LocalNovaFlags.current.isTv) 84.dp else 72.dp
}

// ───────────────────────────── Hero ─────────────────────────────

@Composable
fun FullBleedHero(
    items: List<ContentEntity>,
    favorites: Set<String>,
    heroInfo: suspend (ContentEntity) -> HeroInfo?,
    onPlay: (ContentEntity) -> Unit,
    onDetails: (ContentEntity) -> Unit,
    onFavorite: (ContentEntity) -> Unit,
) {
    val config = LocalConfiguration.current
    val flags = LocalNovaFlags.current
    val wide = config.screenWidthDp >= 720 || flags.isTv
    val heroHeight = when {
        flags.isTv -> (config.screenHeightDp * 0.78f).dp
        wide -> (config.screenHeightDp * 0.72f).dp.coerceAtLeast(380.dp)
        else -> (config.screenHeightDp * 0.66f).dp.coerceIn(460.dp, 640.dp)
    }
    val pager = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(7_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }

    Box(Modifier.fillMaxWidth().height(heroHeight)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].type + items[it].remoteId }) { page ->
            val c = items[page]
            val info by produceState<HeroInfo?>(null, c.remoteId, c.type) { value = runCatching { heroInfo(c) }.getOrNull() }
            HeroPage(
                c = c,
                info = info,
                wide = wide,
                favorite = favKey(c.type, c.remoteId) in favorites,
                onPlay = { onPlay(c) },
                onDetails = { onDetails(c) },
                onFavorite = { onFavorite(c) },
            )
        }
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(items.size) { i ->
                    val selected = i == pager.currentPage
                    val w by animateDpAsState(if (selected) 22.dp else 6.dp, tween(300), label = "dot")
                    Box(
                        Modifier.height(6.dp).width(w).clip(CircleShape)
                            .background(if (selected) Color.White else Color.White.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPage(
    c: ContentEntity,
    info: HeroInfo?,
    wide: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onFavorite: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    val backdrop = info?.backdrop.orEmpty()
    val isMovie = c.type == ContentType.MOVIE
    Box(Modifier.fillMaxSize()) {
        // Artwork: the real backdrop when the provider has one, otherwise the poster
        // (blurred on wide screens where a portrait image would be stretched).
        AnimatedContent(
            targetState = backdrop,
            transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) },
            label = "backdrop",
            modifier = Modifier.fillMaxSize(),
        ) { b ->
            when {
                b.isNotBlank() -> NetImage(b, Modifier.fillMaxSize())
                wide -> NetImage(c.logo, Modifier.fillMaxSize().blur(28.dp))
                else -> NetImage(c.logo, Modifier.fillMaxSize())
            }
        }
        // Top scrim keeps the floating navigation readable.
        Box(
            Modifier.fillMaxWidth().height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
        )
        // Bottom fade into the page.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Transparent,
                    0.78f to bg.copy(alpha = 0.82f),
                    1f to bg,
                )
            )
        )
        if (wide) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(bg.copy(alpha = 0.85f), Color.Transparent), endX = 1400f)
                )
            )
            if (backdrop.isBlank()) {
                // Sharp poster on the trailing side when we only have portrait art.
                NetImage(
                    c.logo,
                    Modifier.align(Alignment.CenterEnd).padding(end = 56.dp, top = 40.dp)
                        .fillMaxHeight(0.62f).aspectRatio(2f / 3f)
                        .shadow(24.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)),
                )
            }
        }

        val align = if (wide) Alignment.Start else Alignment.CenterHorizontally
        val textAlign = if (wide) TextAlign.Start else TextAlign.Center
        Column(
            Modifier
                .align(if (wide) Alignment.BottomStart else Alignment.BottomCenter)
                .padding(start = if (wide) 48.dp else 20.dp, end = 20.dp, bottom = 30.dp)
                .widthIn(max = if (wide) 560.dp else 600.dp),
            horizontalAlignment = align,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(NovaGradient))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.featured_new) + "  ·  " + stringResource(if (isMovie) R.string.type_movie else R.string.type_series),
                    color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                c.name.cleanTitle(),
                color = Color.White,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = if (wide) 44.sp else 34.sp, lineHeight = if (wide) 48.sp else 38.sp),
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
            )
            val meta = listOf(
                c.year,
                info?.genre.orEmpty().split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" · "),
            ).filter { it.isNotBlank() }
            if (meta.isNotEmpty() || c.rating.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.rating.isNotBlank() && c.rating != "0") {
                        Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC94D), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(c.rating.take(3), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        if (meta.isNotEmpty()) Text("   •   ", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        meta.joinToString("   •   "), color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val plot = info?.plot?.takeIf { it.isNotBlank() } ?: c.plot
            if (plot.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    plot, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (wide) 3 else 2, overflow = TextOverflow.Ellipsis, textAlign = textAlign,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val pill = RoundedCornerShape(50)
                Row(
                    Modifier.novaFocus(pill).shadow(14.dp, pill).clip(pill).background(Color.White)
                        .clickable(onClick = onPlay).padding(horizontal = 26.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (isMovie) Icons.Filled.PlayArrow else Icons.Filled.Info, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (isMovie) R.string.play else R.string.watch_now),
                        color = Color.Black, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    )
                }
                if (isMovie) GlassPill(stringResource(R.string.details), Icons.Filled.Info, onDetails)
                GlassCircle(if (favorite) Icons.Filled.Check else Icons.Filled.Add, stringResource(R.string.favorite), onFavorite)
            }
        }
    }
}

@Composable
private fun GlassPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.novaFocus(shape).clip(shape).background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GlassCircle(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.novaFocus(CircleShape).size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White) }
}

@Composable
private fun HomeActionPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(50)
        Row(
            Modifier.novaFocus(shape).clip(shape).background(NovaGradient).clickable(onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

// ───────────────────────────── Cards ─────────────────────────────

/** Wide 16:9 continue-watching card with a "45% watched" chip and gradient progress. */
@Composable
fun WideContinueCard(h: HistoryEntity, artwork: (suspend () -> String?)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val tv = LocalNovaFlags.current.isTv
    val p = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
    val pct = (p * 100).toInt().coerceIn(1, 99)
    Box(
        Modifier
            .width(if (tv) 360.dp else 290.dp)
            .aspectRatio(16f / 9f)
            .novaFocus(shape)
            .shadow(12.dp, shape, clip = false)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        val backdrop by produceState<String?>(null, h.historyKey) {
            value = artwork?.let { runCatching { it() }.getOrNull() }
        }
        val bd = backdrop
        if (!bd.isNullOrBlank()) {
            NetImage(bd, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize()) {
                // Portrait posters look poor stretched: blur them as a backdrop and show sharp art inside.
                NetImage(h.logo, Modifier.fillMaxSize().blur(18.dp))
                NetImage(
                    h.logo,
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(2f / 3f),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.35f), Color.Transparent))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))
            )
        )
        Text(
            stringResource(R.string.watched_pct, pct),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(
            Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f))
                .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp)) {
            val se = if (h.type == ContentType.SERIES) seasonEpisodeOf(h.subtitle) else null
            if (se != null) {
                Text(
                    stringResource(R.string.season_episode, se.first, se.second),
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
            }
            Text(
                h.title.cleanTitle(), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(50)).background(NovaGradient))
            }
        }
    }
}

/** Channel card: logo on a soft gradient tile with a pulsing-red LIVE badge. */
@Composable
private fun LiveChannelCard(c: ContentEntity, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val tv = LocalNovaFlags.current.isTv
    Column(Modifier.width(if (tv) 200.dp else 150.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .novaFocus(shape)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            NetImage(
                c.logo, Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp),
                contentScale = ContentScale.Fit, fallback = Icons.Filled.PlayArrow,
            )
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFFE53950))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.live_badge), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            c.name.cleanTitle(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 18.dp),
        )
    }
}
