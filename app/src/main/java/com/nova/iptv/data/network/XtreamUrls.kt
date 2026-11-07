package com.nova.iptv.data.network

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure helpers for building Xtream Codes URLs (unit-tested). */
object XtreamUrls {

    /** Accepts "host:port", "http://host:port/", or a pasted player_api/get.php URL. */
    fun normalizeServer(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            s = "http://$s"
        }
        val markers = listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php", "/c/")
        for (m in markers) {
            val idx = s.indexOf(m, ignoreCase = true)
            if (idx > 0) s = s.substring(0, idx)
        }
        val q = s.indexOf('?')
        if (q > 0) s = s.substring(0, q)
        return s.trimEnd('/')
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    fun api(server: String, user: String, pass: String, action: String? = null, extra: String = ""): String {
        val base = "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        val withAction = if (action != null) "$base&action=$action" else base
        return if (extra.isNotEmpty()) "$withAction&$extra" else withAction
    }

    fun xmltv(server: String, user: String, pass: String) =
        "$server/xmltv.php?username=${enc(user)}&password=${enc(pass)}"

    fun live(server: String, user: String, pass: String, streamId: String, format: String): String {
        val ext = if (format == "m3u8") "m3u8" else "ts"
        return "$server/live/${enc(user)}/${enc(pass)}/$streamId.$ext"
    }

    fun movie(server: String, user: String, pass: String, streamId: String, ext: String) =
        "$server/movie/${enc(user)}/${enc(pass)}/$streamId.${ext.ifBlank { "mp4" }}"

    fun episode(server: String, user: String, pass: String, episodeId: String, ext: String) =
        "$server/series/${enc(user)}/${enc(pass)}/$episodeId.${ext.ifBlank { "mp4" }}"

    /** Catch-up / timeshift URL. Start time is formatted in the given zone (provider time). */
    fun timeshift(
        server: String,
        user: String,
        pass: String,
        streamId: String,
        startMs: Long,
        durationMin: Int,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = zone }
        return "$server/timeshift/${enc(user)}/${enc(pass)}/$durationMin/${fmt.format(Date(startMs))}/$streamId.ts"
    }
}
