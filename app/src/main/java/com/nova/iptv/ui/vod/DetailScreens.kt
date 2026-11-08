@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nova.iptv.ui.vod

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import com.nova.iptv.data.tmdb.TmdbEpisode
import com.nova.iptv.data.tmdb.TmdbInfo
import com.nova.iptv.data.tmdb.TmdbPerson
import com.nova.iptv.ui.components.TitleLogo
import com.nova.iptv.ui.components.cleanTitle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.R
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.network.XEpisode
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.player.PlayerLauncher
import com.nova.iptv.ui.components.EmptyView
import com.nova.iptv.ui.components.ErrorView
import com.nova.iptv.ui.components.GradientBadge
import com.nova.iptv.ui.components.LoadingView
import com.nova.iptv.ui.components.NetImage
import com.nova.iptv.ui.components.PosterCard
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.NovaGradient

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeader(
    backdrop: String,
    poster: String,
    title: String,
    meta: List<String>,
    rating: String,
    onBack: () -> Unit,
    tmdb: TmdbInfo? = null,
    actions: @Composable () -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val bg = MaterialTheme.colorScheme.background
    val bd = tmdb?.backdrop?.ifBlank { null } ?: backdrop
    val ps = tmdb?.poster?.ifBlank { null } ?: poster
    val shownTitle = tmdb?.title?.ifBlank { null } ?: title.cleanTitle()
    val shownRating = tmdb?.ratingText?.ifBlank { null } ?: rating
    Column(Modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth()) {
        NetImage(
            bd.ifBlank { ps },
            Modifier.fillMaxWidth().aspectRatio(if (wide) 2.4f else 1.4f)
                .then(if (bd.isBlank()) Modifier.blur(26.dp) else Modifier),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, bg)))
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
    }
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        NetImage(
            ps,
            Modifier
                .width(if (wide) 170.dp else 115.dp)
                .aspectRatio(2f / 3f)
                .shadow(16.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            TitleLogo(
                logo = tmdb?.logo.orEmpty(),
                modifier = Modifier.height(if (wide) 90.dp else 64.dp).fillMaxWidth(),
            ) {
                Text(shownTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            val original = tmdb?.originalTitle.orEmpty()
            if (tmdb?.logo.orEmpty().isNotBlank() || (original.isNotBlank() && original != shownTitle)) {
                Text(
                    if (tmdb?.logo.orEmpty().isNotBlank()) shownTitle else original,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (shownRating.isNotBlank() && shownRating != "0") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GradientBadge("★ $shownRating")
                        if (tmdb != null && tmdb.votes > 0) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "TMDB · " + stringResource(R.string.tmdb_votes, tmdb.votes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                tmdb?.certification?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Text(
                    meta.filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tmdb != null && tmdb.genres.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tmdb.genres.take(4).forEach { g ->
                        Text(
                            g,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            actions()
        }
    }
    tmdb?.tagline?.takeIf { it.isNotBlank() }?.let {
        Text(
            "“$it”",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
        )
    }
    }
}

/** Cast with photos from TMDB. */
@Composable
private fun CastRow(cast: List<TmdbPerson>, onPerson: (TmdbPerson) -> Unit = {}) {
    if (cast.isEmpty()) return
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(NovaGradient))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.cast), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cast, key = { it.name + it.role }) { p ->
                Column(
                    Modifier.width(88.dp).novaFocus(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = p.id > 0) { onPerson(p) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NetImage(
                        p.photo,
                        Modifier.size(80.dp).clip(CircleShape),
                        fallback = Icons.Filled.Person,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        p.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    if (p.role.isNotBlank()) {
                        Text(
                            p.role, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrailerButton(key: String) {
    if (key.isBlank()) return
    val context = LocalContext.current
    OutlinedButton(
        onClick = { com.nova.iptv.player.TrailerActivity.start(context, key) },
        modifier = Modifier.novaFocus(),
    ) {
        Icon(Icons.Filled.Movie, null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.trailer))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetailScreen(onBack: () -> Unit, onOpenMovie: (Long) -> Unit = {}, onOpenSeries: (Long) -> Unit = {}, vm: MovieDetailViewModel = hiltViewModel()) {
    val similar by vm.similar.collectAsStateWithLifecycle()
    val item by vm.item.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val fav by vm.isFavorite.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = item
    if (c == null) {
        if (vm.notFound) EmptyView(stringResource(R.string.empty_content)) else LoadingView()
        return
    }
    val info = vm.info
    val tmdb = vm.tmdb
    val req = PlaybackRequest(c.subscriptionId, ContentType.MOVIE, c.remoteId, c.name, info?.image?.ifBlank { null } ?: c.logo)
    val h = history
    val canResume = h != null && h.positionMs > 30_000 && (h.durationMs == 0L || h.positionMs < h.durationMs * 0.95)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        DetailHeader(
            backdrop = info?.backdrop.orEmpty(),
            poster = info?.image?.ifBlank { null } ?: c.logo,
            title = c.name,
            meta = listOf(
                tmdb?.year?.ifBlank { null } ?: c.year.ifBlank { info?.releaseDate?.take(4).orEmpty() },
                tmdb?.runtimeMin?.takeIf { it > 0 }?.let { stringResource(R.string.minutes_n, it) } ?: info?.duration.orEmpty(),
                if (tmdb == null) info?.genre.orEmpty() else "",
            ),
            rating = c.rating.ifBlank { info?.rating.orEmpty() },
            onBack = onBack,
            tmdb = tmdb,
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { PlayerLauncher.play(context, req) }, modifier = Modifier.novaFocus()) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (canResume) R.string.resume else R.string.play))
                }
                if (canResume) {
                    OutlinedButton(onClick = { PlayerLauncher.play(context, req, fromStart = true) }, modifier = Modifier.novaFocus()) {
                        Icon(Icons.Filled.Replay, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.start_over))
                    }
                }
                TrailerButton(tmdb?.trailerKey.orEmpty())
                VersionsButton(vm.extras)
                IconButton(onClick = vm::toggleFavorite, modifier = Modifier.novaFocus(CircleShape)) {
                    Icon(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        stringResource(R.string.favorite),
                        tint = if (fav) Color(0xFFFF4D6D) else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        ExtrasDialogs(vm.extras, c.id, c.subscriptionId, vm::pick, onOpenMovie, onOpenSeries)
        if (canResume && h != null && h.durationMs > 0) {
            LinearProgressIndicator(
                progress = { h.positionMs.toFloat() / h.durationMs },
                modifier = Modifier.padding(16.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        val plot = tmdb?.overview?.ifBlank { null } ?: info?.plot?.ifBlank { null } ?: c.plot
        if (plot.isNotBlank()) {
            Text(plot, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        if (tmdb != null && tmdb.cast.isNotEmpty()) CastRow(tmdb.cast) { vm.extras.openPerson(it, c.subscriptionId) }
        else info?.cast?.takeIf { it.isNotBlank() }?.let {
            Text(
                stringResource(R.string.cast) + ": " + it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        (tmdb?.creators?.takeIf { it.isNotEmpty() }?.joinToString("، ") ?: info?.director)?.takeIf { it.isNotBlank() }?.let {
            Text(
                stringResource(R.string.director) + ": " + it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (similar.isNotEmpty()) {
            SimilarRow(similar) { onOpenMovie(it.id) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SimilarRow(list: List<ContentEntity>, onClick: (ContentEntity) -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(NovaGradient))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.more_like_this), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { c ->
                PosterCard(
                    title = c.name, image = c.logo, subtitle = c.year, rating = c.rating,
                    type = c.type, year = c.year,
                    modifier = Modifier.width(118.dp), onClick = { onClick(c) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesDetailScreen(onBack: () -> Unit, onOpenSeries: (Long) -> Unit = {}, onOpenMovie: (Long) -> Unit = {}, vm: SeriesDetailViewModel = hiltViewModel()) {
    val similar by vm.similar.collectAsStateWithLifecycle()
    val item by vm.item.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val last by vm.lastWatched.collectAsStateWithLifecycle()
    val fav by vm.isFavorite.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = item
    val info = vm.info
    val tmdb = vm.tmdb
    val tmdbEpisodes = vm.tmdbEpisodes

    if (c == null) {
        if (vm.error != null) ErrorView(vm.error, onRetry = { vm.load(true) }) else LoadingView()
        return
    }

    fun request(e: XEpisode) = PlaybackRequest(
        subscriptionId = c.subscriptionId,
        type = ContentType.SERIES,
        remoteId = c.remoteId,
        title = c.name,
        logo = info?.cover?.ifBlank { null } ?: c.logo,
        episodeId = e.id,
        episodeExt = e.ext,
        subtitle = "S${e.season} E${e.episodeNum}" + if (e.title.isNotBlank()) " — ${e.title}" else "",
    )

    ExtrasDialogs(vm.extras, c.id, c.subscriptionId, vm::pick, onOpenMovie, onOpenSeries)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            DetailHeader(
                backdrop = info?.backdrop.orEmpty(),
                poster = info?.cover?.ifBlank { null } ?: c.logo,
                title = c.name,
                meta = listOf(
                    tmdb?.year?.ifBlank { null } ?: c.year.ifBlank { info?.releaseDate?.take(4).orEmpty() },
                    tmdb?.seasons?.takeIf { it > 0 }?.let { stringResource(R.string.seasons_count, it) }.orEmpty(),
                    if (tmdb == null) info?.genre.orEmpty() else "",
                ),
                rating = c.rating.ifBlank { info?.rating.orEmpty() },
                onBack = onBack,
                tmdb = tmdb,
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val l = last
                    if (l != null && l.episodeId.isNotEmpty()) {
                        Button(
                            onClick = {
                                PlayerLauncher.play(
                                    context,
                                    PlaybackRequest(
                                        l.subscriptionId, ContentType.SERIES, l.remoteId, l.title, l.logo,
                                        episodeId = l.episodeId, episodeExt = l.containerExt, subtitle = l.subtitle,
                                    ),
                                )
                            },
                            modifier = Modifier.novaFocus(),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.continue_episode, l.subtitle.substringBefore(" —")))
                        }
                    }
                    TrailerButton(tmdb?.trailerKey.orEmpty())
                    VersionsButton(vm.extras)
                    IconButton(onClick = vm::toggleFavorite, modifier = Modifier.novaFocus(CircleShape)) {
                        Icon(
                            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            stringResource(R.string.favorite),
                            tint = if (fav) Color(0xFFFF4D6D) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        val plot = tmdb?.overview?.ifBlank { null } ?: info?.plot?.ifBlank { null } ?: c.plot
        if (plot.isNotBlank()) {
            item { Text(plot, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
        }
        if (tmdb != null && tmdb.cast.isNotEmpty()) {
            item(key = "cast") { CastRow(tmdb.cast) { vm.extras.openPerson(it, c.subscriptionId) } }
        }
        when {
            vm.loading && info == null -> item { Box(Modifier.fillMaxWidth().height(160.dp)) { LoadingView() } }
            vm.error != null && info == null -> item {
                Box(Modifier.fillMaxWidth().height(260.dp)) { ErrorView(vm.error, onRetry = { vm.load(true) }) }
            }
            info != null -> {
                val seasons = info.seasons.keys.toList()
                if (seasons.isEmpty()) {
                    item { Box(Modifier.height(200.dp)) { EmptyView(stringResource(R.string.no_episodes)) } }
                } else {
                    item {
                        ScrollableTabRow(
                            selectedTabIndex = seasons.indexOf(vm.selectedSeason).coerceAtLeast(0),
                            edgePadding = 12.dp,
                        ) {
                            seasons.forEach { s ->
                                Tab(
                                    selected = s == vm.selectedSeason,
                                    onClick = { vm.selectedSeason = s },
                                    text = { Text(stringResource(R.string.season_n, s)) },
                                )
                            }
                        }
                    }
                    val episodes = info.seasons[vm.selectedSeason].orEmpty()
                    items(episodes, key = { it.id }) { e ->
                        val h = progress[e.id]
                        EpisodeRow(
                            episode = e,
                            tmdb = tmdbEpisodes[e.episodeNum],
                            progress = if (h != null && h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else null,
                            onClick = { PlayerLauncher.play(context, request(e)) },
                        )
                    }
                }
            }
        }
        if (similar.isNotEmpty()) {
            item(key = "similar") { SimilarRow(similar) { onOpenSeries(it.id) } }
        }
    }
}

@Composable
private fun EpisodeRow(episode: XEpisode, tmdb: TmdbEpisode?, progress: Float?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .novaFocus(shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            NetImage(tmdb?.still?.ifBlank { null } ?: episode.image, Modifier.fillMaxSize())
            Icon(
                Icons.Filled.PlayArrow, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${episode.episodeNum}. " + (tmdb?.name?.ifBlank { null } ?: episode.title.ifBlank { stringResource(R.string.episode_n, episode.episodeNum) }),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val minutes = if (episode.durationSecs > 0) episode.durationSecs / 60 else tmdb?.runtimeMin ?: 0
            if (minutes > 0) {
                Text(
                    stringResource(R.string.minutes_n, minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val epPlot = tmdb?.overview?.ifBlank { null } ?: episode.plot
            if (epPlot.isNotBlank()) {
                Text(
                    epPlot,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


@Composable
private fun VersionsButton(extras: DetailExtras) {
    if (extras.versions.size < 2) return
    OutlinedButton(onClick = { extras.choosing = true }, modifier = Modifier.novaFocus()) {
        Text(stringResource(R.string.versions_n, extras.versions.size))
    }
}

@Composable
private fun ExtrasDialogs(
    extras: DetailExtras, currentId: Long, subId: Long, onPick: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit, onOpenSeries: (Long) -> Unit,
) {
    if (extras.choosing && extras.versions.size > 1) {
        VersionDialog(extras.versions, currentId, onPick = { onPick(it.item.id) }, onDismiss = { extras.choosing = false })
    }
    extras.personLoading?.let { PersonLoading(it.name) { extras.closePerson() } }
    extras.person?.let { p ->
        PersonDialog(p, extras.found, onOpen = { w, id ->
            extras.closePerson()
            if (w.isTv) onOpenSeries(id) else onOpenMovie(id)
        }, onDismiss = { extras.closePerson() })
    }
}
