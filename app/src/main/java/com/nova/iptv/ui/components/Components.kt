package com.nova.iptv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.nova.iptv.R
import com.nova.iptv.data.ErrorKind
import com.nova.iptv.data.NovaException
import com.nova.iptv.ui.theme.LocalNovaFlags
import com.nova.iptv.ui.theme.NovaGradient

/** Focus highlight for D-pad / remote navigation (TV) and keyboard. Place before clickable(). */
@Composable
fun Modifier.novaFocus(shape: Shape = RoundedCornerShape(14.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val lowEnd = LocalNovaFlags.current.lowEnd
    val scale by animateFloatAsState(if (focused && !lowEnd) 1.05f else 1f, label = "focusScale")
    val color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .scale(scale)
        .border(if (focused) 2.dp else 0.dp, color, shape)
}

@Composable
fun NetImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: ImageVector = Icons.Filled.Movie,
) {
    if (url.isBlank()) {
        Placeholder(fallback, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
        loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) },
        error = { Placeholder(fallback, Modifier.fillMaxSize()) },
    )
}

@Composable
private fun Placeholder(icon: ImageVector, modifier: Modifier) {
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(32.dp))
    }
}

@Composable
fun PosterCard(
    title: String,
    image: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    progress: Float? = null,
    favorite: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .novaFocus(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            NetImage(image, Modifier.fillMaxSize())
            if (favorite) {
                Icon(
                    Icons.Filled.Star, null, tint = Color(0xFFFFC857),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp)
                )
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}

@Composable
fun ChannelLogo(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        NetImage(url, Modifier.fillMaxSize(), ContentScale.Fit, Icons.Filled.LiveTv)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(NovaGradient))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyView(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Inbox) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorView(error: NovaException?, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(errorText(error), textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, modifier = Modifier.novaFocus()) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
fun errorText(e: NovaException?): String = when (e?.kind) {
    ErrorKind.NETWORK -> stringResource(R.string.err_network)
    ErrorKind.TIMEOUT -> stringResource(R.string.err_timeout)
    ErrorKind.INVALID_URL -> stringResource(R.string.err_invalid_url)
    ErrorKind.AUTH_FAILED -> stringResource(R.string.err_auth)
    ErrorKind.EXPIRED -> stringResource(R.string.err_expired)
    ErrorKind.HTTP -> stringResource(R.string.err_http, e?.httpCode ?: 0)
    ErrorKind.NOT_PLAYLIST -> stringResource(R.string.err_not_playlist)
    ErrorKind.PARSE -> stringResource(R.string.err_parse)
    ErrorKind.FILE -> stringResource(R.string.err_file)
    else -> stringResource(R.string.err_unknown)
}

@Composable
fun GradientBadge(text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, modifier = modifier) {
        Box(Modifier.background(NovaGradient).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
