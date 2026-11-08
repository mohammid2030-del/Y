package com.nova.iptv.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.tmdb.TmdbCard
import com.nova.iptv.data.tmdb.TmdbRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TmdbEntryPoint {
    fun tmdb(): TmdbRepository
}

private fun tmdbOf(context: Context): TmdbRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, TmdbEntryPoint::class.java).tmdb()

/**
 * Poster in the app language from TMDB when a key is set (movies / series only);
 * otherwise, or until it arrives, the provider's image.
 */
@Composable
fun rememberTmdbCard(type: String, title: String, year: String, fallbackImage: String): Pair<String, String> {
    val fallback = fallbackImage to title
    if (type != ContentType.MOVIE && type != ContentType.SERIES) return fallback
    val context = LocalContext.current
    val tmdb = remember { tmdbOf(context) }
    val enabled by tmdb.enabled.collectAsStateWithLifecycle()
    if (!enabled) return fallback
    val isTv = type == ContentType.SERIES
    val initial = remember(title, year, type) { tmdb.cachedPoster(isTv, title, year) }
    val card by produceState(initial, title, year, type) {
        if (initial == null) value = tmdb.poster(isTv, title, year)
    }
    val c = card ?: return fallback
    return c.poster.ifBlank { fallbackImage } to c.title.ifBlank { title }
}
