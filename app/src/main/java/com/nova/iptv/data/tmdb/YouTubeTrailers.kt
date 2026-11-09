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

    data class Query(val q: String, val names: List<String>, val season: Int = 0, val lang: String = "", val series: Boolean = false)

    /**
     * What to search on YouTube, in the work's original language (Turkish series → "<Turkish name> fragman"),
     * and the names the video's title must contain.
     */
    fun query(tm: TmdbInfo?, name: String, year: String, series: Boolean, season: Int = 0): Query {
        val title = tm?.let { it.originalTitle.ifBlank { it.title } } ?: searchTitle(name).first
        val y = tm?.year?.ifBlank { null } ?: year.take(4)
        val arabic = ARABIC.containsMatchIn(title)
        val lang = tm?.originalLanguage?.ifBlank { null } ?: if (arabic) "ar" else ""
        val q = when {
            lang == "tr" -> if (season > 0) "$title $season. sezon fragman" else "$title" + if (series) " 1. bölüm fragmanı" else " fragman"
            // "اعلان" alone also means a TV commercial — say what it is
            lang == "ar" || arabic -> when {
                season > 0 -> "مسلسل $title الموسم $season برومو"
                series -> "مسلسل $title برومو"
                else -> "فيلم $title $y اعلان رسمي"
            }
            season > 0 -> "$title season $season official trailer"
            else -> "$title $y${if (series) " series" else ""} official trailer"
        }
        val names = listOfNotNull(title, tm?.title, searchTitle(name).first).filter { it.isNotBlank() }.distinct()
        return Query(q.split(' ').filter { it.isNotBlank() }.joinToString(" "), names, season, lang, series)
    }

    /** What YouTube says about a video: title, channel, length (s), audio language, plays here. */
    private data class Fact(val t: String = "", val c: String = "", val d: Int = 0, val a: String = "", val ok: Boolean = false)

    private val factPrefs = context.getSharedPreferences("nova_youtube_facts", Context.MODE_PRIVATE)
    private val gson = com.google.gson.Gson()

    /** Facts for videos (one API unit for up to 50 ids, remembered). */
    private suspend fun facts(ids: List<String>): Map<String, Fact> {
        val out = HashMap<String, Fact>()
        val ask = ArrayList<String>()
        for (id in ids) {
            val f = factPrefs.getString(id, null)?.let { runCatching { gson.fromJson(it, Fact::class.java) }.getOrNull() }
            if (f != null) out[id] = f else ask += id
        }
        if (ask.isEmpty() || !enabled) return out
        val o = get(
            "https://www.googleapis.com/youtube/v3/videos?part=snippet,status,contentDetails&id=${ask.take(50).joinToString(",")}&key=${enc(BuildConfig.YOUTUBE_API_KEY)}",
        ) ?: return out   // can't tell now
        val cc = Locale.getDefault().country.uppercase().ifBlank { "SA" }
        val got = HashMap<String, Fact>()
        o.getAsJsonArray("items")?.forEach { e ->
            val v = e.asJsonObject
            val id = v.get("id")?.asString.orEmpty()
            val st = v.getAsJsonObject("status")
            val sn = v.getAsJsonObject("snippet")
            val cd = v.getAsJsonObject("contentDetails")
            val rr = cd?.getAsJsonObject("regionRestriction")
            val blocked = rr?.getAsJsonArray("blocked")?.any { x -> x.asString == cc } == true ||
                rr?.getAsJsonArray("allowed")?.let { a -> a.none { x -> x.asString == cc } } == true
            got[id] = Fact(
                t = sn?.get("title")?.asString.orEmpty(),
                c = sn?.get("channelTitle")?.asString.orEmpty(),
                d = seconds(cd?.get("duration")?.asString.orEmpty()),
                a = sn?.get("defaultAudioLanguage")?.asString.orEmpty().take(2).lowercase(),
                ok = st?.get("privacyStatus")?.asString != "private" && st?.get("embeddable")?.asBoolean != false &&
                    !blocked && st?.get("uploadStatus")?.asString != "rejected",
            )
        }
        ask.take(50).filter { it !in got }.forEach { got[it] = Fact() }   // deleted / private
        val edit = factPrefs.edit()
        got.forEach { (k, v) -> edit.putString(k, gson.toJson(v)) }
        edit.apply()
        return out + got
    }

    /** An official-looking trailer of the work, in its original language, that plays here? */
    private fun acceptable(f: Fact?, q: Query, needName: Boolean): Boolean {
        if (f == null || !f.ok) return false
        if (f.d > 0 && (f.d < 45 || f.d > 600)) return false   // Shorts / full episodes
        val t = norm(f.t); val ch = norm(f.c)
        if (MUSIC.containsMatchIn(t) || UNOFFICIAL.containsMatchIn(t) || UNOFFICIAL.containsMatchIn(ch) || DUBBED.containsMatchIn(t)) return false
        if (episodeOf(f.t) > 1) return false
        if (q.season > 0 && !namesSeason(f.t, q.season)) return false
        if (q.lang.isNotEmpty() && f.a.isNotEmpty() && f.a != q.lang) return false   // audio in another language than the original
        if (needName) {
            val wanted = q.names.map(::norm).filter { it.length >= 2 }
            if (wanted.none { t.contains(it) } || !TRAILER_WORDS.containsMatchIn(t)) return false
        }
        return true
    }

    /** Higher = more official: the series' own channel, the broadcaster / studio, the original language. */
    private fun score(f: Fact, q: Query): Int {
        val t = norm(f.t); val ch = norm(f.c)
        var s = 0
        if (q.names.map(::norm).filter { it.length >= 3 }.any { ch.contains(it) }) s += 4
        if (OFFICIAL_CH.containsMatchIn(ch)) s += 3
        if (Regex("official|resmi|رسمي").containsMatchIn(ch)) s += 1
        if (STRONG.containsMatchIn(t)) s += 2
        if (q.lang == "tr" && Regex("fragman|tanitim").containsMatchIn(t)) s += 1
        if (q.lang == "ar" && ARABIC.containsMatchIn(t)) s += 1
        if (q.lang.isNotEmpty() && q.lang != "ar" && q.lang != "tr" && Regex("trailer|teaser").containsMatchIn(t)) s += 1
        if (SUBBED.containsMatchIn(t) && !OFFICIAL_CH.containsMatchIn(ch)) s -= 3   // re-uploads with subtitles
        return s
    }

    /** The best official trailer: one of TMDB's [keys] that passes the checks, else a YouTube search. "" when none. */
    suspend fun find(keys: List<String>, query: Query): String {
        val clean = keys.map { k -> k.filter { it.isLetterOrDigit() || it == '-' || it == '_' } }.filter { it.isNotEmpty() }.distinct()
        if (!enabled) return clean.firstOrNull().orEmpty()
        val fk = facts(clean)
        clean.filter { acceptable(fk[it], query, false) }.maxWithOrNull(compareBy<String> { score(fk[it]!!, query) }.thenByDescending { clean.indexOf(it) })
            ?.let { return it }
        if (query.q.isBlank()) return ""
        val cacheKey = "yt4|" + query.q.lowercase() + "|" + query.lang
        prefs.getString(cacheKey, null)?.let { return it }
        val o = get(
            "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=15&videoEmbeddable=true&safeSearch=moderate" +
                (if (query.lang.isNotEmpty()) "&relevanceLanguage=${query.lang}" else "") +
                "&q=${enc(query.q)}&key=${enc(BuildConfig.YOUTUBE_API_KEY)}",
        ) ?: return ""   // quota used up / offline: try another time
        val ids = o.getAsJsonArray("items")?.mapNotNull { e -> e.asJsonObject.getAsJsonObject("id")?.get("videoId")?.asString }.orEmpty()
        val f = facts(ids)
        // most official first; YouTube's order among equals
        val id = ids.filter { acceptable(f[it], query, true) }
            .maxWithOrNull(compareBy<String> { score(f[it]!!, query) }.thenByDescending { ids.indexOf(it) }).orEmpty()
        prefs.edit().putString(cacheKey, id).apply()
        return id
    }

    private fun seconds(iso: String): Int {
        val m = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(iso) ?: return 0
        fun n(i: Int) = m.groupValues[i].toIntOrNull() ?: 0
        return n(1) * 86400 + n(2) * 3600 + n(3) * 60 + n(4)
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
        private val TRAILER_WORDS = Regex("trailer|teaser|promo|official|اعلان|برومو|تريلر|fragman|tanitim|bande annonce|trailer oficial")
        private val MUSIC = Regex("اغنيه|كليب|music video|lyrics|official video|official audio|soundtrack|jenerik|dizi muzig|muzigi|(?:^| )ost(?: |$)|(?:^| )mv(?: |$)|sarki")
        private val UNOFFICIAL = Regex("fan ?made|(?:^| )fan(?: |$)|concept|reaction|tepki|parod|(?:^| )edit(?: |$)|review|recap|explained|breakdown|ozet|inceleme|مراجعه|تحليل|ملخص|ردة فعل|رده فعل|حالات|ستوري|shorts|(?:^| )short(?: |$)|status|tiktok|ai trailer|fake|اعلان مزيف|(?:^| )kamera arkasi|behind the scenes|kolaj")
        private val DUBBED = Regex("dublaj|dubbed|(?:^| )dub(?: |$)|doblad|doblaje|مدبلج|بالعربي|باللهجه|النسخه العربيه|arabic version|hindi|urdu|turkce dublaj")
        private val SUBBED = Regex("مترجم|altyazili|subtitled|translated|traduit|legendado")
        private val STRONG = Regex("official trailer|resmi fragman|tanitim|الاعلان الرسمي|اعلان رسمي|الاعلان الاول|(?:^| )1 bolum fragman|ilk fragman|teaser trailer|ilk tanitim")
        private val OFFICIAL_CH = Regex("(?:^| )(?:atv|show tv|showtv|kanal d|star tv|fox|now|now tv|trt ?1|trt|tv ?8|beyaz tv|kanal 7|netflix|disney|prime video|amazon|hbo|max|apple tv|paramount|warner|universal|sony pictures|lionsgate|marvel|a24|20th century|focus features|neon|mubi|ay yapim|ox yapim|tims|mf yapim|gold film|blu ?tv|exxen|gain|tabii|mbc|shahid|osn|rotana|watch it|yango|tod|شاهد|روتانا|ام بي سي)(?: |$)")
        private val ORD = mapOf(
            "الاول" to 1, "الاولي" to 1, "الثاني" to 2, "الثانيه" to 2, "الثالث" to 3, "الثالثه" to 3, "الرابع" to 4, "الرابعه" to 4,
            "الخامس" to 5, "الخامسه" to 5, "السادس" to 6, "السادسه" to 6, "السابع" to 7, "السابعه" to 7, "الثامن" to 8, "الثامنه" to 8,
            "التاسع" to 9, "التاسعه" to 9, "العاشر" to 10, "العاشره" to 10,
        )
        private val EPISODE = Regex("s\\d+ ?e(\\d+)|(\\d+) bolum|bolum (\\d+)|episode (\\d+)|(?:^| )ep ?(\\d+)|(?:^| )e(\\d+)(?: |$)|(?:الحلقه|حلقه) ([0-9\\u0660-\\u0669]+)|(\\d+) (?:الحلقه|حلقه)")
        private val FINAL = Regex("الحلقه الاخيره|final episode|final bolum|finali|son bolum")

        /** Comparison text: lower case, no accents (ö → o, ş → s, ı → i), no Arabic diacritics / letter variants, no punctuation. */
        fun norm(s: String): String {
            var t = s.lowercase().replace('ı', 'i')
            t = Normalizer.normalize(t, Normalizer.Form.NFD)
            t = t.replace(Regex("[\\u0300-\\u036f\\u064B-\\u065F\\u0670\\u0640]"), "").replace(Regex("[أإآٱ]"), "ا").replace('ة', 'ه').replace('ى', 'ي')
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
