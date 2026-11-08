package com.nova.iptv.data.subs

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.iptv.data.ErrorKind
import com.nova.iptv.data.NovaException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

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
            when (resp.code) {
                401, 403 -> throw NovaException(ErrorKind.AUTH_FAILED, resp.code)
                406, 429 -> throw NovaException(ErrorKind.HTTP, resp.code)
            }
            if (!resp.isSuccessful) throw NovaException(ErrorKind.HTTP, resp.code)
            val el = runCatching { JsonParser.parseString(body) }.getOrNull()
            return if (el != null && el.isJsonObject) el.asJsonObject else JsonObject()
        }
    }

    /** Returns (token, baseUrl host) */
    suspend fun login(apiKey: String, user: String, pass: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply { addProperty("username", user); addProperty("password", pass) }
        val o = execute(request("$BASE/login", apiKey, null).post(payload.toString().toRequestBody(jsonType)).build())
        val token = o.get("token")?.takeIf { it.isJsonPrimitive }?.asString ?: throw NovaException(ErrorKind.AUTH_FAILED)
        val host = o.get("base_url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        token to host
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

    /** Downloads the SRT text of a file. */
    suspend fun download(apiKey: String, token: String?, host: String, fileId: Long): String = withContext(Dispatchers.IO) {
        val base = if (host.isNotBlank()) "https://$host/api/v1" else BASE
        val payload = JsonObject().apply { addProperty("file_id", fileId); addProperty("sub_format", "srt") }
        val o = execute(request("$base/download", apiKey, token).post(payload.toString().toRequestBody(jsonType)).build())
        val link = o.get("link")?.takeIf { it.isJsonPrimitive }?.asString ?: throw NovaException(ErrorKind.HTTP, 406)
        http.newCall(Request.Builder().url(link).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw NovaException(ErrorKind.HTTP, r.code)
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
