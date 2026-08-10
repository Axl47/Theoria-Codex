@file:androidx.annotation.OptIn(UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.net.Uri
import android.os.Trace
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.PlayerView
import com.theoriacodex.app.R
import com.theoriacodex.domain.model.PostId
import java.io.File

internal fun createTexturePlayerView(context: Context): PlayerView {
    return LayoutInflater.from(context)
        .inflate(R.layout.player_view_texture, FrameLayout(context), false) as PlayerView
}

internal fun createLoopingExoPlayer(
    context: Context,
    location: String,
    headers: Map<String, String>,
    muted: Boolean,
    profile: VideoPlaybackProfile,
): ExoPlayer {
    val appContext = context.applicationContext
    val infrastructure = appContext.videoPlaybackInfrastructure()
    val request = infrastructure.bind(location, headers)
    return ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(request.mediaSourceFactory)
        .setLoadControl(infrastructure.loadControl(profile))
        .build()
        .apply {
            addAnalyticsListener(MediaLoadTraceListener)
            repeatMode = Player.REPEAT_MODE_ONE
            setSeekParameters(SeekParameters.EXACT)
            volume = if (muted) 0f else 1f
            setMediaItem(request.mediaItem)
            prepare()
        }
}

internal object MediaTraceSections {
    const val PREVIEW_PREPARE = "TheoriaPreviewPrepare"
    const val PREVIEW_FIRST_FRAME = "TheoriaPreviewFirstFrame"
    const val PREVIEW_PLAYER_CREATE = "TheoriaPreviewPlayerCreate"
    const val PREVIEW_PLAYER_RELEASE = "TheoriaPreviewPlayerRelease"
    const val VIEWER_PREPARE = "TheoriaViewerPrepare"
    const val VIEWER_FIRST_FRAME = "TheoriaViewerFirstFrame"
    const val MEDIA_LOAD = "TheoriaMediaLoad"
}

internal inline fun <T> traceMediaSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

internal fun recordMediaTraceEvent(name: String) {
    Trace.beginSection(name)
    Trace.endSection()
}

internal class FirstFrameTraceGate(
    private val record: (String) -> Unit = ::recordMediaTraceEvent,
) {
    private var recorded = false

    fun recordOnce(traceName: String) {
        if (recorded) return
        recorded = true
        record(traceName)
    }
}

internal data class PlaybackDiagnosticsSemantics(
    val contentDescription: String,
    val stateDescription: String,
)

internal fun playbackDiagnosticsSemantics(
    enabled: Boolean,
    isPlaying: Boolean,
    surface: String,
): PlaybackDiagnosticsSemantics? {
    if (!enabled) return null
    return PlaybackDiagnosticsSemantics(
        contentDescription = if (isPlaying) {
            "Playing $surface benchmark video"
        } else {
            "Stopped $surface benchmark video"
        },
        stateDescription = if (isPlaying) "Playing" else "Not playing",
    )
}

internal fun PostId.mediaTestTagPart(): String {
    val sourcePart = source.name.lowercase()
    val postPart = sourcePostId.lowercase().map { character ->
        if (character.isLetterOrDigit() || character == '_') character else '_'
    }.joinToString(separator = "")
    return "${sourcePart}_$postPart"
}

internal object MediaLoadTraceListener : AnalyticsListener {
    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int,
    ) {
        recordMediaTraceEvent(MediaTraceSections.MEDIA_LOAD)
    }
}

internal fun videoLocationToUri(location: String): Uri {
    return when {
        location.startsWith("http://", ignoreCase = true) ||
            location.startsWith("https://", ignoreCase = true) ||
            location.startsWith("content://", ignoreCase = true) ||
            location.startsWith("android.resource://", ignoreCase = true) -> location.toUri()

        else -> File(location).toUri()
    }
}
