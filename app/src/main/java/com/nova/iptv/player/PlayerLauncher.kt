package com.nova.iptv.player

import android.content.Context
import android.content.Intent
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.repo.PlaybackRequest

/** In-memory channel list for quick zapping inside the player (avoids Binder size limits). */
object ChannelQueue {
    @Volatile
    var channels: List<ContentEntity> = emptyList()
        private set

    fun set(list: List<ContentEntity>) {
        channels = list
    }
}

object PlayerLauncher {
    const val EXTRA_SUB = "sub"
    const val EXTRA_TYPE = "type"
    const val EXTRA_REMOTE = "remote"
    const val EXTRA_TITLE = "title"
    const val EXTRA_LOGO = "logo"
    const val EXTRA_EPISODE = "episode"
    const val EXTRA_EPISODE_EXT = "episode_ext"
    const val EXTRA_SUBTITLE = "subtitle"
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_CATCHUP_START = "catchup_start"
    const val EXTRA_CATCHUP_DURATION = "catchup_duration"
    const val EXTRA_FROM_START = "from_start"

    fun play(
        context: Context,
        req: PlaybackRequest,
        channelList: List<ContentEntity>? = null,
        fromStart: Boolean = false,
    ) {
        ChannelQueue.set(channelList ?: emptyList())
        context.startActivity(intent(context, req, fromStart))
    }

    fun intent(context: Context, req: PlaybackRequest, fromStart: Boolean = false): Intent =
        Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_SUB, req.subscriptionId)
            putExtra(EXTRA_TYPE, req.type)
            putExtra(EXTRA_REMOTE, req.remoteId)
            putExtra(EXTRA_TITLE, req.title)
            putExtra(EXTRA_LOGO, req.logo)
            putExtra(EXTRA_EPISODE, req.episodeId)
            putExtra(EXTRA_EPISODE_EXT, req.episodeExt)
            putExtra(EXTRA_SUBTITLE, req.subtitle)
            putExtra(EXTRA_CATEGORY, req.categoryId)
            putExtra(EXTRA_CATCHUP_START, req.catchupStartMs)
            putExtra(EXTRA_CATCHUP_DURATION, req.catchupDurationMin)
            putExtra(EXTRA_FROM_START, fromStart)
        }

    fun fromIntent(intent: Intent): PlaybackRequest = PlaybackRequest(
        subscriptionId = intent.getLongExtra(EXTRA_SUB, 0L),
        type = intent.getStringExtra(EXTRA_TYPE).orEmpty(),
        remoteId = intent.getStringExtra(EXTRA_REMOTE).orEmpty(),
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
        logo = intent.getStringExtra(EXTRA_LOGO).orEmpty(),
        episodeId = intent.getStringExtra(EXTRA_EPISODE).orEmpty(),
        episodeExt = intent.getStringExtra(EXTRA_EPISODE_EXT).orEmpty(),
        subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty(),
        categoryId = intent.getStringExtra(EXTRA_CATEGORY).orEmpty(),
        catchupStartMs = intent.getLongExtra(EXTRA_CATCHUP_START, 0L),
        catchupDurationMin = intent.getIntExtra(EXTRA_CATCHUP_DURATION, 0),
    )
}
