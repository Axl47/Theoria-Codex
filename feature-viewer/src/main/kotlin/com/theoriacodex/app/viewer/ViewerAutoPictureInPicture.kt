package com.theoriacodex.app.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.view.View
import androidx.media3.common.Player
import java.io.Closeable

class ViewerAutoPictureInPicture(private val activity: Activity) {
    private var currentPlayer: Player? = null
    private var currentView: () -> View? = { null }
    private var currentBinding: Any? = null

    val keepPlayingOnPause: Boolean
        get() = eligiblePlayer() != null

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
