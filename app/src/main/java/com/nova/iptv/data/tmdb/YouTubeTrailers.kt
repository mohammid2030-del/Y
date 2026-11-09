package com.nova.iptv.data.tmdb

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.iptv.BuildConfig
import com.nova.iptv.ui.components.searchTitle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trailers like the Samsung and Apple apps: TMDB's trailers first, else a YouTube Data API search (100 searches a day
 * for the whole project, every answer remembered). Only accepts a video whose title names the work and looks like a
 * trailer — never a song, a commercial or an episode preview — and that really plays in the viewer's country.
 */
@Singleton
class YouTubeTrailers @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("nova_youtube", Context.MODE_PRIVATE)
    val enabled: Boolean get() = BuildConfig.YOUTUBE_API_KEY.isNotBlank()

    data class Query(val q: String, val names: List<String>, val season: Int = 0)

    /** What to search on YouTube, and the names the video's title must contain. */
    fun query(tm: TmdbInfo?, name: String, year: String, series: Boolean, season: Int = 0): Query {
        val title = tm?.let { it.originalTitle.ifBlank { it.title } } ?: searchTitle(name).first
        val y = tm?.year?.ifBlank { null } ?: year.take(4)
        val arabic = ARABIC.containsMatchIn(title)
        // "اعلان" alone also means a TV commercial — say what it is
        val q = when {
            season > 0 -> if (arabic) "مسلسل $title الموسم $season برومو" else "$title season $season official trailer"
            arabic -> if (series) "مسلسل $title برومو" else "فيلم $title $y اعلان رسمي"
            else -> "$title $y${if (series) " series" else ""} official trailer"
        }
        val names = listOfNotNull(title, tm?.title, searchTitle(name).first).filter { it.isNotBlank() }.distinct()
        return Query(q.split(' ').filter { it.isNotBlank() }.joinToString(" "), names, season)
    }

    /** The first trailer that plays: one of TMDB's [keys], else a YouTube search. "" when there is none. */
    suspend fun find(keys: List<String>, query: Query): String {
        val clean = keys.map { k -> k.filter { it.isLetterOrDigit() || it == '-' || it == '_' } }.filter { it.isNotEmpty() }
        usable(clean).firstOrNull()?.let { return it }
        return search(query)
    }

    /**
     * The videos that really play here: public, embeddable, not blocked in the viewer's country
     * ("The uploader has not made this video available in your country"). One API unit for up to 50 ids.
     */
    suspend fun usable(ids: List<String>): List<String> {
        if (!enabled || ids.isEmpty()) return ids
        val ask = ids.filter { !prefs.contains("ok|$it") }
        if (ask.isNotEmpty()) {
            val o = get("https://www.googleapis.com/youtube/v3/videos?part=status,contentDetails&id=${ask.joinToString(",")}&key=${enc(BuildConfig.YOUTUBE_API_KEY)}")
                ?: return ids   // can't tell (quota / offline): try them
            val cc = Locale.getDefault().country.uppercase().ifBlank { "SA" }
            val seen = HashSet<String>()
            val edit = prefs.edit()
            o.getAsJsonArray("items")?.forEach { e ->
                val it = e.asJsonObject
                val id = it.get("id")?.asString.orEmpty(); seen += id
                val st = it.getAsJsonObject("status")
                val rr = it.getAsJsonObject("contentDetails")?.getAsJsonObject("regionRestriction")
                val blocked = rr?.getAsJsonArray("blocked")?.any { x -> x.asString == cc } == true ||
                    rr?.getAsJsonArray("allowed")?.let { a -> a.none { x -> x.asString == cc } } == true
                val ok = st?.get("privacyStatus")?.asString != "private" && st?.get("embeddable")?.asBoolean != false &&
                    !blocked && st?.get("uploadStatus")?.asString != "rejected"
                edit.putBoolean("ok|$id", ok)
            }
            ask.filter { it !in seen }.forEach { edit.putBoolean("ok|$it", false) }   // deleted / private
            edit.apply()
        }
        return ids.filter { prefs.getBoolean("ok|$it", false) }
    }

    private suspend fun search(query: Query): String {
        if (!enabled || query.q.isBlank()) return ""
        val cacheKey = "yt3|" + query.q.lowercase()
        prefs.getString(cacheKey, null)?.let { return it }
        val o = get(
            "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=10&videoEmbeddable=true&safeSearch=moderate" +
                "&q=${enc(query.q)}&key=${enc(BuildConfig.YOUTUBE_API_KEY)}",
        ) ?: return ""   // quota used up / offline: try another time
        val wanted = query.names.map(::norm).filter { it.length >= 2 }
        val items = o.getAsJsonArray("items")?.mapNotNull { e ->
            val it = e.asJsonObject
            val id = it.getAsJsonObject("id")?.get("videoId")?.asString ?: return@mapNotNull null
            id to (it.getAsJsonObject("snippet")?.get("title")?.asString.orEmpty())
        }.orEmpty()
        val good = items.filter { (_, t) ->
            val n = norm(t)
            wanted.any { n.contains(it) } && TRAILER_WORDS.containsMatchIn(t) && !MUSIC.containsMatchIn(t) &&
                episodeOf(t) <= 1 && (query.season == 0 || namesSeason(t, query.season))
        }.sortedBy { (_, t) -> if (STRONG.containsMatchIn(norm(t))) 0 else 1 }   // official / first-episode trailer first
        val id = usable(good.map { it.first }).firstOrNull().orEmpty()
        prefs.edit().putString(cacheKey, id).apply()
        return id
    }

    private suspend fun get(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 12_000
            try {
                if (c.responseCode != 200) null else JsonParser.parseString(c.inputStream.bufferedReader().readText()).asJsonObject
            } finally { c.disconnect() }
        }.getOrNull()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private val ARABIC = Regex("[\\u0600-\\u06FF]")
        private val TRAILER_WORDS = Regex("trailer|teaser|promo|official|اعلان|إعلان|برومو|تريلر|fragman|tanıtım", RegexOption.IGNORE_CASE)
        private val MUSIC = Regex("اغنية|أغنية|كليب|music video|lyrics|official video|official audio|\\bmv\\b", RegexOption.IGNORE_CASE)
        private val STRONG = Regex("official trailer|resmi fragman|tanıtım|الاعلان الرسمي|اعلان رسمي|الاعلان الاول|1 bölüm fragman|ilk fragman|teaser trailer")
        private val ORD = mapOf(
            "الاول" to 1, "الاولي" to 1, "الثاني" to 2, "الثانيه" to 2, "الثالث" to 3, "الثالثه" to 3, "الرابع" to 4, "الرابعه" to 4,
            "الخامس" to 5, "الخامسه" to 5, "السادس" to 6, "السادسه" to 6, "السابع" to 7, "السابعه" to 7, "الثامن" to 8, "الثامنه" to 8,
            "التاسع" to 9, "التاسعه" to 9, "العاشر" to 10, "العاشره" to 10,
        )
        private val EPISODE = Regex("s\\d+ ?e(\\d+)|(\\d+) bölüm|bölüm (\\d+)|episode (\\d+)|(?:^| )ep ?(\\d+)|(?:^| )e(\\d+)(?: |$)|(?:الحلقه|حلقه) ([0-9\\u0660-\\u0669]+)")
        private val FINAL = Regex("الحلقه الاخيره|final episode|final bölüm|finali|son bölüm")

        /** Title comparison that ignores case, Arabic letter variants, diacritics and punctuation. */
        fun norm(s: String): String {
            var t = s.lowercase()
            t = t.replace(Regex("[\\u064B-\\u0652\\u0640]"), "").replace(Regex("[أإآ]"), "ا").replace('ة', 'ه').replace('ى', 'ي')
            t = Normalizer.normalize(t, Normalizer.Form.NFC)
            return t.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex(" +"), " ")
        }

        /** Episode a video previews ("8. Bölüm 2. Fragman", "Episode 8", "الحلقة 8" → 8), 0 when it is not about one episode. */
        fun episodeOf(title: String): Int {
            val t = norm(title)
            if (FINAL.containsMatchIn(t)) return 99
            EPISODE.find(t)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let { v ->
                return v.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("").toIntOrNull() ?: 0
            }
            val words = t.split(' ')
            words.forEachIndexed { i, w -> if ((w == "الحلقه" || w == "حلقه") && i + 1 < words.size) ORD[words[i + 1]]?.let { return it } }
            return 0
        }

        /** Does a video title name season [n]? Season 1 is often just "the trailer": fine unless another season is named. */
        fun namesSeason(title: String, n: Int): Boolean {
            val t = norm(title)
            val ar = listOf("", "الاول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر")
            fun says(k: Int): Boolean {
                val east = k.toString().map { '٠' + (it - '0') }.joinToString("")
                val num = "(?:0?$k|$east" + (if (k < ar.size) "|" + ar[k] else "") + ")"
                return Regex("(?:season|sezon|الموسم|موسم|الجزء|جزء)\\s*$num(?![0-9])|(?:^|\\s)s0?$k(?![0-9])|(?:^|\\s)$k\\s*sezon").containsMatchIn(t)
            }
            if (says(n)) return true
            return n == 1 && (2..30).none { says(it) }
        }
    }
}
