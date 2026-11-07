package com.nova.iptv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
    val onZap: (Int) -> Unit,
)

@Composable
fun PlayerScreen(state: PlayerUiState, playerView: PlayerView, actions: PlayerActions) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())

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
