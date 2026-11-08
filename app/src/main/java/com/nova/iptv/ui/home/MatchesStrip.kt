@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nova.iptv.ui.home

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.sports.BroadcastResolver
import com.nova.iptv.data.sports.MatchPrefs
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import com.nova.iptv.data.sports.ChannelPick
import com.nova.iptv.ui.components.ChannelLogo
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nova.iptv.R
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.sports.Match
import com.nova.iptv.data.sports.MatchState
import com.nova.iptv.data.sports.MatchTeam
import com.nova.iptv.data.sports.MatchesRepository
import com.nova.iptv.data.sports.isLive
import com.nova.iptv.player.PlayerLauncher
import com.nova.iptv.ui.components.NetImage
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.LocalNovaFlags
import com.nova.iptv.ui.theme.NovaGradient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

data class MatchesUi(val loading: Boolean = true, val matches: List<Match> = emptyList(), val enabled: Boolean = true)

@HiltViewModel
class MatchesViewModel @Inject constructor(
    private val repo: MatchesRepository,
    private val content: ContentRepository,
    private val settings: SettingsRepository,
    val resolver: BroadcastResolver,
    private val prefs: MatchPrefs,
) : ViewModel() {
    /** Match whose channel list is being shown in the chooser. */
    var sheet by mutableStateOf<Pair<Match, List<ChannelPick>>?>(null)
        private set
    var resolvingId by mutableStateOf<String?>(null)
        private set
    val day = MutableStateFlow(0)
    private var lastKey = ""

    val ui: StateFlow<MatchesUi> = combine(settings.settings.map { it.showMatches }, day, prefs.state) { on, d, p -> Triple(on, d, p) }
        .flatMapLatest { (on, d, p) ->
            if (!on) flowOf(MatchesUi(loading = false, enabled = false))
            else flow {
                val key = "$d|${p.hashCode()}"
                if (key != lastKey) emit(MatchesUi(loading = true))
                lastKey = key
                // Refreshes every 45 s while a match is live, otherwise every 10 minutes (and on each app open).
                while (true) {
                    val list = runCatching { repo.matchesFor(d, p.competitions, p.teams, p.onlyFavorites) }.getOrDefault(emptyList())
                    emit(MatchesUi(loading = false, matches = list))
                    delay(if (list.any { it.isLive }) 45_000 else 600_000)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MatchesUi())

    fun select(d: Int) { day.value = d }

    /** Opens the best channel automatically when certain, otherwise shows a ranked chooser. */
    fun watch(m: Match, onPlay: (PlaybackRequest) -> Unit, onMissing: () -> Unit) {
        if (resolvingId != null) return
        viewModelScope.launch {
            resolvingId = m.id
            val r = runCatching { resolver.resolve(m) }.getOrNull()
            resolvingId = null
            when {
                r == null || r.picks.isEmpty() -> onMissing()
                r.autoPlay != null -> play(m, r.autoPlay.channel, onPlay)
                else -> sheet = m to r.picks
            }
        }
    }

    fun choose(m: Match, ch: ContentEntity, onPlay: (PlaybackRequest) -> Unit) {
        sheet = null
        viewModelScope.launch { play(m, ch, onPlay) }
    }

    fun dismissSheet() { sheet = null }

    private suspend fun play(m: Match, ch: ContentEntity, onPlay: (PlaybackRequest) -> Unit) {
        resolver.remember(m, settings.current().activeSubscriptionId, ch)
        onPlay(PlaybackRequest(ch.subscriptionId, ContentType.LIVE, ch.remoteId, ch.name, ch.logo, categoryId = ch.categoryId))
    }
}

/** Channel opens from 30 minutes before kick-off until the final whistle. */
private const val OPEN_BEFORE_MS = 30 * 60_000L

private fun Match.canOpen(now: Long): Boolean = when (state) {
    MatchState.LIVE, MatchState.HALFTIME -> true
    MatchState.PRE -> now >= startMs - OPEN_BEFORE_MS
    else -> false
}

@Composable
fun MatchesStrip(onCustomize: () -> Unit = {}, vm: MatchesViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!ui.enabled) return
    val noChannel = stringResource(R.string.no_channel_found)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    val arabic = remember { vm.resolver.isArabicUi() }
    val play: (PlaybackRequest) -> Unit = { PlayerLauncher.play(context, it) }

    Column(Modifier.padding(top = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(NovaGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.SportsSoccer, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.matches_title),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            DayChip(stringResource(R.string.today), day == 0) { vm.select(0) }
            Spacer(Modifier.width(6.dp))
            DayChip(stringResource(R.string.tomorrow), day == 1) { vm.select(1) }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.novaFocus(CircleShape).size(34.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onCustomize),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Tune, stringResource(R.string.customize_matches), modifier = Modifier.size(18.dp)) }
        }
        when {
            ui.loading -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(3) { MatchSkeleton() }
            }
            ui.matches.isEmpty() -> Text(
                stringResource(R.string.no_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            )
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ui.matches, key = { it.id }) { m ->
                    MatchCard(
                        m = m,
                        now = now,
                        network = vm.resolver.networkLabel(m.leagueSlug, arabic).ifBlank { if (arabic) "" else m.broadcasters.firstOrNull().orEmpty() },
                        resolving = vm.resolvingId == m.id,
                        onWatch = if (!m.canOpen(now)) null else {
                            {
                                vm.watch(m, onPlay = play, onMissing = { Toast.makeText(context, noChannel, Toast.LENGTH_SHORT).show() })
                            }
                        },
                    )
                }
            }
        }
    }
    ChannelChooser(vm, play)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelChooser(vm: MatchesViewModel, onPlay: (PlaybackRequest) -> Unit) {
    val (match, picks) = vm.sheet ?: return
    ModalBottomSheet(onDismissRequest = vm::dismissSheet) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.pick_channel),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "${match.home.short.ifBlank { match.home.name }}  ×  ${match.away.short.ifBlank { match.away.name }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            picks.forEachIndexed { i, p ->
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .novaFocus(shape)
                        .clip(shape)
                        .background(if (i == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { vm.choose(match, p.channel, onPlay) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(p.channel.logo, Modifier.size(width = 64.dp, height = 40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.channel.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.nowTitle.isNotBlank()) {
                            Text(p.nowTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    when {
                        p.epgMatch -> Tag(stringResource(R.string.guide_match), Color(0xFF2ECC71))
                        i == 0 -> Tag(stringResource(R.string.best_match), MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .novaFocus(shape)
            .clip(shape)
            .then(if (selected) Modifier.background(NovaGradient) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

private fun darken(c: Long, f: Float): Color {
    if (c == 0L) return Color.Unspecified
    val col = Color(c.toInt())
    return Color(col.red * f, col.green * f, col.blue * f, 1f)
}

private fun countdown(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0)
    val h = m / 60
    return if (h > 0) "${h}h ${m % 60}m" else "${m}m"
}

@Composable
private fun MatchCard(m: Match, now: Long, network: String, resolving: Boolean, onWatch: (() -> Unit)?) {
    val tv = LocalNovaFlags.current.isTv
    val shape = RoundedCornerShape(24.dp)
    val live = m.isLive
    val homeC = darken(m.home.color, 0.62f).takeIf { it != Color.Unspecified } ?: Color(0xFF2A2F5A)
    var awayRaw = m.away.color
    if (awayRaw == m.home.color && m.away.altColor != 0L) awayRaw = m.away.altColor
    val awayC = darken(awayRaw, 0.62f).takeIf { it != Color.Unspecified } ?: Color(0xFF4A2A5A)
    val border = if (live) Brush.linearGradient(listOf(Color(0xFFFF3B5C), Color(0xFFFF8A3D)))
    else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f)))
    Box(
        Modifier
            .width(if (tv) 340.dp else 292.dp)
            .novaFocus(shape)
            .shadow(14.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(homeC, Color(0xFF0B0B14), awayC)))
            .border(if (live) 1.6.dp else 1.dp, border, shape)
            .then(if (onWatch != null) Modifier.clickable(onClick = onWatch) else Modifier),
    ) {
        // Club crests as large watermarks on each side.
        NetImage(
            m.home.logo,
            Modifier.align(Alignment.CenterStart).offset(x = (-34).dp).size(150.dp).alpha(0.16f).rotate(-12f),
            contentScale = ContentScale.Fit, fallback = Icons.Filled.SportsSoccer,
        )
        NetImage(
            m.away.logo,
            Modifier.align(Alignment.CenterEnd).offset(x = 34.dp).size(150.dp).alpha(0.16f).rotate(12f),
            contentScale = ContentScale.Fit, fallback = Icons.Filled.SportsSoccer,
        )
        // Pitch lines for a stadium feel.
        Canvas(Modifier.matchParentSize()) {
            val stroke = 1.dp.toPx()
            val c = Color.White.copy(alpha = 0.07f)
            drawLine(c, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke)
            drawCircle(c, radius = size.height * 0.28f, center = Offset(size.width / 2, size.height / 2), style = Stroke(stroke))
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.55f)))))

        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.leagueLogo.isNotBlank()) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White).padding(3.dp)) {
                        NetImage(m.leagueLogo, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    m.league,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (m.favorite) {
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC94D), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                StatusPill(m)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamColumn(m.home, Modifier.weight(1f))
                CenterScore(m)
                TeamColumn(m.away, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            val rowShape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(rowShape)
                    .background(if (onWatch != null) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.28f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when {
                    onWatch != null -> Icons.Filled.PlayArrow
                    m.state == MatchState.PRE -> Icons.Filled.Lock
                    else -> Icons.Filled.Tv
                }
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                val label = when {
                    resolving -> stringResource(R.string.finding_channel)
                    onWatch != null -> stringResource(R.string.watch_now) + if (network.isNotBlank()) " · $network" else ""
                    m.state == MatchState.PRE -> stringResource(R.string.opens_in, countdown(m.startMs - OPEN_BEFORE_MS - now)) +
                        if (network.isNotBlank()) " · $network" else ""
                    else -> network
                }
                Text(
                    label, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (resolving) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun TeamColumn(t: MatchTeam, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape).padding(7.dp),
            contentAlignment = Alignment.Center,
        ) {
            NetImage(t.logo, Modifier.size(38.dp), contentScale = ContentScale.Fit, fallback = Icons.Filled.SportsSoccer)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            t.short.ifBlank { t.name },
            color = Color.White,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenterScore(m: Match) {
    val context = LocalContext.current
    Box(Modifier.width(92.dp), contentAlignment = Alignment.Center) {
        when (m.state) {
            MatchState.PRE, MatchState.POSTPONED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    android.text.format.DateFormat.getTimeFormat(context).format(Date(m.startMs)),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White,
                )
                Text("VS", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                val color = Color.White
                Text(m.home.score.ifBlank { "0" }, fontSize = 30.sp, fontWeight = FontWeight.Black, color = color)
                Text("  -  ", fontSize = 20.sp, color = Color.White.copy(alpha = 0.6f))
                Text(m.away.score.ifBlank { "0" }, fontSize = 30.sp, fontWeight = FontWeight.Black, color = color)
            }
        }
    }
}

@Composable
private fun StatusPill(m: Match) {
    val shape = RoundedCornerShape(50)
    when (m.state) {
        MatchState.LIVE, MatchState.HALFTIME -> {
            val pulse = rememberInfiniteTransition(label = "live")
            val a by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
            Row(
                Modifier.clip(shape).background(Color(0xFFE53950)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).alpha(a).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (m.state == MatchState.HALFTIME) stringResource(R.string.match_ht)
                    else stringResource(R.string.match_live) + if (m.clock.isNotBlank()) "  " + m.clock else "",
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        MatchState.POST -> Text(
            stringResource(R.string.match_ft),
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.clip(shape).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        MatchState.POSTPONED -> Text(
            stringResource(R.string.match_postponed),
            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB648),
            modifier = Modifier.clip(shape).background(Color(0x22FFB648)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        MatchState.PRE -> Unit
    }
}

@Composable
private fun MatchSkeleton() {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.width(292.dp).height(170.dp).alpha(a).clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
