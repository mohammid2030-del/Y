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
    var audioTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var textTracks by mutableStateOf<List<TrackOption>>(emptyList())
    var textOff by mutableStateOf(false)
    var tracksDialog by mutableStateOf(false)
    var canZap by mutableStateOf(false)
    var pipSupported by mutableStateOf(false)
    var skipIntroVisible by mutableStateOf(false)
    var aiCue by mutableStateOf("")
    var aiAvailable by mutableStateOf(false)
    var aiOn by mutableStateOf(true)
    var upNext by mutableStateOf<UpNextUi?>(null)
    var upNextSeconds by mutableStateOf(0)
    var upNextTotal by mutableStateOf(25)
    var skipIsRecap by mutableStateOf(false)
    var autoNext by mutableStateOf(true)
    var introAdjustVisible by mutableStateOf(false)
    var introLearned by mutableStateOf(false)
}

data class UpNextUi(val title: String, val subtitle: String, val image: String)

data class QualityOption(val label: String, val groupIndex: Int, val trackIndex: Int, val selected: Boolean)

/** An audio or subtitle track with a readable label ("العربية · 5.1 · E-AC-3"). */
data class TrackOption(
    val label: String,
    val detail: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val selected: Boolean,
    val supported: Boolean,
)

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    @Inject lateinit var content: ContentRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var okHttp: OkHttpClient
    @Inject lateinit var skipRepo: com.nova.iptv.data.repo.SkipRepository
    @Inject lateinit var introStore: com.nova.iptv.data.repo.IntroStore
    @Inject lateinit var sharedSegments: com.nova.iptv.data.segments.SharedSegments
    @Inject lateinit var subtitlePrefs: com.nova.iptv.data.subs.SubtitlePrefs
    @Inject lateinit var aiSubs: com.nova.iptv.data.aisubs.AiSubtitles
    private var aiCues: List<com.nova.iptv.data.aisubs.AiSubtitles.Cue> = emptyList()

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
    private val skippedSegments = HashSet<String>()
    private var segments: com.nova.iptv.data.network.EpisodeSegments? = null
    private var learnedIntro: com.nova.iptv.data.network.Segment? = null
    private var episodeCtx: com.nova.iptv.data.repo.EpisodeContext? = null
    private var currentSkip: Pair<String, com.nova.iptv.data.network.Segment>? = null
    private var upNextShownAt = 0L
    private var lastIntro: com.nova.iptv.data.network.Segment? = null
    private var introStartMs = 0L
    private var introEndMs = 0L
    private var programmaticSeek = false
    private var seekCandidate: Pair<Long, Long>? = null
    private var chainFrom = 0L
    private var chainTo = -1L
    private var chainAt = 0L
    private var adjustJob: Job? = null
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

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (programmaticSeek) { programmaticSeek = false; return }
            if (!::request.isInitialized || request.type != ContentType.SERIES || ui.introAdjustVisible) return
            // A manual jump of 20 s – 4 min near the start is almost always "skipping the intro":
            // remember it once the user keeps watching from there.
            val to = newPosition.positionMs
            val now = android.os.SystemClock.elapsedRealtime()
            // several quick +10 s taps count as one jump
            val from = if (now - chainAt < 4_000L && kotlin.math.abs(oldPosition.positionMs - chainTo) < 3_000L) chainFrom
            else oldPosition.positionMs
            chainFrom = from; chainTo = to; chainAt = now
            seekCandidate = if (from < 6 * 60_000L && to - from in 20_000L..240_000L) from to to else null
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateQualities(tracks)
            updateTrackLists(tracks)
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
        applySubtitleStyle()

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
                        onTracks = { ui.tracksDialog = true },
                        onSelectAudio = { selectTrack(C.TRACK_TYPE_AUDIO, it) },
                        onSelectText = { selectTrack(C.TRACK_TYPE_TEXT, it) },
                        onZap = { zap(it) },
                        onSkipIntro = { skipIntro() },
                        onPlayNext = { playNext() },
                        onDismissNext = { dismissUpNext() },
                        onAdjustIntro = { adjustIntro(it) },
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
        // Device decoders first; FFmpeg fills in for codecs the device lacks (Dolby / DTS audio in many 4K files).
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
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
                    val nearEnd = h.durationMs > 0 && h.positionMs > h.durationMs * 0.98
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
        val httpSource = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(r.userAgent.ifBlank { DEFAULT_USER_AGENT })
        val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(this, httpSource)
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
        // finished: the end, 98 %, or into the end credits
        val credits = segments?.credits?.startMs?.takeIf { it > duration / 2 }
        val done = finished || p.currentPosition >= duration * 98 / 100 || (credits != null && p.currentPosition >= credits)
        val position = if (done) duration else p.currentPosition
        val req = request
        val next = if (done && req.type == ContentType.SERIES) episodeCtx?.takeIf { it.current.id == req.episodeId }?.next else null
        lifecycleScope.launch {
            if (next != null) {
                // Continue watching moves on: the next episode (next season's first after a season's last), ready from the start
                content.saveHistory(
                    HistoryEntity(
                        historyKey = HistoryEntity.key(req.subscriptionId, req.type, req.remoteId, next.id),
                        subscriptionId = req.subscriptionId, type = req.type, remoteId = req.remoteId, episodeId = next.id,
                        title = req.title,
                        subtitle = "S${next.season} E${next.episodeNum}" + if (next.title.isNotBlank()) " — ${next.title}" else "",
                        logo = req.logo, containerExt = next.ext, positionMs = UP_NEXT_MARK, durationMs = 0,
                        updatedAt = System.currentTimeMillis() + 1,
                    )
                )
            }
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

    // ---- Subtitle look (embedded / stream subtitles) ----
    private fun applySubtitleStyle() {
        val size = subtitlePrefs.size
        playerView.subtitleView?.apply {
            setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.argb(120, 0, 0, 0),
                    android.graphics.Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.BLACK,
                    null,
                )
            )
            val factor = when (size) { 0 -> 0.85f; 2 -> 1.25f; 3 -> 1.5f; else -> 1.05f }
            setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * factor)
        }
    }

    // ---- Series: skip intro/recap & up next ----
    private suspend fun seriesLoop(req: PlaybackRequest) {
        val st = settings.current()
        introSeconds = st.introSeconds
        ui.autoNext = st.autoNext
        skippedSegments.clear()
        upNextDismissed = false
        upNextShownAt = 0L
        ui.upNext = null
        ui.skipIntroVisible = false
        segments = null
        learnedIntro = null
        val ctx = content.episodeContext(req.subscriptionId, req.remoteId, req.episodeId)
        episodeCtx = ctx
        nextEpisode = ctx?.next
        seekCandidate = null
        ui.introAdjustVisible = false
        learnedIntro = introStore.get(req.subscriptionId, req.remoteId)?.let { com.nova.iptv.data.network.Segment(it.startMs, it.endMs) }
        if (ctx != null) {
            // times found by sound fingerprinting (exact for this very file, shared by all devices) first, then TheIntroDB
            val found = runCatching { sharedSegments.get(req.subscriptionId, req.remoteId, ctx.current.season, ctx.current.episodeNum) }.getOrNull()
            segments = sharedSegments.toSegments(found) ?: skipRepo.segments(ctx.tmdbId, ctx.current.season, ctx.current.episodeNum)
        }
        aiCues = emptyList(); ui.aiCue = ""; ui.aiAvailable = false
        val epId = req.episodeId
        if (!epId.isNullOrBlank()) {
            aiCues = runCatching { aiSubs.vtt(req.subscriptionId, req.remoteId, epId) }.getOrNull()
                ?.let { com.nova.iptv.data.aisubs.AiSubtitles.parseVtt(it) }.orEmpty()
            ui.aiAvailable = aiCues.isNotEmpty()
        }
        while (true) {
            val p = player
            if (p != null && p.playbackState == Player.STATE_READY && ui.error == null) {
                val pos = p.currentPosition
                ui.aiCue = if (ui.aiOn && aiCues.isNotEmpty()) aiCueAt(pos) else ""
                // learn from a manual skip once playback continues 12 s past the landing point
                val cand = seekCandidate
                if (cand != null && pos >= cand.second + 12_000L) {
                    seekCandidate = null
                    if (segments?.intro == null) {
                        saveIntro(if (cand.first < 15_000L) 0L else cand.first, cand.second)
                        android.widget.Toast.makeText(this, R.string.intro_saved, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                updateSkip(pos, p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L, req)
            }
            delay(400)
        }
    }

    private fun aiCueAt(pos: Long): String {
        val sec = pos / 1000.0
        var lo = 0; var hi = aiCues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = aiCues[mid]
            when {
                sec < c.start -> hi = mid - 1
                sec >= c.end -> lo = mid + 1
                else -> return c.text
            }
        }
        return ""
    }

    /** Which segment can be skipped right now: real data first, then learned, then the optional fixed fallback. */
    private fun activeSkip(pos: Long, dur: Long): Pair<String, com.nova.iptv.data.network.Segment>? {
        val seg = segments
        seg?.recap?.let { if (pos in it.startMs until it.endMs - 1_500 && "recap" !in skippedSegments) return "recap" to it }
        val intro = seg?.intro
            ?: learnedIntro?.let { com.nova.iptv.data.network.Segment((it.startMs - 15_000).coerceAtLeast(0), it.endMs) }
            ?: if (introSeconds > 0) com.nova.iptv.data.network.Segment(3_000, introSeconds * 1000L) else null
        if (intro != null && "intro" !in skippedSegments && pos in intro.startMs until intro.endMs - 1_500 &&
            (dur == 0L || intro.endMs < dur / 2)
        ) return "intro" to intro
        return null
    }

    private fun updateSkip(pos: Long, dur: Long, req: PlaybackRequest) {
        val skip = activeSkip(pos, dur)
        currentSkip = skip
        ui.skipIntroVisible = skip != null
        ui.skipIsRecap = skip?.first == "recap"

        val next = nextEpisode ?: return
        if (dur < 120_000L || upNextDismissed) return
        val credits = segments?.credits?.startMs?.takeIf { it > dur / 2 && it < dur - 5_000 }
        val showFrom = credits ?: (dur - FALLBACK_UP_NEXT_MS)
        if (pos >= showFrom) {
            if (ui.upNext == null) {
                upNextShownAt = System.currentTimeMillis()
                ui.upNext = UpNextUi(
                    title = next.title.ifBlank { getString(R.string.episode_n, next.episodeNum) },
                    subtitle = "S${next.season} E${next.episodeNum}",
                    image = next.image.ifBlank { req.logo },
                )
            }
            if (credits != null) {
                // credits detected: Netflix-style 10 s countdown, then continue
                ui.upNextTotal = CREDITS_COUNTDOWN_S
                val left = CREDITS_COUNTDOWN_S - ((System.currentTimeMillis() - upNextShownAt) / 1000L).toInt()
                ui.upNextSeconds = left.coerceAtLeast(0)
                if (left <= 0 && ui.autoNext) playNext()
            } else {
                ui.upNextTotal = (FALLBACK_UP_NEXT_MS / 1000L).toInt()
                ui.upNextSeconds = ((dur - pos) / 1000L).toInt().coerceAtLeast(0)
            }
        } else if (ui.upNext != null) {
            ui.upNext = null // user seeked back
        }
    }

    private fun skipIntro() {
        val p = player ?: return
        val (kind, seg) = currentSkip ?: return
        programmaticSeek = true
        p.seekTo(if (seg.endMs > 0) seg.endMs else p.currentPosition + 85_000L)
        skippedSegments += kind
        ui.skipIntroVisible = false
        // exact community data needs no tuning; learned/fallback times can be fine-tuned with ◀ ▶
        if (kind == "intro" && segments?.intro == null) {
            lastIntro = seg
            showIntroAdjust()
        }
    }

    private fun saveIntro(start: Long, end: Long) {
        val seg = com.nova.iptv.data.network.Segment(start, end)
        learnedIntro = seg
        lastIntro = seg
        skippedSegments += "intro"
        ui.introLearned = true
        introStore.save(request.subscriptionId, request.remoteId, start, end)
    }

    /** Fine-tune where the intro ends; the new point is remembered for this series. */
    private fun adjustIntro(deltaMs: Long) {
        val p = player ?: return
        val base = lastIntro ?: learnedIntro ?: com.nova.iptv.data.network.Segment(0L, p.currentPosition)
        val end = (base.endMs + deltaMs).coerceIn(5_000L, 6 * 60_000L)
        programmaticSeek = true
        p.seekTo(end)
        saveIntro(base.startMs.coerceAtMost(end - 1_000L).coerceAtLeast(0L), end)
        showIntroAdjust()
    }

    private fun showIntroAdjust() {
        ui.introAdjustVisible = true
        adjustJob?.cancel()
        adjustJob = lifecycleScope.launch {
            delay(8_000)
            ui.introAdjustVisible = false
        }
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

    private fun appLocale(): Locale {
        val tag = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
        return if (tag.isNotBlank()) Locale.forLanguageTag(tag) else Locale.getDefault()
    }

    private fun languageName(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return ""
        val loc = Locale.forLanguageTag(code)
        return loc.getDisplayLanguage(appLocale()).replaceFirstChar { it.titlecase(appLocale()) }.ifBlank { code }
    }

    private fun codecName(mime: String?): String = when (mime) {
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital+"
        "audio/eac3-joc" -> "Dolby Atmos"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd;profile=p2" -> "DTS"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/flac" -> "FLAC"
        "application/x-subrip" -> "SRT"
        "text/vtt" -> "WebVTT"
        "text/x-ssa" -> "ASS"
        "application/pgs" -> "PGS"
        else -> mime?.substringAfter('/')?.uppercase(Locale.US).orEmpty()
    }

    private fun channelsName(n: Int): String = when {
        n <= 0 -> ""
        n == 1 -> "Mono"
        n == 2 -> "Stereo"
        n == 6 -> "5.1"
        n == 8 -> "7.1"
        else -> "${n}ch"
    }

    private fun updateTrackLists(tracks: Tracks) {
        val audio = ArrayList<TrackOption>()
        val text = ArrayList<TrackOption>()
        tracks.groups.forEachIndexed { gi, group ->
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (ti in 0 until group.length) {
                val f = group.getTrackFormat(ti)
                val lang = languageName(f.language)
                val name = listOf(lang, f.label.orEmpty().takeIf { it.isNotBlank() && !it.equals(lang, true) && !it.equals(f.language, true) }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { getString(if (group.type == C.TRACK_TYPE_AUDIO) R.string.audio_track_n else R.string.subtitle_track_n, (if (group.type == C.TRACK_TYPE_AUDIO) audio.size else text.size) + 1) }
                val detail = if (group.type == C.TRACK_TYPE_AUDIO) {
                    listOf(channelsName(f.channelCount), codecName(f.sampleMimeType)).filter { it.isNotBlank() }.joinToString(" · ")
                } else codecName(f.sampleMimeType ?: f.containerMimeType)
                val opt = TrackOption(name, detail, gi, ti, group.isTrackSelected(ti), group.isTrackSupported(ti))
                if (group.type == C.TRACK_TYPE_AUDIO) audio += opt else text += opt
            }
        }
        ui.audioTracks = audio
        ui.textTracks = text
        ui.textOff = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true || text.none { it.selected }
    }

    private fun selectTrack(type: Int, option: TrackOption?) {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
        if (option == null) {
            params.setTrackTypeDisabled(type, true)
        } else {
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            params.setTrackTypeDisabled(type, false)
            params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = params.build()
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
        if (event.action == KeyEvent.ACTION_DOWN && ui.introAdjustVisible && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { adjustIntro(-5_000L); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { adjustIntro(5_000L); return true }
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
        // find intro / credits of what's being watched next (Wi-Fi, free connection only)
        if (::request.isInitialized && request.type == ContentType.SERIES) com.nova.iptv.work.SegmentWorker.soon(this)
        // the connection being watched on frees up: a waiting AI translation can start (providers take ~a minute to notice)
        if (aiSubs.available) com.nova.iptv.work.AiSubtitleWorker.now(this, delaySec = 60)
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
        private const val FALLBACK_UP_NEXT_MS = 25_000L
        private const val CREDITS_COUNTDOWN_S = 10
        /** Position of a "next episode, from the start" entry in Continue watching (same on every platform). */
        const val UP_NEXT_MARK = 1L
    }
}
