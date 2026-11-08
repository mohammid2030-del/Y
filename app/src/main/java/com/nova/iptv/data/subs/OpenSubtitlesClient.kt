package com.nova.iptv.data.subs

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Error returned by OpenSubtitles, with the server's own explanation. */
class OpenSubtitlesException(val code: Int, val serverMessage: String) : Exception("HTTP $code $serverMessage")

data class FeatureResult(val title: String, val year: String, val tmdbId: String, val type: String)

data class FoundSubtitle(val fileId: Long, val language: String, val machine: Boolean, val downloads: Long)

/** OpenSubtitles.com REST API v1 (user supplies their own free API key). */
@Singleton
class OpenSubtitlesClient @Inject constructor(private val http: OkHttpClient) {
    private val jsonType = "application/json".toMediaType()

    private fun request(url: String, apiKey: String, token: String?): Request.Builder =
        Request.Builder().url(url)
            .header("Api-Key", apiKey)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    private fun execute(req: Request): JsonObject {
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val el = runCatching { JsonParser.parseString(body) }.getOrNull()
            val obj = if (el != null && el.isJsonObject) el.asJsonObject else JsonObject()
            if (!resp.isSuccessful) throw OpenSubtitlesException(resp.code, serverMessage(obj, body))
            return obj
        }
    }

    private fun serverMessage(o: JsonObject, body: String): String {
        o.get("message")?.takeIf { it.isJsonPrimitive }?.asString?.let { return it }
        val errors = o.get("errors")
        if (errors != null && errors.isJsonArray && errors.asJsonArray.size() > 0) {
            return errors.asJsonArray.joinToString(" · ") { if (it.isJsonPrimitive) it.asString else it.toString() }
        }
        return body.take(160)
    }

    data class Login(val token: String, val host: String, val allowedDownloads: Int)

    suspend fun login(apiKey: String, user: String, pass: String): Login = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply { addProperty("username", user); addProperty("password", pass) }
        val o = execute(
            request("$BASE/login", apiKey, null)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonType)).build()
        )
        val token = o.get("token")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw OpenSubtitlesException(401, "no token")
        val host = o.get("base_url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val allowed = o.getAsJsonObject("user")?.get("allowed_downloads")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        Login(token, host, allowed)
    }

    /** params must contain tmdb_id, or parent_tmdb_id + season_number + episode_number. */
    suspend fun search(apiKey: String, token: String?, params: Map<String, String>, languages: String): List<FoundSubtitle> =
        withContext(Dispatchers.IO) {
            // the API asks for alphabetically sorted, lower-case parameters
            val query = (params + mapOf("languages" to languages, "order_by" to "download_count"))
                .toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
            val o = execute(request("$BASE/subtitles?$query", apiKey, token).get().build())
            val data = o.getAsJsonArray("data") ?: return@withContext emptyList()
            data.mapNotNull { el ->
                val a = el.asJsonObject.getAsJsonObject("attributes") ?: return@mapNotNull null
                val files = a.getAsJsonArray("files")
                if (files == null || files.size() == 0) return@mapNotNull null
                val fileId = files[0].asJsonObject.get("file_id")?.asLong ?: return@mapNotNull null
                fun bool(k: String) = a.get(k)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                FoundSubtitle(
                    fileId = fileId,
                    language = a.get("language")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    machine = bool("machine_translated") || bool("ai_translated"),
                    downloads = a.get("download_count")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                )
            }.sortedWith(compareBy<FoundSubtitle> { it.machine }.thenByDescending { it.downloads })
        }

    /** Title search (English / original / Turkish names) to find the TMDB id of a show or movie. */
    suspend fun features(apiKey: String, query: String, tv: Boolean): List<FeatureResult> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim().lowercase(), "UTF-8").replace("+", "%20")
        val url = "$BASE/features?query=$q&type=${if (tv) "tvshow" else "movie"}"
        val o = execute(request(url, apiKey, null).get().build())
        val data = o.getAsJsonArray("data") ?: return@withContext emptyList()
        data.mapNotNull { el ->
            val a = el.asJsonObject.getAsJsonObject("attributes") ?: return@mapNotNull null
            fun str(k: String) = a.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val tmdb = str("tmdb_id").filter { it.isDigit() }
            if (tmdb.isEmpty()) return@mapNotNull null
            FeatureResult(
                title = str("title").ifBlank { str("original_title") },
                year = str("year"),
                tmdbId = tmdb,
                type = str("feature_type"),
            )
        }.distinctBy { it.tmdbId }.take(15)
    }

    /** Downloads the SRT text of a file. */
    suspend fun download(apiKey: String, token: String?, host: String, fileId: Long): String = withContext(Dispatchers.IO) {
        val base = if (host.isNotBlank()) "https://$host/api/v1" else BASE
        val payload = JsonObject().apply { addProperty("file_id", fileId); addProperty("sub_format", "srt") }
        val o = execute(
            request("$base/download", apiKey, token)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonType)).build()
        )
        val link = o.get("link")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw OpenSubtitlesException(406, o.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "download limit")
        http.newCall(Request.Builder().url(link).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw OpenSubtitlesException(r.code, "file download failed")
            val bytes = r.body?.bytes() ?: ByteArray(0)
            decode(bytes)
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // Old Arabic/Turkish files may still be in legacy code pages
        return if (utf8.contains('�')) {
            val cp1256 = runCatching { String(bytes, charset("windows-1256")) }.getOrNull()
            cp1256 ?: utf8
        } else utf8
    }

    companion object {
        private const val BASE = "https://api.opensubtitles.com/api/v1"
        private const val USER_AGENT = "NOVA IPTV v1.0"
    }
}
