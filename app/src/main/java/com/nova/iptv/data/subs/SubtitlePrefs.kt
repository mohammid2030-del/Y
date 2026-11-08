package com.nova.iptv.data.subs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Look of subtitles shown by the player (0 small, 1 medium, 2 large, 3 extra large). */
@Singleton
class SubtitlePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("nova_subtitle_style", Context.MODE_PRIVATE)

    var size: Int
        get() = prefs.getInt("size", 1)
        set(value) { prefs.edit().putInt("size", value.coerceIn(0, 3)).apply() }
}
