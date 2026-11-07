package com.nova.iptv.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nova.iptv.R
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.EpgProgramEntity
import com.nova.iptv.data.db.HistoryEntity
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.PlaybackRequest
import com.nova.iptv.data.repo.ResolvedStream
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.toNovaException
import com.nova.iptv.di.DEFAULT_USER_AGENT
import com.nova.iptv.ui.isTvDevice
import com.nova.iptv.ui.theme.NovaTheme
import com.nova.iptv.ui.theme.NovaUiFlags
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import javax.inject.Inject

/** Observable UI state for the Compose overlay. */
class PlayerUiState {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var logo by mutableStateOf("")
    var isLive by mutableStateOf(false)
    var controlsVisible by mutableStateOf(true)
    var reconnecting by mutableStateOf(false)
    var reconnectAttempt by mutableStateOf(0)
    var error by mutableStateOf<PlayerError?>(null)
    var sourceError by mutableStateOf<com.nova.iptv.data.NovaException?>(null)
    var inPip by mutableStateOf(false)
    var bannerVisible by mutableStateOf(false)
    var infoVisible by mutableStateOf(false)
    var qualityDialog by mutableStateOf(false)
    var resizeMode by mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    var nowProgram by mutableStateOf<EpgProgramEntity?>(null)
    var nextProgram by mutableStateOf<EpgProgramEntity?>(null)
    var epgOffsetMs by mutableStateOf(0L)
    var channelNumber by mutableStateOf(0)
    var videoInfo by mutableStateOf("")
    var qualities by mutableStateOf<List<QualityOption>>(emptyList())
    var canZap by mutableStateOf(false)
    var pipSupported by mutableStateOf(false)
    var skipIntroVisible by mutableStateOf(false)
    var upNext by mutableStateOf<UpNextUi?>(null)
    var upNextSeconds by mutableStateOf(0)
    var autoNext by mutableStateOf(true)
}

data class UpNextUi(val title: String, val subtitle: String, val image: String)

data class QualityOption(val label: String, val groupIndex: Int, val trackIndex: Int, val selected: Boolean)

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    @Inject lateinit var content: ContentRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var okHttp: OkHttpClient

    private val ui = PlayerUiState()
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var request: PlaybackRequest
    private var resolved: ResolvedStream? = null
    private var fromStart = false
    private var channels: List<ContentEntity> = emptyList()
    private var channelIndex = -1
    private var retryCount = 0
    private var retryJob: Job? = null
    private var bannerJob: Job? = null
    private var playJob: Job? = null
    private var resumeOnStart = false
    private var isTv = false

    // Series: skip intro + up next
    private var nextEpisode: com.nova.iptv.data.network.XEpisode? = null
    private var introSeconds = 85
    private var introSkipped = false
    private var upNextDismissed = false
    private var swallowBackUp = false

    private val extractors by lazy {
        DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    retryCount = 0
                    ui.reconnecting = false
                    ui.error = null
                    updateVideoInfo()
                }
                Player.STATE_ENDED -> {
                    if (ui.isLive) {
                        scheduleRetry()
                    } else {
                        saveProgress(finished = true)
                        if (nextEpisode != null && ui.autoNext && !upNextDismissed) playNext()
                    }
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (PlayerErrors.isRecoverable(error) && retryCount < MAX_RETRIES) {
                scheduleRetry()
            } else {
                ui.reconnecting = false
                ui.error = PlayerErrors.classify(error)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateQualities(tracks)
            updateVideoInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = isTvDevice(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ui.pipSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        request = PlayerLauncher.fromIntent(intent)
        fromStart = intent.getBooleanExtra(PlayerLauncher.EXTRA_FROM_START, false)

        playerView = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowSubtitleButton(true)
            controllerAutoShow = true
            controllerShowTimeoutMs = 4000
            keepScreenOn = true
            isFocusable = true
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                ui.controlsVisible = visibility == View.VISIBLE
            })
        }

        setContent {
            NovaTheme(themeMode = "dark", flags = NovaUiFlags(isTv = isTv)) {
                PlayerScreen(
                    state = ui,
                    playerView = playerView,
                    actions = PlayerActions(
                        onBack = { finish() },
                        onRetry = { retryNow() },
                        onAspect = { cycleAspect() },
                        onPip = { enterPip() },
                        onInfo = { ui.infoVisible = !ui.infoVisible },
                        onQuality = { ui.qualityDialog = true },
                        onSelectQuality = { selectQuality(it) },
                        onZap = { zap(it) },
                        onSkipIntro = { skipIntro() },
                        onPlayNext = { playNext() },
                        onDismissNext = { dismissUpNext() },
                    ),
                )
            }
        }

        lifecycleScope.launch {
            val s = settings.current()
            ui.epgOffsetMs = s.epgOffsetMinutes * 60_000L
            buildPlayer(s.bufferSeconds, s.lowEndMode)
            startPlayback(request)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = PlayerLauncher.fromIntent(intent)
        fromStart = intent.getBooleanExtra(PlayerLauncher.EXTRA_FROM_START, false)
        if (player != null) startPlayback(request)
    }

    private fun buildPlayer(bufferSec: Int, lowEnd: Boolean) {
        val maxMs = (bufferSec * 1000).coerceIn(10_000, 120_000)
        val minMs = if (lowEnd) 5_000 else (maxMs / 2).coerceAtLeast(8_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs.coerceAtLeast(minMs + 1000), 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val trackSelector = DefaultTrackSelector(this).apply {
            val lang = Locale.getDefault().language
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages(lang, "ar", "en")
                    .setPreferredTextLanguages(lang, "ar", "en")
                    .apply { if (lowEnd) setMaxVideoSize(1280, 720) }
            )
        }
        val p = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(listener)
        player = p
        playerView.player = p
    }

    private fun startPlayback(req: PlaybackRequest) {
        playJob?.cancel()
        retryJob?.cancel()
        playJob = lifecycleScope.launch {
            saveProgress()
            request = req
            ui.error = null
            ui.sourceError = null
            ui.reconnecting = false
            ui.title = req.title
            ui.subtitle = req.subtitle
            ui.logo = req.logo
            ui.isLive = req.type == ContentType.LIVE && req.catchupStartMs == 0L
            retryCount = 0

            val r = try {
                content.resolve(req)
            } catch (e: Exception) {
                ui.sourceError = e.toNovaException()
                ui.error = PlayerError(PlayerErrorKind.SOURCE)
                return@launch
            }
            resolved = r

            var startPos = C.TIME_UNSET
            val key = historyKey(req)
            if (!ui.isLive && req.catchupStartMs == 0L && !fromStart) {
                content.history(key)?.let { h ->
                    val nearEnd = h.durationMs > 0 && h.positionMs > h.durationMs * 0.95
                    if (h.positionMs > 30_000 && !nearEnd) startPos = h.positionMs
                }
            }
            fromStart = false
            prepare(r, startPos)

            // History: live channels are recorded as "recently watched", VOD gets resume points.
            content.saveHistory(
                HistoryEntity(
                    historyKey = key,
                    subscriptionId = req.subscriptionId,
                    type = req.type,
                    remoteId = req.remoteId,
                    episodeId = req.episodeId,
                    title = req.title,
                    subtitle = req.subtitle,
                    logo = req.logo,
                    containerExt = req.episodeExt,
                    positionMs = if (startPos == C.TIME_UNSET) 0 else startPos,
                    durationMs = content.history(key)?.durationMs ?: 0,
                )
            )

            if (ui.isLive) {
                settings.setLastChannel("${req.subscriptionId}|${req.remoteId}")
                setupChannels(req)
                showBanner()
                launch { epgLoop(req) }
            } else {
                ui.canZap = false
                launch { progressLoop() }
                if (req.type == ContentType.SERIES && req.catchupStartMs == 0L) launch { seriesLoop(req) }
                else { ui.skipIntroVisible = false; ui.upNext = null }
            }
        }
    }

    private fun prepare(r: ResolvedStream, startPos: Long) {
        val p = player ?: return
        val dataSource = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(r.userAgent.ifBlank { DEFAULT_USER_AGENT })
        val factory = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        val item = MediaItem.Builder()
            .setUri(r.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ui.title).build())
            .apply { mimeFor(r.url)?.let { setMimeType(it) } }
            .build()
        val source = factory.createMediaSource(item)
        if (startPos != C.TIME_UNSET && startPos > 0) p.setMediaSource(source, startPos) else p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    private fun mimeFor(url: String): String? {
        val u = url.substringBefore('?').lowercase()
        return when {
            u.endsWith(".m3u8") || u.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            u.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    private fun historyKey(req: PlaybackRequest) =
        HistoryEntity.key(req.subscriptionId, req.type, req.remoteId, req.episodeId)

    private fun scheduleRetry() {
        val r = resolved ?: return
        retryJob?.cancel()
        retryCount++
        ui.reconnecting = true
        ui.reconnectAttempt = retryCount
        retryJob = lifecycleScope.launch {
            delay((1000L shl retryCount.coerceAtMost(4)).coerceAtMost(15_000L))
            val pos = if (ui.isLive) C.TIME_UNSET else (player?.currentPosition ?: C.TIME_UNSET)
            prepare(r, pos)
        }
    }

    private fun retryNow() {
        retryCount = 0
        ui.error = null
        val r = resolved
        if (r == null || ui.sourceError != null) startPlayback(request)
        else prepare(r, if (ui.isLive) C.TIME_UNSET else (player?.currentPosition ?: C.TIME_UNSET))
    }

    private suspend fun progressLoop() {
        while (lifecycleScope.isActive) {
            delay(10_000)
            saveProgress()
        }
    }

    private fun saveProgress(finished: Boolean = false) {
        val p = player ?: return
        if (!::request.isInitialized || request.type == ContentType.LIVE) return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (finished) duration else p.currentPosition
        val req = request
        lifecycleScope.launch {
            content.saveHistory(
                HistoryEntity(
                    historyKey = historyKey(req),
                    subscriptionId = req.subscriptionId,
                    type = req.type,
                    remoteId = req.remoteId,
                    episodeId = req.episodeId,
                    title = req.title,
                    subtitle = req.subtitle,
                    logo = req.logo,
                    containerExt = req.episodeExt,
                    positionMs = position,
                    durationMs = duration,
                )
            )
        }
    }

    // ---- Series: skip intro & up next ----
    private suspend fun seriesLoop(req: PlaybackRequest) {
        val s = settings.current()
        introSeconds = s.introSeconds
        ui.autoNext = s.autoNext
        introSkipped = false
        upNextDismissed = false
        ui.upNext = null
        ui.skipIntroVisible = false
        nextEpisode = content.nextEpisode(req.subscriptionId, req.remoteId, req.episodeId)
        while (true) {
            val p = player
            if (p != null && p.playbackState == Player.STATE_READY) {
                val pos = p.currentPosition
                val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                val introEnd = introSeconds * 1000L
                ui.skipIntroVisible = introSeconds > 0 && !introSkipped && ui.error == null &&
                    pos in 3_000L until (introEnd - 2_000L) && (dur == 0L || pos < dur / 3)
                val next = nextEpisode
                val remaining = dur - pos
                if (next != null && dur > 120_000L && remaining in 0L..UP_NEXT_WINDOW_MS && !upNextDismissed) {
                    ui.upNextSeconds = (remaining / 1000L).toInt()
                    if (ui.upNext == null) {
                        ui.upNext = UpNextUi(
                            title = next.title.ifBlank { getString(R.string.episode_n, next.episodeNum) },
                            subtitle = "S${next.season} E${next.episodeNum}",
                            image = next.image.ifBlank { req.logo },
                        )
                    }
                } else if (ui.upNext != null && (remaining > UP_NEXT_WINDOW_MS || upNextDismissed)) {
                    ui.upNext = null
                }
            }
            delay(500)
        }
    }

    private fun skipIntro() {
        val p = player ?: return
        p.seekTo(introSeconds * 1000L)
        introSkipped = true
        ui.skipIntroVisible = false
    }

    private fun dismissUpNext() {
        upNextDismissed = true
        ui.upNext = null
    }

    private fun playNext() {
        val next = nextEpisode ?: return
        val cur = request
        ui.upNext = null
        saveProgress(finished = true)
        startPlayback(
            PlaybackRequest(
                subscriptionId = cur.subscriptionId,
                type = ContentType.SERIES,
                remoteId = cur.remoteId,
                title = cur.title,
                logo = cur.logo,
                episodeId = next.id,
                episodeExt = next.ext,
                subtitle = "S${next.season} E${next.episodeNum}" + if (next.title.isNotBlank()) " — ${next.title}" else "",
            )
        )
    }

    // ---- Live: channel list, zapping, EPG ----
    private suspend fun setupChannels(req: PlaybackRequest) {
        val queued = ChannelQueue.channels
        channels = if (queued.any { it.remoteId == req.remoteId && it.subscriptionId == req.subscriptionId }) queued
        else content.channelsFor(req.subscriptionId, req.categoryId)
        channelIndex = channels.indexOfFirst { it.remoteId == req.remoteId }
        ui.canZap = channels.size > 1 && channelIndex >= 0
        ui.channelNumber = channels.getOrNull(channelIndex)?.num ?: 0
    }

    private fun zap(delta: Int) {
        if (!ui.isLive || channels.size < 2 || channelIndex < 0) return
        channelIndex = (channelIndex + delta + channels.size) % channels.size
        val c = channels[channelIndex]
        startPlayback(
            PlaybackRequest(
                subscriptionId = c.subscriptionId,
                type = ContentType.LIVE,
                remoteId = c.remoteId,
                title = c.name,
                logo = c.logo,
                categoryId = c.categoryId,
            )
        )
    }

    private suspend fun epgLoop(req: PlaybackRequest) {
        val channel = channels.getOrNull(channelIndex) ?: content.byRemote(req.subscriptionId, ContentType.LIVE, req.remoteId)
        val key = channel?.epgId.orEmpty()
        if (key.isEmpty()) {
            ui.nowProgram = null; ui.nextProgram = null
            return
        }
        while (true) {
            val now = System.currentTimeMillis() - ui.epgOffsetMs
            val list = content.programmes(req.subscriptionId, key, now - 6 * 3_600_000L, now + 12 * 3_600_000L)
            val idx = list.indexOfFirst { it.start <= now && it.stop > now }
            ui.nowProgram = list.getOrNull(idx)
            ui.nextProgram = if (idx >= 0) list.getOrNull(idx + 1) else list.firstOrNull { it.start > now }
            delay(60_000)
        }
    }

    private fun showBanner() {
        bannerJob?.cancel()
        ui.bannerVisible = true
        bannerJob = lifecycleScope.launch {
            delay(4_000)
            ui.bannerVisible = false
        }
    }

    // ---- Tracks / quality ----
    private fun updateQualities(tracks: Tracks) {
        val out = ArrayList<QualityOption>()
        tracks.groups.forEachIndexed { gi, group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val f = group.getTrackFormat(ti)
                val label = buildString {
                    if (f.height > 0) append("${f.height}p") else append(getString(R.string.quality_track, ti + 1))
                    if (f.bitrate > 0) append("  •  ${"%.1f".format(Locale.US, f.bitrate / 1_000_000f)} Mbps")
                }
                out += QualityOption(label, gi, ti, group.isTrackSelected(ti))
            }
        }
        ui.qualities = out.sortedByDescending { it.label.substringBefore('p').toIntOrNull() ?: 0 }
    }

    private fun selectQuality(option: QualityOption?) {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
        if (option == null) {
            params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = params.build()
        ui.qualityDialog = false
    }

    private fun updateVideoInfo() {
        val p = player ?: return
        val f = p.videoFormat
        ui.videoInfo = if (f == null) "" else buildString {
            if (f.width > 0) append("${f.width}×${f.height}")
            if (f.frameRate > 0) append("  ${"%.0f".format(Locale.US, f.frameRate)}fps")
            if (f.bitrate > 0) append("  ${"%.1f".format(Locale.US, f.bitrate / 1_000_000f)} Mbps")
            f.sampleMimeType?.let { append("  ${it.substringAfter('/')}") }
        }
    }

    private fun cycleAspect() {
        val next = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = next
        ui.resizeMode = next
    }

    // ---- Picture in picture ----
    private fun enterPip() {
        if (!ui.pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val f = player?.videoFormat
        var ratio = if (f != null && f.width > 0 && f.height > 0) f.width.toFloat() / f.height else 16f / 9f
        ratio = ratio.coerceIn(1f / 2.39f, 2.39f)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isTv && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.inPip = isInPictureInPictureMode
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) playerView.hideController()
    }

    // ---- Remote control keys ----
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && ui.upNext != null) {
                dismissUpNext(); swallowBackUp = true; return true
            }
            if (event.action == KeyEvent.ACTION_UP && swallowBackUp) {
                swallowBackUp = false; return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && ok && !playerView.isControllerFullyVisible) {
            if (ui.upNext != null) { playNext(); return true }
            if (ui.skipIntroVisible) { skipIntro(); return true }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> if (ui.isLive) { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> if (ui.isLive) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (ui.isLive && !playerView.isControllerFullyVisible) { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (ui.isLive && !playerView.isControllerFullyVisible) { zap(-1); return true }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { ui.infoVisible = !ui.infoVisible; showBanner(); return true }
                KeyEvent.KEYCODE_MENU -> { ui.qualityDialog = true; return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!playerView.isControllerFullyVisible) {
                    playerView.showController(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Lifecycle ----
    override fun onStart() {
        super.onStart()
        if (resumeOnStart) {
            player?.play()
            resumeOnStart = false
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        val p = player
        if (p != null) {
            resumeOnStart = p.playWhenReady && ui.isLive
            p.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        retryJob?.cancel()
        playJob?.cancel()
        playerView.player = null
        player?.removeListener(listener)
        player?.release()
        player = null
    }

    companion object {
        private const val MAX_RETRIES = 8
        private const val UP_NEXT_WINDOW_MS = 25_000L
    }
}
