package com.nova.iptv.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

data class XCategory(val id: String, val name: String)

data class XAuth(
    val authenticated: Boolean,
    val status: String,
    val expiresAtMs: Long,
    val maxConnections: Int,
    val activeConnections: Int,
)

data class XEpisode(
    val id: String,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val ext: String,
    val plot: String,
    val image: String,
    val durationSecs: Int,
)

data class XSeriesInfo(
    val name: String,
    val tmdbId: String = "",
    val plot: String,
    val cover: String,
    val genre: String,
    val rating: String,
    val releaseDate: String,
    val cast: String,
    val seasons: Map<Int, List<XEpisode>>,
)

data class XVodInfo(
    val plot: String,
    val genre: String,
    val duration: String,
    val releaseDate: String,
    val rating: String,
    val cast: String,
    val director: String,
    val image: String,
    val backdrop: String,
    val tmdbId: String = "",
)

/**
 * Streaming, type-tolerant parser for Xtream Codes JSON.
 * Providers are inconsistent (numbers as strings, nulls, objects instead of arrays),
 * so every primitive is read as a string and nested structures are skipped.
 */
object XtreamParser {

    fun readFlatObject(r: JsonReader): Map<String, String> {
        val map = HashMap<String, String>(24)
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> map[name] = r.nextString()
                JsonToken.BOOLEAN -> map[name] = if (r.nextBoolean()) "1" else "0"
                JsonToken.NULL -> r.nextNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return map
    }

    /** Iterates over an array of objects (or an object whose values are objects). Returns count. */
    fun readObjects(reader: Reader, onItem: (Map<String, String>) -> Unit): Int {
        val r = JsonReader(reader)
        r.isLenient = true
        var count = 0
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        onItem(readFlatObject(r)); count++
                    } else {
                        r.skipValue()
                    }
                }
                r.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    r.nextName()
                    if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        onItem(readFlatObject(r)); count++
                    } else {
                        r.skipValue()
                    }
                }
                r.endObject()
            }
            else -> r.skipValue()
        }
        return count
    }

    fun readCategories(reader: Reader): List<XCategory> {
        val out = ArrayList<XCategory>()
        readObjects(reader) { m ->
            val id = m["category_id"] ?: return@readObjects
            out += XCategory(id, m["category_name"].orEmpty().ifBlank { id })
        }
        return out
    }

    fun parseAuth(reader: Reader): XAuth {
        val root = JsonParser.parseReader(reader)
        if (!root.isJsonObject) return XAuth(false, "", 0, 0, 0)
        val info = root.asJsonObject.obj("user_info") ?: return XAuth(false, "", 0, 0, 0)
        val authRaw = info.str("auth")
        val auth = authRaw == "1" || authRaw.equals("true", true)
        val exp = info.str("exp_date").toLongOrNull()?.let { it * 1000 } ?: 0L
        return XAuth(
            authenticated = auth,
            status = info.str("status"),
            expiresAtMs = exp,
            maxConnections = info.str("max_connections").toIntOrNull() ?: 0,
            activeConnections = info.str("active_cons").toIntOrNull() ?: 0,
        )
    }

    fun parseSeriesInfo(reader: Reader): XSeriesInfo {
        val root = JsonParser.parseReader(reader)
        val obj = if (root.isJsonObject) root.asJsonObject else JsonObject()
        val info = obj.obj("info") ?: JsonObject()
        val seasons = sortedMapOf<Int, MutableList<XEpisode>>()
        fun addEpisode(e: JsonElement, seasonKey: Int?) {
            if (!e.isJsonObject) return
            val o = e.asJsonObject
            val epInfo = o.obj("info") ?: JsonObject()
            val season = o.str("season").toIntOrNull() ?: seasonKey ?: 1
            val ep = XEpisode(
                id = o.str("id"),
                season = season,
                episodeNum = o.str("episode_num").toIntOrNull() ?: 0,
                title = o.str("title"),
                ext = o.str("container_extension"),
                plot = epInfo.str("plot"),
                image = epInfo.str("movie_image"),
                durationSecs = epInfo.str("duration_secs").toIntOrNull() ?: 0,
            )
            if (ep.id.isNotEmpty()) seasons.getOrPut(season) { mutableListOf() }.add(ep)
        }
        val episodes = obj.get("episodes")
        when {
            episodes == null || episodes.isJsonNull -> Unit
            episodes.isJsonObject -> episodes.asJsonObject.entrySet().forEach { (k, v) ->
                val key = k.toIntOrNull()
                if (v.isJsonArray) v.asJsonArray.forEach { addEpisode(it, key) } else addEpisode(v, key)
            }
            episodes.isJsonArray -> episodes.asJsonArray.forEach { el ->
                if (el.isJsonArray) (el as JsonArray).forEach { addEpisode(it, null) } else addEpisode(el, null)
            }
        }
        seasons.values.forEach { list -> list.sortBy { it.episodeNum } }
        return XSeriesInfo(
            name = info.str("name"),
            tmdbId = info.str("tmdb").ifBlank { info.str("tmdb_id") }.filter { it.isDigit() },
            plot = info.str("plot"),
            cover = info.str("cover"),
            genre = info.str("genre"),
            rating = info.str("rating"),
            releaseDate = info.str("releaseDate").ifBlank { info.str("release_date") },
            cast = info.str("cast"),
            seasons = seasons,
        )
    }

    fun parseVodInfo(reader: Reader): XVodInfo {
        val root = JsonParser.parseReader(reader)
        val obj = if (root.isJsonObject) root.asJsonObject else JsonObject()
        val info = obj.obj("info") ?: JsonObject()
        val backdrop = info.get("backdrop_path")?.let { el ->
            when {
                el.isJsonArray && el.asJsonArray.size() > 0 && el.asJsonArray[0].isJsonPrimitive -> el.asJsonArray[0].asString
                el.isJsonPrimitive -> el.asString
                else -> ""
            }
        }.orEmpty()
        return XVodInfo(
            plot = info.str("plot").ifBlank { info.str("description") },
            genre = info.str("genre"),
            duration = info.str("duration"),
            releaseDate = info.str("releasedate").ifBlank { info.str("release_date") },
            rating = info.str("rating"),
            cast = info.str("cast").ifBlank { info.str("actors") },
            director = info.str("director"),
            image = info.str("movie_image").ifBlank { info.str("cover_big") },
            backdrop = backdrop,
            tmdbId = info.str("tmdb_id").ifBlank { info.str("tmdb") }.filter { it.isDigit() },
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(key: String): String {
        val el = get(key) ?: return ""
        return if (el.isJsonPrimitive) el.asString else ""
    }
}
