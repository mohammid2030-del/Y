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
    val available: Boolean get() = BuildConfig.OPENAI_API_KEY.isNotBlank()

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
        return o.objects().entries.filter { it.value.str("ep").isNotBlank() }
            .sortedByDescending { it.value.num("t") }.map { it.key to it.value.str("ep") }
    }

    suspend fun request(subId: Long, seriesId: String, episodeId: String, force: Boolean = false) {
        val b = base(subId) ?: return
        cloud.put("$b/req/${Cloud.safe(seriesId)}", mapOf("ep" to episodeId, "t" to System.currentTimeMillis() / 1000, "f" to force))
    }

    /** "Start now" was pressed: don't wait for the provider to report a free connection. */
    suspend fun forced(subId: Long, seriesId: String): Boolean {
        val b = base(subId) ?: return false
        return runCatching { cloud.get("$b/req/${Cloud.safe(seriesId)}") }.getOrNull()?.get("f")?.let { it.isJsonPrimitive && it.asBoolean } ?: false
    }

    /** Finished by the current method (older runs could save lines the speech model made up on broken sound). */
    suspend fun isDone(subId: Long, seriesId: String, episodeId: String): Boolean {
        val b = base(subId) ?: return false
        val o = runCatching { cloud.get("$b/eps/${Cloud.safe(seriesId)}/${Cloud.safe(episodeId)}") }.getOrNull() ?: return false
        return o.str("s") == "done" && o.num("v") >= VERSION
    }

    suspend fun cancel(subId: Long, seriesId: String) {
        val b = base(subId) ?: return
        cloud.put("$b/req/${Cloud.safe(seriesId)}", com.google.gson.JsonNull.INSTANCE)
    }

    /** Lines the speech model invents on silence or music (TV credits, "subscribe", …). */
    private fun isHallucination(t: String): Boolean {
        val l = t.lowercase()
        return HALLUCINATIONS.any { l.contains(it) }
    }

    /** The last API problem (shown on the series page so it's clear why a translation stopped). */
    @Volatile var lastError: String = ""

    private fun errorMessage(body: String): String =
        runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonObject("error").get("message").asString }.getOrNull()?.take(120) ?: ""

    /**
     * What the Android device is doing for this series, for every series page to show:
     * r = "working" (on episode l, p %), "busy" (the subscription's connections are all in use), "error" (m = why),
     * "done" (reached the last episode), "stopped".
     */
    suspend fun note(subId: Long, seriesId: String, r: String, label: String = "", p: Int = 0, m: String = "") {
        val b = base(subId) ?: return
        cloud.put("$b/w/${Cloud.safe(seriesId)}", mapOf("r" to r, "l" to label, "p" to p, "m" to m.take(160), "t" to System.currentTimeMillis() / 1000))
    }

    /** "An Android device with the app looked for work at …" (shown when nothing has started yet). */
    suspend fun heartbeat(subId: Long) {
        val b = base(subId) ?: return
        cloud.put("$b/dev", mapOf("t" to System.currentTimeMillis() / 1000, "n" to android.os.Build.MODEL.orEmpty()))
    }

    data class Status(
        val pending: Boolean, val r: String, val label: String, val p: Int, val m: String,
        /** seconds since the note / since a device last checked (-1 = never) */
        val noteAge: Long, val devAge: Long, val device: String,
    )

    suspend fun status(subId: Long, seriesId: String): Status? {
        val b = base(subId) ?: return null
        val now = System.currentTimeMillis() / 1000
        val req = runCatching { cloud.get("$b/req/${Cloud.safe(seriesId)}") }.getOrNull()
        val w = runCatching { cloud.get("$b/w/${Cloud.safe(seriesId)}") }.getOrNull()
        val dev = runCatching { cloud.get("$b/dev") }.getOrNull()
        return Status(
            pending = req != null && req.str("ep").isNotBlank(),
            r = w?.str("r").orEmpty(), label = w?.str("l").orEmpty(), p = w?.num("p")?.toInt() ?: 0, m = w?.str("m").orEmpty(),
            noteAge = w?.num("t")?.toLong()?.takeIf { it > 0 }?.let { now - it } ?: -1,
            devAge = dev?.num("t")?.toLong()?.takeIf { it > 0 }?.let { now - it } ?: -1,
            device = dev?.str("n").orEmpty(),
        )
    }

    /** Is this series still asked for (false once someone presses Stop)? */
    suspend fun requested(subId: Long, seriesId: String): Boolean {
        val b = base(subId) ?: return false
        return runCatching { cloud.get("$b/req/${Cloud.safe(seriesId)}") }.getOrNull()?.let { it.str("ep").isNotBlank() } ?: false
    }

    /** Forgets an unfinished episode (stopped half way). */
    suspend fun clear(subId: Long, seriesId: String, episodeId: String) {
        val b = base(subId) ?: return
        cloud.put("$b/eps/${Cloud.safe(seriesId)}/${Cloud.safe(episodeId)}", com.google.gson.JsonNull.INSTANCE)
    }

    /** After a crash: episodes left "working" go back to nothing so the page doesn't show progress forever. */
    suspend fun clearWorking(subId: Long, seriesId: String) {
        val b = base(subId) ?: return
        val o = runCatching { cloud.get("$b/eps/${Cloud.safe(seriesId)}") }.getOrNull()?.takeIf { it.isJsonObject } ?: return
        for ((ep, v) in o.asJsonObject.entrySet()) {
            if (v.isJsonObject && v.asJsonObject.get("s")?.asString == "working") cloud.put("$b/eps/${Cloud.safe(seriesId)}/$ep", com.google.gson.JsonNull.INSTANCE)
        }
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
        v["s"] = state; v["p"] = progress; v["t"] = System.currentTimeMillis() / 1000; v["v"] = VERSION
        if (vtt != null) v["vtt"] = vtt
        cloud.put("$b/eps/${Cloud.safe(seriesId)}/${Cloud.safe(episodeId)}", v)
    }

    /** The finished Arabic subtitles of an episode (WebVTT), or null. */
    suspend fun vtt(subId: Long, seriesId: String, episodeId: String): String? {
        val b = base(subId) ?: return null
        val o = runCatching { cloud.get("$b/eps/${Cloud.safe(seriesId)}/${Cloud.safe(episodeId)}") }.getOrNull() ?: return null
        return o.str("vtt").takeIf { o.str("s") == "done" && o.num("v") >= VERSION && it.isNotBlank() }
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
                .addFormDataPart("temperature", "0")
                .addFormDataPart("prompt", "Türk dizisi bölümü. Karakterlerin konuşmaları.")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("file", chunk.file.name, chunk.file.asRequestBody("audio/mp4".toMediaType()))
                .build()
            val req = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}").post(body).build()
            val res = runCatching { http.newCall(req).execute() }.onFailure { lastError = it.message.orEmpty() }.getOrNull()
                ?: return@withContext null
            res.use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) lastError = "HTTP ${r.code} ${errorMessage(text)}"
                if (!r.isSuccessful && r.code != 401 && r.code != 429 && model != "whisper-1") return@use   // try the next model
                if (!r.isSuccessful) return@withContext null
                val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return@withContext null
                val segs = o.getAsJsonArray("segments") ?: if (model != "whisper-1") return@use else return@withContext null
                return@withContext segs.mapNotNull { e ->
                    val s = e.asJsonObject
                    val t = s.get("text")?.asString?.trim().orEmpty()
                    val noSpeech = s.get("no_speech_prob")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
                    if (t.isEmpty() || noSpeech > 0.8 || isHallucination(t)) null else Cue(chunk.startSec + s.get("start").asDouble, chunk.startSec + s.get("end").asDouble, t)
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
            var arabic: Map<Int, String>? = null
            // one OpenAI key for everything: a small, fast model is plenty for subtitle lines
            for (attempt in 0 until 4) {
                val model = TRANSLATE_MODELS[attempt % TRANSLATE_MODELS.size]
                val body = JsonObject().apply {
                    addProperty("model", model)
                    addProperty("temperature", 0.3)
                    add("messages", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt) }) })
                }
                val req = Request.Builder().url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                val text = runCatching {
                    http.newCall(req).execute().use { r ->
                        val t = r.body?.string()
                        if (r.isSuccessful) t else { lastError = "HTTP ${r.code} ${errorMessage(t.orEmpty())}"; null }
                    }
                }.onFailure { lastError = it.message.orEmpty() }.getOrNull() ?: continue
                val reply = runCatching {
                    JsonParser.parseString(text).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                        .getAsJsonObject("message").get("content").asString
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
        const val VERSION = 2
        private val TRANSLATE_MODELS = listOf("gpt-4.1-mini", "gpt-4o-mini")
        private val HALLUCINATIONS = listOf(
            "sesli betimleme", "altyazı", "altyazi", "abone ol", "izlediğiniz için teşekkür", "seslendiren", "metin yazarı",
            "betimleme metni", "subtitles by", "amara.org", "thanks for watching",
        )

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
