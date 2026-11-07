@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nova.iptv.ui.vod

import androidx.compose.foundation.background
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
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.network.XEpisode
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.player.PlayerLauncher
import com.nova.iptv.ui.components.EmptyView
import com.nova.iptv.ui.components.ErrorView
import com.nova.iptv.ui.components.GradientBadge
import com.nova.iptv.ui.components.LoadingView
import com.nova.iptv.ui.components.NetImage
import com.nova.iptv.ui.components.novaFocus

@Composable
private fun DetailHeader(
    backdrop: String,
    poster: String,
    title: String,
    meta: List<String>,
    rating: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val bg = MaterialTheme.colorScheme.background
    Column(Modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth()) {
        NetImage(
            backdrop.ifBlank { poster },
            Modifier.fillMaxWidth().aspectRatio(if (wide) 2.6f else 1.5f),
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
            poster,
            Modifier
                .width(if (wide) 160.dp else 110.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rating.isNotBlank()) {
                    GradientBadge("★ $rating")
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    meta.filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(14.dp))
            actions()
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetailScreen(onBack: () -> Unit, vm: MovieDetailViewModel = hiltViewModel()) {
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
    val req = PlaybackRequest(c.subscriptionId, ContentType.MOVIE, c.remoteId, c.name, info?.image?.ifBlank { null } ?: c.logo)
    val h = history
    val canResume = h != null && h.positionMs > 30_000 && (h.durationMs == 0L || h.positionMs < h.durationMs * 0.95)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        DetailHeader(
            backdrop = info?.backdrop.orEmpty(),
            poster = info?.image?.ifBlank { null } ?: c.logo,
            title = c.name,
            meta = listOf(c.year.ifBlank { info?.releaseDate?.take(4).orEmpty() }, info?.duration.orEmpty(), info?.genre.orEmpty()),
            rating = c.rating.ifBlank { info?.rating.orEmpty() },
            onBack = onBack,
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
                IconButton(onClick = vm::toggleFavorite, modifier = Modifier.novaFocus(CircleShape)) {
                    Icon(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        stringResource(R.string.favorite),
                        tint = if (fav) Color(0xFFFF4D6D) else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (canResume && h != null && h.durationMs > 0) {
            LinearProgressIndicator(
                progress = { h.positionMs.toFloat() / h.durationMs },
                modifier = Modifier.padding(16.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        val plot = info?.plot?.ifBlank { null } ?: c.plot
        if (plot.isNotBlank()) {
            Text(plot, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        info?.cast?.takeIf { it.isNotBlank() }?.let {
            Text(
                stringResource(R.string.cast) + ": " + it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        info?.director?.takeIf { it.isNotBlank() }?.let {
            Text(
                stringResource(R.string.director) + ": " + it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesDetailScreen(onBack: () -> Unit, vm: SeriesDetailViewModel = hiltViewModel()) {
    val item by vm.item.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val last by vm.lastWatched.collectAsStateWithLifecycle()
    val fav by vm.isFavorite.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = item
    val info = vm.info

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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            DetailHeader(
                backdrop = "",
                poster = info?.cover?.ifBlank { null } ?: c.logo,
                title = c.name,
                meta = listOf(c.year.ifBlank { info?.releaseDate?.take(4).orEmpty() }, info?.genre.orEmpty()),
                rating = c.rating.ifBlank { info?.rating.orEmpty() },
                onBack = onBack,
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
        val plot = info?.plot?.ifBlank { null } ?: c.plot
        if (plot.isNotBlank()) {
            item { Text(plot, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
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
                            progress = if (h != null && h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else null,
                            onClick = { PlayerLauncher.play(context, request(e)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: XEpisode, progress: Float?, onClick: () -> Unit) {
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
            NetImage(episode.image, Modifier.fillMaxSize())
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
                "${episode.episodeNum}. " + episode.title.ifBlank { stringResource(R.string.episode_n, episode.episodeNum) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.durationSecs > 0) {
                Text(
                    stringResource(R.string.minutes_n, episode.durationSecs / 60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (episode.plot.isNotBlank()) {
                Text(
                    episode.plot,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
