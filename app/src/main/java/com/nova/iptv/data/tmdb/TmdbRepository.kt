package com.nova.iptv.data.tmdb

import android.content.Context
import android.util.LruCache
import androidx.appcompat.app.AppCompatDelegate
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.iptv.data.ErrorKind
import com.nova.iptv.data.NovaException
import com.nova.iptv.data.network.HttpFetcher
import com.nova.iptv.data.security.CryptoManager
import com.nova.iptv.ui.components.cleanTitle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class TmdbPerson(val name: String, val role: String, val photo: String)

data class TmdbInfo(
    val id: Int,
    val isTv: Boolean,
    val title: String,
    val originalTitle: String,
    val overview: String,
    val tagline: String,
    val rating: Double,
    val votes: Int,
    val year: String,
    val runtimeMin: Int,
    val genres: List<String>,
    val backdrop: String,
    val poster: String,
    val logo: String,
    val cast: List<TmdbPerson>,
    val creators: List<String>,
    val trailerKey: String,
    val certification: String,
    val seasons: Int,
) {
    val ratingText: String get() = if (rating > 0) String.format(Locale.US, "%.1f", rating) else ""
}

data class TmdbEpisode(
    val number: Int,
    val name: String,
    val overview: String,
    val still: String,
    val rating: Double,
    val runtimeMin: Int,
)

/**
 * Optional TMDB enrichment with the user's own API key (v3 key or v4 read token).
 * Sends only titles / TMDB ids to api.themoviedb.org. Responses are cached in memory and on disk for 7 days.
 */
@Singleton
class TmdbRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fetcher: HttpFetcher,
    private val crypto: CryptoManager,
) {
    private val prefs = context.getSharedPreferences("nova_tmdb", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getString(KEY, "").orEmpty().isNotEmpty())
    val enabled: StateFlow<Boolean> = _enabled

    private val infoCache = LruCache<String, TmdbInfo>(150)
    private val missCache = HashSet<String>()
    private val seasonCache = LruCache<String, Map<Int, TmdbEpisode>>(30)
    private val diskDir by lazy { File(context.cacheDir, "tmdb").apply { mkdirs() } }

    private fun key(): String = crypto.decrypt(prefs.getString(KEY, "").orEmpty())

    /** Saves a key after checking it with TMDB. Returns false when TMDB rejects it. */
    suspend fun saveKey(raw: String): Boolean {
        val k = raw.trim()
        if (k.isEmpty()) return false
        val ok = runCatching { request("configuration", emptyMap(), k, useCache = false) }.isSuccess
        if (!ok) return false
        prefs.edit().putString(KEY, crypto.encrypt(k)).apply()
        clearCaches()
        _enabled.value = true
        return true
    }

    fun removeKey() {
        prefs.edit().remove(KEY).apply()
        clearCaches()
        _enabled.value = false
    }

    private fun clearCaches() {
        infoCache.evictAll(); seasonCache.evictAll()
        synchronized(missCache) { missCache.clear() }
        runCatching { diskDir.listFiles()?.forEach { it.delete() } }
    }

    private fun lang(): String {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
        val l = tag.substringBefore('-').ifBlank { Locale.getDefault().language }
        return if (l == "ar") "ar" else "en"
    }

    /**
     * Details for a movie or series. Uses the provider's TMDB id when known, otherwise searches by title (+ year).
     */
    suspend fun lookup(isTv: Boolean, tmdbId: String, title: String, year: String): TmdbInfo? {
        if (!_enabled.value) return null
        val lang = lang()
        val clean = title.cleanTitle().replace(YEAR_IN_TITLE, "").trim()
        val y = year.take(4).ifBlank { YEAR_IN_TITLE.find(title)?.groupValues?.get(1).orEmpty() }
        val cacheKey = "${if (isTv) "tv" else "movie"}|$lang|${tmdbId.ifBlank { "$clean|$y" }}"
        infoCache.get(cacheKey)?.let { return it }
        synchronized(missCache) { if (cacheKey in missCache) return null }
        val result = runCatching {
            val id = tmdbId.filter { it.isDigit() }.toIntOrNull() ?: search(isTv, clean, y, lang)
            id?.let { details(isTv, it, lang) }
        }.getOrNull()
        if (result != null) infoCache.put(cacheKey, result) else synchronized(missCache) { missCache += cacheKey }
        return result
    }

    /** Arabic/English episode names, overviews and stills for one season. */
    suspend fun season(tvId: Int, season: Int): Map<Int, TmdbEpisode> {
        if (!_enabled.value || tvId <= 0 || season < 0) return emptyMap()
        val lang = lang()
        val ck = "$tvId|$season|$lang"
        seasonCache.get(ck)?.let { return it }
        val result = runCatching {
            val o = request("tv/$tvId/season/$season", mapOf("language" to lang))
            val fallback = if (lang != "en") runCatching { request("tv/$tvId/season/$season", mapOf("language" to "en-US")) }.getOrNull() else null
            val enById = fallback?.arr("episodes")?.mapNotNull { it.obj() }?.associateBy { it.int("episode_number") }.orEmpty()
            o.arr("episodes").mapNotNull { it.obj() }.associate { e ->
                val n = e.int("episode_number")
                val en = enById[n]
                n to TmdbEpisode(
                    number = n,
                    name = e.str("name").takeUnless { it.isBlank() || GENERIC_EPISODE.matches(it) } ?: en?.str("name").orEmpty(),
                    overview = e.str("overview").ifBlank { en?.str("overview").orEmpty() },
                    still = img(e.str("still_path"), "w500"),
                    rating = e.dbl("vote_average"),
                    runtimeMin = e.int("runtime"),
                )
            }
        }.getOrDefault(emptyMap())
        if (result.isNotEmpty()) seasonCache.put(ck, result)
        return result
    }

    private suspend fun search(isTv: Boolean, title: String, year: String, lang: String): Int? {
        if (title.isBlank()) return null
        val params = buildMap {
            put("query", title)
            put("language", lang)
            put("include_adult", "false")
            if (year.length == 4) put(if (isTv) "first_air_date_year" else "year", year)
        }
        val path = if (isTv) "search/tv" else "search/movie"
        var results = request(path, params).arr("results")
        if (results.size() == 0 && year.length == 4) {
            results = request(path, params - (if (isTv) "first_air_date_year" else "year")).arr("results")
        }
        return results.firstOrNull()?.obj()?.int("id")?.takeIf { it > 0 }
    }

    private suspend fun details(isTv: Boolean, id: Int, lang: String): TmdbInfo {
        val type = if (isTv) "tv" else "movie"
        val append = if (isTv) "credits,images,videos,content_ratings" else "credits,images,videos,release_dates"
        val o = request(
            "$type/$id",
            mapOf("language" to lang, "append_to_response" to append, "include_image_language" to "$lang,en,null"),
        )
        // Missing Arabic text falls back to English.
        val en = if (lang != "en" && (o.str("overview").isBlank() || o.str(if (isTv) "name" else "title").isBlank())) {
            runCatching { request("$type/$id", mapOf("language" to "en-US")) }.getOrNull()
        } else null

        val images = o.get("images")?.obj()
        fun pick(list: String, langs: List<String?>): String {
            val arr = images?.arr(list)?.mapNotNull { it.obj() }.orEmpty()
            for (l in langs) {
                val best = arr.filter { (it.strOrNull("iso_639_1")) == l }.maxByOrNull { it.dbl("vote_average") * 10 + it.int("vote_count") }
                if (best != null) return best.str("file_path")
            }
            return ""
        }
        val logo = pick("logos", listOf(lang, "en", null))
        val poster = pick("posters", listOf(lang)).ifBlank { o.str("poster_path") }
        val backdrop = pick("backdrops", listOf(null)).ifBlank { o.str("backdrop_path") }

        val credits = o.get("credits")?.obj()
        val cast = credits?.arr("cast")?.mapNotNull { it.obj() }?.take(15)?.map {
            TmdbPerson(it.str("name"), it.str("character"), img(it.str("profile_path"), "w185"))
        }.orEmpty()
        val creators = if (isTv) {
            o.arr("created_by").mapNotNull { it.obj()?.str("name") }
        } else {
            credits?.arr("crew")?.mapNotNull { it.obj() }?.filter { it.str("job") == "Director" }?.map { it.str("name") }.orEmpty()
        }
        val videos = o.get("videos")?.obj()?.arr("results")?.mapNotNull { it.obj() }.orEmpty()
            .filter { it.str("site") == "YouTube" && it.str("type") in setOf("Trailer", "Teaser") }
        val trailer = (videos.firstOrNull { it.str("type") == "Trailer" } ?: videos.firstOrNull())?.str("key").orEmpty()
            .ifBlank { trailerFallback(type, id, lang) }
        val cert = if (isTv) {
            o.get("content_ratings")?.obj()?.arr("results")?.mapNotNull { it.obj() }
                ?.let { l -> (l.firstOrNull { it.str("iso_3166_1") == "SA" } ?: l.firstOrNull { it.str("iso_3166_1") == "US" })?.str("rating") }
                .orEmpty()
        } else {
            o.get("release_dates")?.obj()?.arr("results")?.mapNotNull { it.obj() }
                ?.let { l -> l.firstOrNull { it.str("iso_3166_1") == "SA" } ?: l.firstOrNull { it.str("iso_3166_1") == "US" } }
                ?.arr("release_dates")?.mapNotNull { it.obj()?.str("certification") }?.firstOrNull { it.isNotBlank() }
                .orEmpty()
        }
        val title = o.str(if (isTv) "name" else "title").ifBlank { en?.str(if (isTv) "name" else "title").orEmpty() }
        return TmdbInfo(
            id = id,
            isTv = isTv,
            title = title,
            originalTitle = o.str(if (isTv) "original_name" else "original_title"),
            overview = o.str("overview").ifBlank { en?.str("overview").orEmpty() },
            tagline = o.str("tagline"),
            rating = o.dbl("vote_average"),
            votes = o.int("vote_count"),
            year = o.str(if (isTv) "first_air_date" else "release_date").take(4),
            runtimeMin = if (isTv) o.arr("episode_run_time").firstOrNull()?.let { runCatching { it.asInt }.getOrDefault(0) } ?: 0 else o.int("runtime"),
            genres = o.arr("genres").mapNotNull { it.obj()?.str("name") }.filter { it.isNotBlank() },
            backdrop = img(backdrop, "w1280"),
            poster = img(poster, "w500"),
            logo = img(logo, "w500"),
            cast = cast,
            creators = creators,
            trailerKey = trailer,
            certification = cert,
            seasons = o.int("number_of_seasons"),
        )
    }

    /** Arabic results often have no trailer — try English. */
    private suspend fun trailerFallback(type: String, id: Int, lang: String): String {
        if (lang == "en") return ""
        return runCatching {
            request("$type/$id/videos", mapOf("language" to "en-US")).arr("results").mapNotNull { it.obj() }
                .filter { it.str("site") == "YouTube" && it.str("type") in setOf("Trailer", "Teaser") }
                .sortedBy { if (it.str("type") == "Trailer") 0 else 1 }
                .firstOrNull()?.str("key").orEmpty()
        }.getOrDefault("")
    }

    private suspend fun request(
        path: String,
        params: Map<String, String>,
        overrideKey: String? = null,
        useCache: Boolean = true,
    ): JsonObject {
        val k = overrideKey ?: key()
        if (k.isBlank()) throw NovaException(ErrorKind.AUTH_FAILED)
        val bearer = k.startsWith("eyJ")
        val query = params.entries.joinToString("&") { (a, b) -> "$a=" + URLEncoder.encode(b, "UTF-8") }
        val publicUrl = "$BASE$path" + if (query.isNotEmpty()) "?$query" else ""
        val cacheFile = File(diskDir, md5(publicUrl) + ".json")
        if (useCache && cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < TTL_MS) {
            runCatching { return JsonParser.parseString(cacheFile.readText()).asJsonObject }
        }
        val url = if (bearer) publicUrl else publicUrl + (if (query.isEmpty()) "?" else "&") + "api_key=" + URLEncoder.encode(k, "UTF-8")
        val body = fetcher.string(url, USER_AGENT, if (bearer) "Bearer $k" else null)
        val json = JsonParser.parseString(body).asJsonObject
        if (useCache) withContext(Dispatchers.IO) { runCatching { cacheFile.writeText(body) } }
        return json
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY = "key"
        private const val BASE = "https://api.themoviedb.org/3/"
        private const val USER_AGENT = "NOVA-IPTV/1.0 (Android)"
        private const val TTL_MS = 7L * 24 * 3600 * 1000
        private val YEAR_IN_TITLE = Regex("""\s*[(\[](\d{4})[)\]]\s*""")
        private val GENERIC_EPISODE = Regex("""^(Episode|الحلقة)\s*\d+$""", RegexOption.IGNORE_CASE)

        fun img(path: String, size: String): String = if (path.isBlank()) "" else "https://image.tmdb.org/t/p/$size$path"
    }
}

private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
private fun JsonObject.arr(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
private fun JsonObject.strOrNull(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.str(name: String): String = strOrNull(name).orEmpty()
private fun JsonObject.int(name: String): Int = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0
private fun JsonObject.dbl(name: String): Double = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
