package com.nova.iptv.data.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader

/** A time range inside an episode, in ms. end = -1 means "until the end of the video". */
data class Segment(val startMs: Long, val endMs: Long)

data class EpisodeSegments(
    val intro: Segment? = null,
    val recap: Segment? = null,
    val credits: Segment? = null,
)

/** Parses TheIntroDB `/v4/media` responses: {"intro":[{"start_ms":..,"end_ms":..}], "recap":[..], "credits":[..]} */
object SegmentsParser {
    fun parse(reader: Reader): EpisodeSegments {
        val root = JsonParser.parseReader(reader)
        if (!root.isJsonObject) return EpisodeSegments()
        val o = root.asJsonObject
        return EpisodeSegments(
            intro = first(o, "intro", endRequired = true),
            recap = first(o, "recap", endRequired = true),
            credits = first(o, "credits", endRequired = false),
        )
    }

    private fun first(o: JsonObject, key: String, endRequired: Boolean): Segment? {
        val el: JsonElement = o.get(key) ?: return null
        val obj = when {
            el.isJsonArray && el.asJsonArray.size() > 0 && el.asJsonArray[0].isJsonObject -> el.asJsonArray[0].asJsonObject
            el.isJsonObject -> el.asJsonObject
            else -> return null
        }
        val start = num(obj, "start_ms") ?: 0L
        val end = num(obj, "end_ms")
        if (end == null) return if (endRequired) null else Segment(start, -1L)
        return if (end > start) Segment(start, end) else null
    }

    private fun num(o: JsonObject, key: String): Long? {
        val v = o.get(key) ?: return null
        if (!v.isJsonPrimitive) return null
        return runCatching { v.asDouble.toLong() }.getOrNull()
    }
}
