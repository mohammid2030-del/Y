package com.nova.iptv.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.nova.iptv.R

/** Human-readable playback diagnostics (no URLs / credentials are ever shown). */
enum class PlayerErrorKind(val message: Int) {
    NETWORK(R.string.perr_network),
    FORBIDDEN(R.string.perr_forbidden),
    NOT_FOUND(R.string.perr_not_found),
    SERVER(R.string.perr_server),
    UNSUPPORTED(R.string.perr_unsupported),
    DRM(R.string.perr_drm),
    SOURCE(R.string.perr_source),
    UNKNOWN(R.string.perr_unknown),
}

data class PlayerError(val kind: PlayerErrorKind, val code: String = "")

object PlayerErrors {

    @OptIn(UnstableApi::class)
    fun httpCode(error: PlaybackException): Int {
        var t: Throwable? = error.cause
        while (t != null) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            t = t.cause
        }
        return 0
    }

    fun isRecoverable(error: PlaybackException): Boolean {
        val code = httpCode(error)
        if (code == 401 || code == 403 || code == 404) return false
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_UNSPECIFIED -> true
            else -> false
        }
    }

    fun classify(error: PlaybackException): PlayerError {
        val http = httpCode(error)
        val kind = when {
            http == 401 || http == 403 -> PlayerErrorKind.FORBIDDEN
            http == 404 || http == 410 -> PlayerErrorKind.NOT_FOUND
            http >= 500 -> PlayerErrorKind.SERVER
            else -> when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_TIMEOUT -> PlayerErrorKind.NETWORK
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> PlayerErrorKind.UNSUPPORTED
                in 6000..6999 -> PlayerErrorKind.DRM
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlayerErrorKind.SOURCE
                else -> PlayerErrorKind.UNKNOWN
            }
        }
        return PlayerError(kind, if (http > 0) "HTTP $http" else error.errorCodeName)
    }
}
