package com.nova.iptv.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nova.iptv.data.cloud.Cloud
import com.nova.iptv.data.cloud.child
import com.nova.iptv.data.cloud.num
import com.nova.iptv.data.cloud.objects
import com.nova.iptv.data.cloud.str
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.network.HttpFetcher
import com.nova.iptv.data.network.XEpisode
import com.nova.iptv.data.network.XtreamParser
import com.nova.iptv.data.network.XtreamUrls
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.SubscriptionRepository
import com.nova.iptv.data.segments.AudioPrint
import com.nova.iptv.data.segments.SharedSegments
import com.nova.iptv.data.viewers.ViewersRepository
import com.nova.iptv.data.repo.PlaybackRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Finds the intro and the end credits of the episodes people are watching — on any device of the subscription —
 * by comparing the sound of neighbouring episodes (the same theme music = the intro; the same closing music = the
 * credits), then shares the times through Firebase so Apple TV, iPad, Samsung and Android all show
 * "Skip intro" and "Next episode" at the right moment.
 *
 * Polite by design: Wi-Fi / unmetered network only, battery not low, a few episodes per run (the current one and the
 * next ones), and only while the subscription has a free connection — it never takes the last one from a viewer.
 */
@HiltWorker
class SegmentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val subs: SubscriptionRepository,
    private val content: ContentRepository,
    private val viewers: ViewersRepository,
    private val cloud: Cloud,
    private val shared: SharedSegments,
    private val fetcher: HttpFetcher,
) : CoroutineWorker(context, params) {

    private val dir = File(context.filesDir, "prints").apply { mkdirs() }
    private val started = System.currentTimeMillis()
    private fun timeLeft() = System.currentTimeMillis() - started < BUDGET_MS

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!cloud.enabled) return@withContext Result.success()
        var analysed = 0
        for (sub in subs.getAll()) {
            if (sub.type != SourceType.XTREAM || !timeLeft()) continue
            val key = viewers.subKey(sub.id) ?: continue
            for ((seriesId, episodeId) in watching(key)) {
                if (!timeLeft() || analysed >= MAX_EPISODES) break
                analysed += runCatching { series(sub.id, seriesId, episodeId) }.getOrDefault(0)
            }
        }
        cleanup()
        Result.success()
    }

    /** Series being watched on any device of the subscription (latest episode each), newest first. */
    private suspend fun watching(subKey: String): List<Pair<String, String>> {
        val data = cloud.get("nova/v1/$subKey/data") ?: return emptyList()
        val since = System.currentTimeMillis() / 1000.0 - 21 * 86400
        val latest = HashMap<String, Pair<Double, String>>()
        for ((_, viewer) in data.objects()) {
            for ((_, h) in viewer.child("h")?.objects() ?: emptyMap()) {
                if (h.str("k") != ContentType.SERIES || h.str("e").isEmpty()) continue
                val u = h.num("u"); if (u < since) continue
                val sid = h.str("i")
                if ((latest[sid]?.first ?: 0.0) < u) latest[sid] = u to h.str("e")
            }
        }
        return latest.entries.sortedByDescending { it.value.first }.take(8).map { it.key to it.value.second }
    }

    /** Analyses the watched episode's neighbourhood: it and the next three (references: the episodes around them). */
    private suspend fun series(subId: Long, seriesId: String, episodeId: String): Int {
        val sub = subs.get(subId) ?: return 0
        val info = content.seriesInfo(sub, seriesId)
        val all = info.seasons.toSortedMap().values.flatten()
        val i = all.indexOfFirst { it.id == episodeId }
        if (i < 0) return 0
        val season = all[i].season
        val list = all.filter { it.season == season }.sortedBy { it.episodeNum }
        if (list.size < 2) return 0
        val k = list.indexOfFirst { it.id == episodeId }
        val done = shared.all(subId, seriesId)
        var count = 0
        targets@ for (t in (k until minOf(list.size, k + 4))) {
            val ep = list[t]
            if (done.containsKey("${ep.season}_${ep.episodeNum}") || !timeLeft() || count >= MAX_EPISODES) continue
            // references: the episode before, else after (then the other one if the first shares nothing)
            val refs = listOfNotNull(list.getOrNull(t - 1), list.getOrNull(t + 1), list.getOrNull(t - 2)).distinct()
            var introS = 0L; var introE = 0L; var credits = 0L; var dur = 0L
            for (ref in refs) {
                if (!freeConnection(subId)) return count
                // this episode couldn't be read now (network / server): try again on a later run, record nothing
                val a = window(subId, ep, true) ?: continue@targets
                val b = window(subId, ref, true) ?: continue
                if (introE == 0L) AudioPrint.common(a, b, MIN_INTRO_S, MAX_INTRO_S)?.let { m ->
                    introS = if (m.aStart < 8) 0 else (m.aStart * 1000).toLong()
                    introE = (m.aEnd * 1000).toLong()
                }
                if (credits == 0L) {
                    val ae = window(subId, ep, false); val be = window(subId, ref, false)
                    if (ae != null) dur = ((ae.startSec + END_S) * 1000).toLong()
                    if (ae != null && be != null) AudioPrint.common(ae, be, MIN_CREDITS_S, END_S)?.let { m -> credits = (m.aStart * 1000).toLong() }
                }
                if (introE > 0 && credits > 0) break
            }
            shared.put(subId, seriesId, ep.season, ep.episodeNum, SharedSegments.Found(introS, introE, credits, dur, introE == 0L && credits == 0L))
            count++
        }
        return count
    }

    /** The subscription allows more connections than are in use now (a viewer always keeps a free one). */
    private suspend fun freeConnection(subId: Long): Boolean {
        val sub = subs.get(subId) ?: return false
        val c = subs.creds(sub)
        val auth = runCatching { fetcher.text(XtreamUrls.api(c.server, c.user, c.pass), sub.userAgent) { XtreamParser.parseAuth(it) } }.getOrNull() ?: return false
        if (auth.maxConnections <= 1) return false
        return auth.activeConnections + 2 <= auth.maxConnections
    }

    /** Fingerprint of the first minutes (start) or the last minutes (end) of an episode, cached on disk. */
    private suspend fun window(subId: Long, ep: XEpisode, start: Boolean): AudioPrint.Window? {
        val f = File(dir, "${subId}_${ep.id}_${if (start) "s" else "e"}.bin")
        read(f)?.let { return it }
        val stream = content.resolve(
            PlaybackRequest(subscriptionId = subId, type = ContentType.SERIES, remoteId = "", title = "", episodeId = ep.id, episodeExt = ep.ext),
        )
        val headers = if (stream.userAgent.isNotBlank()) mapOf("User-Agent" to stream.userAgent) else emptyMap()
        val w = AudioPrint.of(stream.url, headers, if (start) 0.0 else -END_S, if (start) START_S else END_S) { !isStopped && timeLeft() } ?: return null
        write(f, w)
        return w
    }

    private fun read(f: File): AudioPrint.Window? = runCatching {
        DataInputStream(f.inputStream().buffered()).use { s ->
            val start = s.readDouble(); val n = s.readInt()
            AudioPrint.Window(start, IntArray(n) { s.readInt() })
        }
    }.getOrNull()

    private fun write(f: File, w: AudioPrint.Window) {
        runCatching { DataOutputStream(f.outputStream().buffered()).use { s -> s.writeDouble(w.startSec); s.writeInt(w.prints.size); w.prints.forEach(s::writeInt) } }
    }

    private fun cleanup() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(400).forEach { it.delete() }
    }

    companion object {
        private const val PERIODIC = "nova_segments"
        private const val ONCE = "nova_segments_now"
        private const val START_S = 8 * 60.0
        private const val END_S = 6 * 60.0
        private const val MIN_INTRO_S = 12.0
        private const val MAX_INTRO_S = 4 * 60.0
        private const val MIN_CREDITS_S = 15.0
        private const val MAX_EPISODES = 4
        private const val BUDGET_MS = 8 * 60_000L   // background work is stopped at 10 minutes

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SegmentWorker>(30, TimeUnit.MINUTES).setConstraints(constraints)
                .setInitialDelay(5, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** After watching: look at this series soon (still only on Wi-Fi with a free connection). */
        fun soon(context: Context) {
            val req = OneTimeWorkRequestBuilder<SegmentWorker>().setConstraints(constraints).setInitialDelay(1, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
