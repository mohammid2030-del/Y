package com.nova.iptv.data.segments

import android.util.LruCache
import com.nova.iptv.data.cloud.Cloud
import com.nova.iptv.data.cloud.num
import com.nova.iptv.data.network.EpisodeSegments
import com.nova.iptv.data.network.Segment
import com.nova.iptv.data.viewers.ViewersRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intro / end-credits times found by audio fingerprinting, shared with every device of the subscription
 * (Android, Apple TV / iPad, Samsung) in Firebase:
 *   nova/v1/<subscription key>/segs/<series id>/<season>_<episode> = { is, ie, cs, d, v, t }
 * is / ie = intro start / end (ms), cs = end credits start (ms), d = episode length (ms), n = 1: analysed, nothing found.
 */
@Singleton
class SharedSegments @Inject constructor(
    private val cloud: Cloud,
    private val viewers: ViewersRepository,
) {
    data class Found(val introStart: Long, val introEnd: Long, val creditsStart: Long, val durationMs: Long, val nothing: Boolean)

    private val cache = LruCache<String, Found>(200)

    private suspend fun base(subId: Long, seriesId: String): String? {
        if (!cloud.enabled) return null
        val key = viewers.subKey(subId) ?: return null
        return "nova/v1/$key/segs/${Cloud.safe(seriesId)}"
    }

    suspend fun get(subId: Long, seriesId: String, season: Int, episode: Int): Found? {
        val ck = "$subId|$seriesId|$season|$episode"
        cache.get(ck)?.let { return it }
        val p = base(subId, seriesId) ?: return null
        // this episode (sound fingerprint or marked by hand), else the season's times marked by hand ("<season>_0")
        var f: Found? = null
        for (k in listOf("${season}_$episode", "${season}_0")) {
            val o = runCatching { cloud.get("$p/$k") }.getOrNull() ?: continue
            val x = Found(o.num("is").toLong(), o.num("ie").toLong(), o.num("cs").toLong(), o.num("d").toLong(), o.num("n") > 0)
            if (x.introEnd > x.introStart || x.creditsStart > 0) { f = x; break }
            if (f == null) f = x
        }
        if (f != null) cache.put(ck, f)
        return f
    }

    /** Every analysed episode of a series ("<season>_<episode>" → times). */
    suspend fun all(subId: Long, seriesId: String): Map<String, Found> {
        val p = base(subId, seriesId) ?: return emptyMap()
        val o = runCatching { cloud.get(p) }.getOrNull() ?: return emptyMap()
        return o.entrySet().mapNotNull { (k, v) ->
            if (!v.isJsonObject) null else {
                val d = v.asJsonObject
                k to Found(d.num("is").toLong(), d.num("ie").toLong(), d.num("cs").toLong(), d.num("d").toLong(), d.num("n") > 0)
            }
        }.toMap()
    }

    suspend fun put(subId: Long, seriesId: String, season: Int, episode: Int, f: Found) {
        val p = base(subId, seriesId) ?: return
        val v = HashMap<String, Any>()
        if (f.introEnd > f.introStart) { v["is"] = f.introStart; v["ie"] = f.introEnd }
        if (f.creditsStart > 0) v["cs"] = f.creditsStart
        if (f.durationMs > 0) v["d"] = f.durationMs
        if (f.introEnd <= f.introStart && f.creditsStart <= 0) v["n"] = 1
        v["v"] = 1
        v["t"] = System.currentTimeMillis() / 1000
        cloud.put("$p/${season}_$episode", v)
        cache.put("$subId|$seriesId|$season|$episode", f)
    }

    /** As skip segments for the player (null when this episode wasn't analysed or nothing was found). */
    fun toSegments(f: Found?): EpisodeSegments? {
        if (f == null || f.nothing) return null
        return EpisodeSegments(
            intro = if (f.introEnd > f.introStart) Segment(f.introStart, f.introEnd) else null,
            credits = if (f.creditsStart > 0) Segment(f.creditsStart, -1L) else null,
        )
    }
}
