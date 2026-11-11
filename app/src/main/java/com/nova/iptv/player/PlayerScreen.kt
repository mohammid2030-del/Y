package com.nova.iptv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import com.nova.iptv.ui.components.NetImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.nova.iptv.R
import com.nova.iptv.ui.components.ChannelLogo
import com.nova.iptv.ui.components.errorText
import com.nova.iptv.ui.theme.NovaLive
import java.text.DateFormat
import java.util.Date

class PlayerActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onAspect: () -> Unit,
    val onPip: () -> Unit,
    val onInfo: () -> Unit,
    val onQuality: () -> Unit,
    val onSelectQuality: (QualityOption?) -> Unit,
    val onTracks: () -> Unit = {},
    val onSelectAudio: (TrackOption) -> Unit = {},
    val onSelectText: (TrackOption?) -> Unit = {},
    val onZap: (Int) -> Unit,
    val onSkipIntro: () -> Unit = {},
    val onPlayNext: () -> Unit = {},
    val onDismissNext: () -> Unit = {},
    val onAdjustIntro: (Long) -> Unit = {},
)

@Composable
fun PlayerScreen(state: PlayerUiState, playerView: PlayerView, actions: PlayerActions) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

        if (state.aiCue.isNotBlank()) {
            Text(
                state.aiCue,
                color = Color.White,
                fontSize = 22.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(2f, 2f), 6f),
                    textDirection = androidx.compose.ui.text.style.TextDirection.Rtl,
                ),
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 48.dp, end = 48.dp, bottom = if (state.controlsVisible) 120.dp else 40.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        if (state.inPip) return@Box

        AnimatedVisibility(
            visible = state.controlsVisible || state.error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(state, actions)
        }

        if (state.isLive && state.canZap && state.controlsVisible && state.error == null) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundIcon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.next_channel)) { actions.onZap(1) }
                RoundIcon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.previous_channel)) { actions.onZap(-1) }
            }
        }

        if (state.reconnecting && state.error == null) {
            Row(
                Modifier
                    .align(Alignment.Center)
                    .padding(top = 120.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.reconnecting, state.reconnectAttempt), color = Color.White)
            }
        }

        state.error?.let { err ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 460.dp)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xE6111118))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    if (state.sourceError != null) errorText(state.sourceError) else stringResource(err.kind.message),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (err.code.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(err.code, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.back)) }
                    Button(onClick = actions.onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
        }

        AnimatedVisibility(
            visible = state.isLive && (state.bannerVisible || state.infoVisible) && state.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ChannelBanner(state)
        }

        AnimatedVisibility(
            visible = state.skipIntroVisible && state.upNext == null && state.error == null,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = if (state.controlsVisible) 120.dp else 48.dp),
        ) {
            SkipIntroButton(if (state.skipIsRecap) stringResource(R.string.skip_recap) else stringResource(R.string.skip_intro), actions.onSkipIntro)
        }

        AnimatedVisibility(
            visible = state.introAdjustVisible && state.upNext == null && state.error == null,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = if (state.controlsVisible) 120.dp else 48.dp),
        ) {
            IntroAdjustBar(actions.onAdjustIntro)
        }

        AnimatedVisibility(
            visible = state.upNext != null && state.error == null,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (state.controlsVisible) 120.dp else 40.dp),
        ) {
            val next = state.upNext
            if (next != null) UpNextCard(next, state.upNextSeconds, state.upNextTotal, state.autoNext, actions)
        }

        if (state.tracksDialog) {
            AlertDialog(
                onDismissRequest = { state.tracksDialog = false },
                title = { Text(stringResource(R.string.audio_and_subtitles)) },
                text = {
                    LazyColumn {
                        item { TrackHeader(stringResource(R.string.audio_label)) }
                        if (state.audioTracks.isEmpty()) {
                            item { Text(stringResource(R.string.no_audio_tracks), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
                        }
                        items(state.audioTracks) { t ->
                            TrackRow(t.label, t.detail, t.selected, t.supported) { actions.onSelectAudio(t) }
                        }
                        item { TrackHeader(stringResource(R.string.subtitles_section)) }
                        item { TrackRow(stringResource(R.string.intro_off), "", state.textOff, true) { actions.onSelectText(null) } }
                        if (state.aiAvailable) item {
                            TrackRow(stringResource(R.string.ai_subtitle_track), "", state.aiOn, true) { state.aiOn = !state.aiOn }
                        }
                        items(state.textTracks) { t ->
                            TrackRow(t.label, t.detail, t.selected && !state.textOff, t.supported) { actions.onSelectText(t) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { state.tracksDialog = false }) { Text(stringResource(R.string.close)) } },
            )
        }

        if (state.qualityDialog) {
            AlertDialog(
                onDismissRequest = { state.qualityDialog = false },
                title = { Text(stringResource(R.string.video_quality)) },
                text = {
                    if (state.qualities.isEmpty()) {
                        Text(stringResource(R.string.single_quality))
                    } else {
                        LazyColumn {
                            item {
                                QualityRow(stringResource(R.string.auto), state.qualities.none { it.selected } || state.qualities.size == 1) {
                                    actions.onSelectQuality(null)
                                }
                            }
                            items(state.qualities) { q ->
                                QualityRow(q.label, q.selected && state.qualities.size > 1) { actions.onSelectQuality(q) }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { state.qualityDialog = false }) { Text(stringResource(R.string.close)) } },
            )
        }
    }
}

@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp)) { if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(10.dp))
        Text(label)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
    ) { Icon(icon, desc, tint = Color.White) }
}

@Composable
private fun TopBar(state: PlayerUiState, actions: PlayerActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isLive) {
                    Text(
                        stringResource(R.string.live_badge),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(NovaLive).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(state.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val sub = state.subtitle.ifBlank { state.nowProgram?.title.orEmpty() }
            if (sub.isNotBlank()) {
                Text(sub, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.infoVisible && state.videoInfo.isNotBlank()) {
            Text(state.videoInfo, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick = actions.onInfo) { Icon(Icons.Filled.Info, stringResource(R.string.stream_info), tint = Color.White) }
        IconButton(onClick = actions.onTracks) {
            Icon(Icons.Filled.Audiotrack, stringResource(R.string.audio_and_subtitles), tint = Color.White)
        }
        IconButton(onClick = actions.onQuality) { Icon(Icons.Filled.HighQuality, stringResource(R.string.video_quality), tint = Color.White) }
        IconButton(onClick = actions.onAspect) { Icon(Icons.Filled.AspectRatio, stringResource(R.string.aspect_ratio), tint = Color.White) }
        if (state.pipSupported) {
            IconButton(onClick = actions.onPip) { Icon(Icons.Filled.PictureInPictureAlt, stringResource(R.string.pip), tint = Color.White) }
        }
    }
}

@Composable
private fun ChannelBanner(state: PlayerUiState) {
    val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE6111118))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(state.logo, Modifier.size(width = 80.dp, height = 56.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (state.channelNumber > 0) "${state.channelNumber}  " else "") + state.title,
                color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1,
            )
            val now = state.nowProgram
            if (now != null) {
                val start = now.start + state.epgOffsetMs
                val stop = now.stop + state.epgOffsetMs
                Text(
                    "${fmt.format(Date(start))} – ${fmt.format(Date(stop))}  ${now.title}",
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { ((System.currentTimeMillis() - start).toFloat() / (stop - start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                    color = NovaLive,
                    modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
                if (state.infoVisible && now.description.isNotBlank()) {
                    Text(now.description, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            } else {
                Text(stringResource(R.string.no_info), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
            }
            state.nextProgram?.let { next ->
                Text(
                    stringResource(R.string.next_program, fmt.format(Date(next.start + state.epgOffsetMs)), next.title),
                    color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


@Composable
private fun SkipIntroButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun IntroAdjustBar(onAdjust: (Long) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.intro_adjust_title), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.intro_adjust_hint), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(-10_000L to "−10", -5_000L to "−5", 5_000L to "+5", 10_000L to "+10").forEach { (d, label) ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .clickable { onAdjust(d) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(stringResource(R.string.seconds_short, label), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun UpNextCard(next: UpNextUi, seconds: Int, total: Int, autoNext: Boolean, actions: PlayerActions) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .width(380.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xF21B1E46), Color(0xF22A1748), Color(0xF2111118))))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF23232F))
                    .clickable(onClick = actions.onPlayNext),
                contentAlignment = Alignment.Center,
            ) {
                NetImage(next.image, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                Box(contentAlignment = Alignment.Center) {
                    if (autoNext) {
                        CircularProgressIndicator(
                            progress = { (seconds.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.size(46.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.25f),
                            strokeWidth = 3.dp,
                        )
                    }
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.next_episode).uppercase(),
                    color = Color(0xFF9EA8FF),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                )
                Text(next.subtitle, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Text(
                    next.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (autoNext) {
                    Text(
                        stringResource(R.string.starts_in, seconds),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val pill = RoundedCornerShape(50)
            Row(
                Modifier.weight(1f).clip(pill).background(Color.White).clickable(onClick = actions.onPlayNext)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.play_now), color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier.weight(1f).clip(pill).background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), pill)
                    .clickable(onClick = actions.onDismissNext).padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.cancel), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}


@Composable
private fun TrackHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun TrackRow(label: String, detail: String, selected: Boolean, supported: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(enabled = supported, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = if (supported) onClick else null, enabled = supported)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            val sub = listOf(detail, if (!supported) stringResource(R.string.track_unsupported) else "").filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (supported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        }
    }
}
