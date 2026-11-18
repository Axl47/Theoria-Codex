package com.theoriacodex.app.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.view.View
import androidx.media3.common.Player
import java.io.Closeable

/** Only the mounted Viewer video can arm automatic PiP; feed players never bind here. */
internal class ViewerAutoPictureInPicture(private val activity: Activity) {
    private var currentPlayer: Player? = null
    private var currentView: () -> View? = { null }
    private var currentBinding: Any? = null

    // The Activity can pause before Android reports its automatic PiP transition.
    val keepPlayingOnPause: Boolean
        get() = eligiblePlayer() != null

    /** Binds the Viewer's active [player], drawn in [view], as the PiP source. */
    fun bind(player: Player, view: () -> View?): Closeable {
        val binding = Any()
        currentBinding = binding
        currentPlayer = player
        currentView = view
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (currentBinding === binding) updateParams()
            }
        }
        player.addListener(listener)
        updateParams()
        return Closeable {
            player.removeListener(listener)
            if (currentBinding === binding) {
                currentBinding = null
                currentPlayer = null
                currentView = { null }
                updateParams()
            }
        }
    }

    /**
     * PiP params shaped like the bound video's displayed frame; [fallbackWidth] by [fallbackHeight]
     * (post metadata, which can disagree with the stream) applies only until the player knows that frame.
     */
    fun params(fallbackWidth: Int? = null, fallbackHeight: Int? = null): PictureInPictureParams {
        val frame = currentPlayer?.videoSize?.takeIf { it.displayWidth > 0 && it.height > 0 }
        return viewerPictureInPictureParams(
            frame?.displayWidth ?: fallbackWidth, frame?.height ?: fallbackHeight,
            currentView(), autoEnter = eligiblePlayer() != null,
        )
    }

    private fun eligiblePlayer(): Player? {
        if (!activity.supportsViewerPictureInPicture()) return null
        return currentPlayer?.takeIf { player ->
            player.playWhenReady && player.playerError == null &&
                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        }
    }

    private fun updateParams() {
        if (!activity.supportsViewerPictureInPicture()) return
        runCatching { activity.setPictureInPictureParams(params()) }
    }
}
