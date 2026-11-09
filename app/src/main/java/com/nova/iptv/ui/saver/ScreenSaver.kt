package com.nova.iptv.ui.saver

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nova.iptv.R
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.tmdb.TmdbInfo
import com.nova.iptv.data.tmdb.TmdbRepository
import com.nova.iptv.ui.components.NetImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** One screensaver slide: a library title with full TMDB artwork and story. */
data class SaverSlide(val c: ContentEntity, val info: TmdbInfo)

@HiltViewModel
class ScreenSaverViewModel @Inject constructor(
    private val content: ContentRepository,
    private val settings: SettingsRepository,
    private val tmdb: TmdbRepository,
) : ViewModel() {
    private val recent = ArrayDeque<String>()
    private var pool: Map<String, List<ContentEntity>> = emptyMap()

    val available: Boolean get() = tmdb.enabled.value

    /** Finds a title with a backdrop and a story (in Arabic only titles TMDB has in Arabic: title + story). */
    suspend fun next(): SaverSlide? {
        if (!tmdb.enabled.value) return null
        val sub = settings.activeSubscriptionId.first()
        if (pool.isEmpty()) {
            pool = mapOf(
                ContentType.MOVIE to runCatching { content.recentlyAdded(sub, ContentType.MOVIE, 400).first() }.getOrDefault(emptyList()),
                ContentType.SERIES to runCatching { content.recentlyAdded(sub, ContentType.SERIES, 400).first() }.getOrDefault(emptyList()),
            )
        }
        val arabic = tmdb.language() == "ar"
        repeat(16) {
            val type = if (Math.random() < 0.5) ContentType.MOVIE else ContentType.SERIES
            val list = pool[type].orEmpty()
            if (list.isEmpty()) return@repeat
            // half the time from the newest additions, otherwise anything — varied
            val c = (if (Math.random() < 0.5) list.take(120) else list).random()
            val key = type + c.id
            if (key in recent) return@repeat
            val info = runCatching { content.tmdbFor(c) }.getOrNull() ?: return@repeat
            if (info.backdrop.isBlank() || info.overview.isBlank()) return@repeat
            if (arabic && !(info.localText && ARABIC.containsMatchIn(info.title) && ARABIC.containsMatchIn(info.overview))) return@repeat
            recent.addLast(key); if (recent.size > 40) recent.removeFirst()
            return SaverSlide(c, info)
        }
        return null
    }

    fun reset() { pool = emptyMap() }

    private companion object { val ARABIC = Regex("[\\u0600-\\u06FF]") }
}

/**
 * After a minute without the remote or a touch (and nothing playing — the player is another screen), the library's
 * movies and series from TMDB fill the screen: backdrop, logo / title, type, year, genres, rating and story; a new
 * one every 10 s with a slow zoom. Any key or touch closes it, and that input does nothing else.
 */
@Composable
fun ScreenSaverHost(allowed: Boolean, vm: ScreenSaverViewModel = hiltViewModel()) {
    var slide by remember { mutableStateOf<SaverSlide?>(null) }
    var active by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(allowed) {
        if (!allowed) { active = false; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            UserActivity.touch()   // coming back to the app counts as activity
            while (true) {
                delay(5_000)
                if (active || !vm.available || UserActivity.idleMs < IDLE_MS) continue
                val first = vm.next() ?: continue
                if (UserActivity.idleMs < IDLE_MS) continue
                slide = first; active = true
                var upcoming = vm.next()
                while (active) {
                    delay(SLIDE_MS)
                    if (!active) break
                    upcoming?.let { slide = it }
                    upcoming = vm.next()
                }
            }
        }
    }

    AnimatedVisibility(active, enter = fadeIn(tween(1000)), exit = fadeOut(tween(350))) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val close = { active = false; UserActivity.touch() }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { close(); true }
                .pointerInput(Unit) { detectTapGestures { close() } },
        ) {
            AnimatedContent(
                targetState = slide,
                transitionSpec = { fadeIn(tween(1600)) togetherWith fadeOut(tween(1600)) },
                label = "saver",
                modifier = Modifier.fillMaxSize(),
            ) { s -> if (s != null) SaverPage(s) }
        }
    }
}

@Composable
private fun SaverPage(s: SaverSlide) {
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(s) { zoom.animateTo(1.12f, tween((SLIDE_MS + 2_000).toInt(), easing = LinearEasing)) }
    Box(Modifier.fillMaxSize()) {
        NetImage(s.info.backdrop, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value })
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent), endX = 1600f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, end = 64.dp, bottom = 56.dp).widthIn(max = 760.dp)) {
            val logo = s.info.logoLocal.ifBlank { if (s.info.localText) "" else s.info.logo }
            if (logo.isNotBlank()) {
                NetImage(logo, Modifier.height(110.dp).fillMaxWidth(0.7f), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            } else {
                Text(s.info.title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            val type = stringResource(if (s.c.type == ContentType.MOVIE) R.string.type_movie else R.string.type_series)
            val meta = listOf(type, s.info.year, s.info.genres.take(3).joinToString(" · "), if (s.info.rating > 0) "★ " + s.info.ratingText else "")
                .filter { it.isNotBlank() }.joinToString("   •   ")
            Text(meta, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(s.info.overview, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val IDLE_MS = 60_000L
private const val SLIDE_MS = 10_000L
