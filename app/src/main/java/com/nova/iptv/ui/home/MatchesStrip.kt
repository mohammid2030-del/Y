@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nova.iptv.ui.home

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
) : ViewModel() {
    val day = MutableStateFlow(0)

    val ui: StateFlow<MatchesUi> = combine(settings.settings.map { it.showMatches }, day) { on, d -> on to d }
        .flatMapLatest { (on, d) ->
            if (!on) flowOf(MatchesUi(loading = false, enabled = false))
            else flow {
                emit(MatchesUi(loading = true))
                // Refreshes every 45 s while a match is live, otherwise every 10 minutes (and on each app open).
                while (true) {
                    val list = runCatching { repo.matchesFor(d) }.getOrDefault(emptyList())
                    emit(MatchesUi(loading = false, matches = list))
                    delay(if (list.any { it.isLive }) 45_000 else 600_000)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MatchesUi())

    fun select(d: Int) { day.value = d }

    /** Plays the first channel in the active subscription whose name matches a broadcaster. */
    fun watch(m: Match, onPlay: (PlaybackRequest) -> Unit, onMissing: () -> Unit) {
        viewModelScope.launch {
            val subId = settings.current().activeSubscriptionId
            val hit = m.broadcasters.asSequence()
                .flatMap { b -> sequenceOf(b, b.substringBefore(' ')) }
                .filter { it.length >= 3 }
                .firstNotNullOfOrNull { q -> content.search(subId, q, ContentType.LIVE, "").firstOrNull() }
            if (hit == null) onMissing()
            else onPlay(PlaybackRequest(hit.subscriptionId, ContentType.LIVE, hit.remoteId, hit.name, hit.logo))
        }
    }
}

@Composable
fun MatchesStrip(vm: MatchesViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!ui.enabled) return
    val noChannel = stringResource(R.string.no_channel_found)

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
                        m,
                        onWatch = if (m.broadcasters.isEmpty() || m.state == MatchState.POST) null else {
                            {
                                vm.watch(
                                    m,
                                    onPlay = { PlayerLauncher.play(context, it) },
                                    onMissing = { Toast.makeText(context, noChannel, Toast.LENGTH_SHORT).show() },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
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

@Composable
private fun MatchCard(m: Match, onWatch: (() -> Unit)?) {
    val tv = LocalNovaFlags.current.isTv
    val shape = RoundedCornerShape(22.dp)
    val live = m.isLive
    val border = if (live) Brush.linearGradient(listOf(Color(0xFFFF3B5C), Color(0xFFFF8A3D)))
    else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f)))
    Column(
        Modifier
            .width(if (tv) 330.dp else 280.dp)
            .novaFocus(shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)
                )
            )
            .border(if (live) 1.5.dp else 1.dp, border, shape)
            .then(if (onWatch != null) Modifier.clickable(onClick = onWatch) else Modifier)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.leagueLogo.isNotBlank()) {
                NetImage(m.leagueLogo, Modifier.size(18.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                m.league,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusPill(m)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamColumn(m.home, Modifier.weight(1f))
            CenterScore(m)
            TeamColumn(m.away, Modifier.weight(1f))
        }
        if (m.broadcasters.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.18f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    m.broadcasters.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onWatch != null) {
                    Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun TeamColumn(t: MatchTeam, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            NetImage(t.logo, Modifier.size(34.dp), contentScale = ContentScale.Fit, fallback = Icons.Filled.SportsSoccer)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            t.short.ifBlank { t.name },
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
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                )
                Text("VS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                val color = if (m.isLive) Color(0xFFFF5470) else MaterialTheme.colorScheme.onSurface
                Text(m.home.score.ifBlank { "0" }, fontSize = 30.sp, fontWeight = FontWeight.Black, color = color)
                Text("  -  ", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(shape).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
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
        Modifier.width(280.dp).height(150.dp).alpha(a).clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
