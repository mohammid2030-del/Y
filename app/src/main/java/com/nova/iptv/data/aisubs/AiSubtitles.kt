package com.nova.iptv.data.aisubs

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.iptv.BuildConfig
import com.nova.iptv.data.cloud.Cloud
import com.nova.iptv.data.cloud.num
import com.nova.iptv.data.cloud.objects
import com.nova.iptv.data.cloud.str
import com.nova.iptv.data.viewers.ViewersRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arabic subtitles made by AI for episodes that have none: the Turkish (or any) speech is written out with its timing
 * (OpenAI speech-to-text), then translated line by line into Arabic (Claude), keeping every line's timing.
 *
 * Shared through Firebase with every device of the subscription:
 *   nova/v1/<key>/aisubs/req/<series>          = { ep: first episode id, t }      ← asked from any device
 *   nova/v1/<key>/aisubs/eps/<series>/<episode> = { s: state, p: progress, t, vtt? } state: queued / working / done / error
 */
@Singleton
class AiSubtitles @Inject constructor(
    private val cloud: Cloud,
    private val viewers: ViewersRepository,
) {
    val available: Boolean get() = BuildConfig.OPENAI_API_KEY.isNotBlank() && BuildConfig.ANTHROPIC_API_KEY.isNotBlank()

    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES).build()

    data class Cue(val start: Double, val end: Double, val text: String)

    // ---- Firebase

    suspend fun base(subId: Long): String? {
        if (!cloud.enabled) return null
        val k = viewers.subKey(subId) ?: return null
        return "nova/v1/$k/aisubs"
    }

    /** Series asked for, newest first: (series id, first episode id). */
    suspend fun requests(subId: Long): List<Pair<String, String>> {
        val b = base(subId) ?: return emptyList()
        val o = runCatching { cloud.get("$b/req") }.getOrNull() ?: return emptyList()
        return o.objects().entries.sortedByDescending { it.value.num("t") }.map { it.key to it.value.str("ep") }
    }

    suspend fun request(subId: Long, seriesId: String, episodeId: String) {
        val b = base(subId) ?: return
        cloud.put("$b/req/${Cloud.safe(seriesId)}", mapOf("ep" to episodeId, "t" to System.currentTimeMillis() / 1000))
    }

    suspend fun cancel(subId: Long, seriesId: String) {
        val b = base(subId) ?: return
        cloud.put("$b/req/${Cloud.safe(seriesId)}", com.google.gson.JsonNull.INSTANCE)
    }

    /** "queued" / "working" / "done" / "error" / "" and the progress (0–100). */
    suspend fun state(subId: Long, seriesId: String, episodeId: String): Pair<String, Int> {
        val b = base(subId) ?: return "" to 0
        // ask only for the small fields (the finished subtitles can be large)
        val s = runCatching { cloud.raw("$b/eps/${Cloud.safe(seriesId)}/${Cloud.safe(episodeId)}/s") }.getOrNull().orEmpty()
        val p = runCatching { cloud.raw("$b/eps/${Cloud.safe(seriesId)}/${Cloud.safe(episodeId)}/p") }.getOrNull()?.toIntOrNull() ?: 0
        return s to p
    }

    suspend fun mark(subId: Long, seriesId: String, episodeId: String, state: String, progress: Int = 0, vtt: String? = null) {
        val b = base(subId) ?: return
        val v = HashMap<String, Any>()
        v["s"] = state; v["p"] = progress; v["t"] = System.currentTimeMillis() / 1000
        if (vtt != null) v["vtt"] = vtt
        cloud.put("$b/eps/${Cloud.safe(seriesId)}/${Cloud.safe(episodeId)}", v)
    }

    /** The finished Arabic subtitles of an episode (WebVTT), or null. */
    suspend fun vtt(subId: Long, seriesId: String, episodeId: String): String? {
        val b = base(subId) ?: return null
        val o = runCatching { cloud.get("$b/eps/${Cloud.safe(seriesId)}/${Cloud.safe(episodeId)}") }.getOrNull() ?: return null
        return o.str("vtt").takeIf { o.str("s") == "done" && it.isNotBlank() }
    }

    // ---- speech → text with timing

    /** One speech file → timed lines (times in the whole episode). */
    suspend fun transcribe(chunk: AudioChunker.Chunk, language: String = "tr"): List<Cue>? = withContext(Dispatchers.IO) {
        // the newest model first; the older one (always gives line timings) if the newest refuses the format
        for (model in listOf("gpt-transcribe", "whisper-1")) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("file", chunk.file.name, chunk.file.asRequestBody("audio/mp4".toMediaType()))
                .build()
            val req = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}").post(body).build()
            val res = runCatching { http.newCall(req).execute() }.getOrNull() ?: return@withContext null
            res.use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 400 && model != "whisper-1") return@use   // try the next model
                if (!r.isSuccessful) return@withContext null
                val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return@withContext null
                val segs = o.getAsJsonArray("segments") ?: if (model != "whisper-1") return@use else return@withContext null
                return@withContext segs.mapNotNull { e ->
                    val s = e.asJsonObject
                    val t = s.get("text")?.asString?.trim().orEmpty()
                    if (t.isEmpty()) null else Cue(chunk.startSec + s.get("start").asDouble, chunk.startSec + s.get("end").asDouble, t)
                }
            }
        }
        null
    }

    // ---- translation

    /** Lines → Arabic, same count and order (batches of 120 lines, the story so far as context). */
    suspend fun translate(cues: List<Cue>, from: String = "Turkish"): List<Cue>? = withContext(Dispatchers.IO) {
        val out = ArrayList<Cue>(cues.size)
        for (batch in cues.chunked(120)) {
            val numbered = JsonArray().apply { batch.forEachIndexed { i, c -> add(JsonObject().apply { addProperty("i", i); addProperty("t", c.text) }) } }
            val prompt = """
                You translate TV drama subtitles from $from into natural Modern Standard Arabic for Arab viewers.
                Keep each line short and readable on screen, keep names as they are written in Arabic for this show,
                and keep the meaning and tone (romance, anger, humour). Do not add notes.
                Input: a JSON array of {"i": index, "t": line}. Output ONLY a JSON array of {"i": index, "t": Arabic line},
                one for every input line, same indexes.

                ${numbered}
            """.trimIndent()
            val body = JsonObject().apply {
                addProperty("model", "claude-haiku-5-5")
                addProperty("max_tokens", 16000)
                add("messages", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt) }) })
            }
            val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", BuildConfig.ANTHROPIC_API_KEY).header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            var arabic: Map<Int, String>? = null
            for (attempt in 0 until 3) {
                val text = runCatching { http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } }.getOrNull() ?: continue
                val reply = runCatching {
                    JsonParser.parseString(text).asJsonObject.getAsJsonArray("content").first { it.asJsonObject.get("type").asString == "text" }
                        .asJsonObject.get("text").asString
                }.getOrNull() ?: continue
                val json = reply.substring(reply.indexOf('[').coerceAtLeast(0), (reply.lastIndexOf(']') + 1).coerceAtLeast(0))
                arabic = runCatching {
                    JsonParser.parseString(json).asJsonArray.associate { e -> e.asJsonObject.get("i").asInt to e.asJsonObject.get("t").asString }
                }.getOrNull()
                if (arabic != null) break
            }
            val a = arabic ?: return@withContext null
            batch.forEachIndexed { i, c -> out += c.copy(text = a[i]?.trim()?.ifEmpty { null } ?: c.text) }
        }
        out
    }

    companion object {
        /** WebVTT, the subtitle format every player here can read. */
        fun toVtt(cues: List<Cue>): String {
            fun t(s: Double): String {
                val ms = (s * 1000).toLong().coerceAtLeast(0)
                return "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
            }
            val sb = StringBuilder("WEBVTT\n\n")
            cues.forEach { c -> sb.append(t(c.start)).append(" --> ").append(t(maxOf(c.end, c.start + 0.8))).append('\n').append(c.text).append("\n\n") }
            return sb.toString()
        }

        /** Parses WebVTT back into cues (for the player). */
        fun parseVtt(vtt: String): List<Cue> {
            val re = Regex("""(\d+):(\d+):(\d+)[.,](\d+)\s*-->\s*(\d+):(\d+):(\d+)[.,](\d+)""")
            val out = ArrayList<Cue>()
            val blocks = vtt.replace("\r", "").split("\n\n")
            for (b in blocks) {
                val lines = b.lines().filter { it.isNotBlank() }
                val i = lines.indexOfFirst { re.containsMatchIn(it) }
                if (i < 0) continue
                val g = re.find(lines[i])!!.groupValues
                fun sec(h: String, m: String, s: String, f: String) = h.toInt() * 3600 + m.toInt() * 60 + s.toInt() + f.padEnd(3, '0').take(3).toInt() / 1000.0
                out += Cue(sec(g[1], g[2], g[3], g[4]), sec(g[5], g[6], g[7], g[8]), lines.drop(i + 1).joinToString("\n"))
            }
            return out
        }
    }
}
