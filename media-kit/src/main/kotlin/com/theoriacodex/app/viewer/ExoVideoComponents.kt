@file:androidx.annotation.OptIn(UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.net.Uri
import android.os.Trace
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.net.toUri
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.PlayerView
import com.theoriacodex.mediakit.R
import com.theoriacodex.domain.model.PostId
import java.io.File

/** Width of the frame as displayed, after non-square pixels are stretched to their intended shape. */
val VideoSize.displayWidth: Int get() = (width * pixelWidthHeightRatio).toInt()

/**
 * Default subtitle lift for shared [PlayerView] instances (feed cards and any non-Viewer player). The Viewer applies
 * its own subtitle style and controls-aware padding when binding its player view.
 */
private const val SUBTITLE_BOTTOM_PADDING_FRACTION = 0.15f

/** A surface-only player view for a feed card or Viewer page; it draws the video and any subtitle cues. */
fun createTexturePlayerView(context: Context): PlayerView {
    val view = LayoutInflater.from(context)
        .inflate(R.layout.player_view_texture, FrameLayout(context), false) as PlayerView
    view.subtitleView?.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
    return view
}

/**
 * Trace section names. The feed-preview names cover every main-thread call the app makes into a preview player;
 * scripts/trace_player_frames.py groups them into construction, prepare, surface, and commands and events, so a
 * new preview section belongs in that script's category table too.
 */
object MediaTraceSections {
    const val PREVIEW_PREPARE = "TheoriaPreviewPrepare"
    const val PREVIEW_FIRST_FRAME = "TheoriaPreviewFirstFrame"
    const val PREVIEW_PLAYER_CREATE = "TheoriaPreviewPlayerCreate"
    const val PREVIEW_PLAYER_PREPARE = "TheoriaPreviewPlayerPrepare"
    const val PREVIEW_PLAYER_REBIND = "TheoriaPreviewPlayerRebind"
    const val PREVIEW_PLAYER_COOL = "TheoriaPreviewPlayerCool"
    const val PREVIEW_PLAYER_RELEASE = "TheoriaPreviewPlayerRelease"
    const val PREVIEW_VIEW_INFLATE = "TheoriaPreviewViewInflate"
    const val PREVIEW_HANDOFF_SEEK = "TheoriaPreviewHandoffSeek"
    const val PREVIEW_SURFACE_ATTACH = "TheoriaPreviewSurfaceAttach"
    const val PREVIEW_SURFACE_DETACH = "TheoriaPreviewSurfaceDetach"
    const val PREVIEW_PLAY_PAUSE = "TheoriaPreviewPlayPause"
    const val PREVIEW_PLAYER_RECYCLE = "TheoriaPreviewPlayerRecycle"
    const val PREVIEW_PLAYER_RESUME = "TheoriaPreviewPlayerResume"
    const val PREVIEW_STOP_FOR_VIEWER = "TheoriaPreviewStopForViewer"
    const val PREVIEW_LISTENER_BIND = "TheoriaPreviewListenerBind"
    const val PREVIEW_PLAYER_EVENT = "TheoriaPreviewPlayerEvent"
    const val PREVIEW_DEMAND_TO_FIRST_FRAME = "TheoriaPreviewDemandToFirstFrame"
    const val PREVIEW_ATTACH_TO_FIRST_FRAME = "TheoriaPreviewAttachToFirstFrame"
    const val PREVIEW_ABANDONED = "TheoriaPreviewAbandoned"
    const val VIEWER_PREPARE = "TheoriaViewerPrepare"
    const val VIEWER_FIRST_FRAME = "TheoriaViewerFirstFrame"
    const val VIEWER_PLAYER_CREATE = "TheoriaViewerPlayerCreate"
    const val VIEWER_PLAYER_RETURN = "TheoriaViewerPlayerReturn"
    const val VIEWER_PLAYER_RELEASE = "TheoriaViewerPlayerRelease"
    const val MEDIA_LOAD = "TheoriaMediaLoad"
    const val VIEWER_GIF_RENDER = "TheoriaViewerGifRender"
}

object MediaTraceCounters {
    const val PREVIEW_ACTIVE_PLAYERS = "TheoriaPreviewActivePlayers"
    const val PREVIEW_TOTAL_PLAYERS = "TheoriaPreviewTotalPlayers"
    const val VIEWER_ACTIVE_PLAYERS = "TheoriaViewerActivePlayers"
    const val VIEWER_TOTAL_PLAYERS = "TheoriaViewerTotalPlayers"
}

inline fun <T> traceMediaSection(name: String, block: () -> T): T {
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

class FirstFrameTraceGate(
    private val record: (String) -> Unit = ::recordMediaTraceEvent,
) {
    private var recorded = false

    fun recordOnce(traceName: String) {
        if (recorded) return
        recorded = true
        record(traceName)
    }
}

data class PlaybackDiagnosticsSemantics(
    val contentDescription: String,
    val stateDescription: String,
)

fun playbackDiagnosticsSemantics(
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

fun PostId.mediaTestTagPart(): String {
    val sourcePart = source.id.lowercase()
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
