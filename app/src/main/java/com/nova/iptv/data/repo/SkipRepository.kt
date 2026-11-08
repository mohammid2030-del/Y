package com.nova.iptv.data.repo

import android.content.Context
import android.util.LruCache
import com.nova.iptv.data.network.EpisodeSegments
import com.nova.iptv.data.network.HttpFetcher
import com.nova.iptv.data.network.Segment
import com.nova.iptv.data.network.SegmentsParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intro / recap / credits timestamps.
 * 1) TheIntroDB community database (by TMDB id + season + episode). Only these three numbers are sent.
 * (Learned per-series intros live in IntroStore.)
 */
@Singleton
class SkipRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val fetcher: HttpFetcher,
) {
    private val cache = LruCache<String, EpisodeSegments>(100)

    suspend fun segments(tmdbId: String, season: Int, episode: Int): EpisodeSegments? {
        if (tmdbId.isBlank() || season <= 0 || episode <= 0) return null
        val key = "$tmdbId|$season|$episode"
        cache.get(key)?.let { return it }
        val url = "https://api.theintrodb.org/v4/media?tmdb_id=$tmdbId&season=$season&episode=$episode"
        val result = runCatching { fetcher.text(url, USER_AGENT) { SegmentsParser.parse(it) } }.getOrNull() ?: return null
        cache.put(key, result)
        return result
    }

    companion object {
        private const val USER_AGENT = "NOVA-IPTV/1.0 (Android)"
    }
}
