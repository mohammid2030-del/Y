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
 * 2) Fallback: intro position learned on this device when the user skipped it manually in the same series.
 */
@Singleton
class SkipRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val fetcher: HttpFetcher,
) {
    private val cache = LruCache<String, EpisodeSegments>(100)
    private val learned = context.getSharedPreferences("nova_intro_learned", Context.MODE_PRIVATE)

    suspend fun segments(tmdbId: String, season: Int, episode: Int): EpisodeSegments? {
        if (tmdbId.isBlank() || season <= 0 || episode <= 0) return null
        val key = "$tmdbId|$season|$episode"
        cache.get(key)?.let { return it }
        val url = "https://api.theintrodb.org/v4/media?tmdb_id=$tmdbId&season=$season&episode=$episode"
        val result = runCatching { fetcher.text(url, USER_AGENT) { SegmentsParser.parse(it) } }.getOrNull() ?: return null
        cache.put(key, result)
        return result
    }

    fun learnedIntro(subId: Long, seriesId: String, season: Int): Segment? {
        val v = learned.getString("$subId|$seriesId|$season", null)
            ?: learned.getString("$subId|$seriesId|*", null) ?: return null
        val parts = v.split(',')
        val start = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val end = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return if (end > start) Segment(start, end) else null
    }

    fun learnIntro(subId: Long, seriesId: String, season: Int, startMs: Long, endMs: Long) {
        learned.edit()
            .putString("$subId|$seriesId|$season", "$startMs,$endMs")
            .putString("$subId|$seriesId|*", "$startMs,$endMs")
            .apply()
    }

    companion object {
        private const val USER_AGENT = "NOVA-IPTV/1.0 (Android)"
    }
}
