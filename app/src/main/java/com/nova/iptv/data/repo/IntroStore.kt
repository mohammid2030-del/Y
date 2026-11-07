package com.nova.iptv.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-series intro timings learned from the user's own actions
 * (seeking past the intro once, or fine-tuning the skip with ±10 s).
 * Providers don't publish intro markers, so this is how the skip becomes exact for each show.
 */
@Singleton
class IntroStore @Inject constructor(@ApplicationContext context: Context) {

    data class Mark(val startMs: Long, val endMs: Long)

    private val prefs = context.getSharedPreferences("intro_marks", Context.MODE_PRIVATE)

    private fun key(subId: Long, seriesId: String) = "$subId|$seriesId"

    fun get(subId: Long, seriesId: String): Mark? {
        val parts = prefs.getString(key(subId, seriesId), null)?.split(",") ?: return null
        if (parts.size != 2) return null
        val start = parts[0].toLongOrNull() ?: return null
        val end = parts[1].toLongOrNull() ?: return null
        return if (end > start) Mark(start, end) else null
    }

    fun save(subId: Long, seriesId: String, startMs: Long, endMs: Long) {
        prefs.edit().putString(key(subId, seriesId), "${startMs.coerceAtLeast(0)},$endMs").apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
