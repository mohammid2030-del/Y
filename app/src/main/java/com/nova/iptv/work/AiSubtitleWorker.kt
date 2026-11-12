package com.nova.iptv.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nova.iptv.data.aisubs.AiSubtitles
import com.nova.iptv.data.aisubs.AudioChunker
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.network.HttpFetcher
import com.nova.iptv.data.network.XtreamParser
import com.nova.iptv.data.network.XtreamUrls
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.data.repo.SubscriptionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Makes Arabic subtitles with AI for the series someone asked for (on any device): episode after episode, from the
 * one asked for. Each episode: its sound → small speech files → timed Turkish lines → Arabic lines → shared in Firebase.
 * Runs as a visible background task (a notification shows the progress), on Wi-Fi, and only while the subscription
 * has a connection free (it never takes the one someone is watching on).
 */
@HiltWorker
class AiSubtitleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val subs: SubscriptionRepository,
    private val content: ContentRepository,
    private val ai: AiSubtitles,
    private val fetcher: HttpFetcher,
) : CoroutineWorker(context, params) {

    private val dir = File(context.cacheDir, "aisubs")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!ai.available) return@withContext Result.success()
        var any = false
        for (sub in subs.getAll()) {
            if (sub.type != SourceType.XTREAM) continue
            runCatching { ai.heartbeat(sub.id) }
            for ((seriesId, firstEp) in ai.requests(sub.id)) {
                if (isStopped) return@withContext Result.retry()
                any = true
                val finished = runCatching { series(sub.id, seriesId, firstEp) }.getOrElse { e ->
                    runCatching { ai.clearWorking(sub.id, seriesId) }
                    runCatching { ai.note(sub.id, seriesId, "error", m = e.message ?: e.javaClass.simpleName) }
                    false
                }
                if (finished) { ai.cancel(sub.id, seriesId); ai.note(sub.id, seriesId, "done") }   // every episode from there on is done
            }
        }
        dir.deleteRecursively()
        if (any && isStopped) Result.retry() else Result.success()
    }

    /** True when every episode from the asked one to the end has subtitles. */
    private suspend fun series(subId: Long, seriesId: String, firstEp: String): Boolean {
        val sub = subs.get(subId) ?: return false
        val info = content.seriesInfo(sub, seriesId)
        val all = info.seasons.toSortedMap().values.flatten()
        val from = all.indexOfFirst { it.id == firstEp }.coerceAtLeast(0)
        for (ep in all.drop(from)) {
            if (isStopped) return false
            if (!ai.requested(subId, seriesId)) { ai.note(subId, seriesId, "stopped"); return false }   // stopped from a series page
            if (ai.state(subId, seriesId, ep.id).first == "done") continue
            val label = "S${ep.season} E${ep.episodeNum}"
            if (!freeConnection(subId)) { ai.note(subId, seriesId, "busy", label); return false }   // someone is watching: try again later
            notify(label, 0)
            ai.mark(subId, seriesId, ep.id, "working", 0)
            ai.note(subId, seriesId, "working", label, 0)
            val stream = content.resolve(PlaybackRequest(subscriptionId = subId, type = ContentType.SERIES, remoteId = seriesId, title = "",
                episodeId = ep.id, episodeExt = ep.ext))
            val headers = if (stream.userAgent.isNotBlank()) mapOf("User-Agent" to stream.userAgent) else emptyMap()
            val work = File(dir, ep.id).apply { deleteRecursively(); mkdirs() }
            var lastMark = 0L
            var cancelled = false
            // 1. the sound (0–60 %)
            val chunks = AudioChunker.split(stream.url, headers, work, keepGoing = { !isStopped && !cancelled }) { p ->
                val pct = (p * 60).toInt()
                if (System.currentTimeMillis() - lastMark > 20_000) {
                    lastMark = System.currentTimeMillis()
                    kotlinx.coroutines.runBlocking {
                        if (!ai.requested(subId, seriesId)) { cancelled = true; return@runBlocking }
                        notify(label, pct)
                        ai.mark(subId, seriesId, ep.id, "working", pct)
                        ai.note(subId, seriesId, "working", label, pct)
                    }
                }
            }
            if (cancelled || !ai.requested(subId, seriesId)) {
                ai.clear(subId, seriesId, ep.id)
                ai.note(subId, seriesId, "stopped")
                work.deleteRecursively()
                return false
            }
            if (chunks.isNullOrEmpty()) {
                ai.mark(subId, seriesId, ep.id, if (isStopped) "queued" else "error")
                if (!isStopped) ai.note(subId, seriesId, "error", label, m = "audio")
                work.deleteRecursively()
                if (isStopped) return false else continue
            }
            // 2. speech → timed lines (60–85 %)
            val lines = ArrayList<AiSubtitles.Cue>()
            var failed = false
            chunks.forEachIndexed { i, c ->
                if (failed || isStopped) return@forEachIndexed
                val got = ai.transcribe(c)
                if (got == null) failed = true else lines += got
                val pct = 60 + 25 * (i + 1) / chunks.size
                ai.mark(subId, seriesId, ep.id, "working", pct)
                ai.note(subId, seriesId, "working", label, pct)
            }
            work.deleteRecursively()
            if (failed || lines.isEmpty()) { ai.mark(subId, seriesId, ep.id, "error"); ai.note(subId, seriesId, "error", label, m = "transcribe ${ai.lastError}"); continue }
            // 3. → Arabic (85–100 %)
            notify(label, 90)
            ai.note(subId, seriesId, "working", label, 90)
            val arabic = ai.translate(lines)
            if (arabic == null) { ai.mark(subId, seriesId, ep.id, "error"); ai.note(subId, seriesId, "error", label, m = "translate ${ai.lastError}"); continue }
            ai.mark(subId, seriesId, ep.id, "done", 100, AiSubtitles.toVtt(arabic))
        }
        return true
    }

    /** The subscription allows one more connection than are in use now. */
    private suspend fun freeConnection(subId: Long): Boolean {
        val sub = subs.get(subId) ?: return false
        val c = subs.creds(sub)
        val auth = runCatching { fetcher.text(XtreamUrls.api(c.server, c.user, c.pass), sub.userAgent) { XtreamParser.parseAuth(it) } }.getOrNull()
            ?: return true   // can't tell: go on
        if (auth.maxConnections <= 0) return true
        return auth.activeConnections < auth.maxConnections
    }

    private suspend fun notify(episode: String, pct: Int) {
        runCatching { setForeground(foregroundInfo(applicationContext, episode, pct)) }
    }

    companion object {
        private const val PERIODIC = "nova_aisubs"
        private const val NOW = "nova_aisubs_now"
        private const val CHANNEL = "aisubs"

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        /** Looks for requests from other devices every 15 minutes (a quick read when there are none). */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<AiSubtitleWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Right after asking on this device. */
        fun now(context: Context, delaySec: Long = 0) {
            val req = OneTimeWorkRequestBuilder<AiSubtitleWorker>().setConstraints(constraints)
                .setInitialDelay(delaySec, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }

        fun foregroundInfo(context: Context, episode: String, pct: Int): ForegroundInfo {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI subtitles", NotificationManager.IMPORTANCE_LOW))
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("ترجمة بالذكاء الاصطناعي")
                .setContentText("$episode · $pct%")
                .setProgress(100, pct, false)
                .setOngoing(true).setSilent(true)
                .build()
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(4242, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(4242, n)
        }
    }
}
