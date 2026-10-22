package com.theoriacodex.app.viewer

import android.app.Activity
import androidx.media3.common.Player
import java.io.Closeable

/** Only the mounted Viewer video can arm automatic PiP; feed players never bind here. */
internal class ViewerAutoPictureInPicture(private val activity: Activity) {
    private var currentPlayer: Player? = null
    private var currentBinding: Any? = null

    // The Activity can pause before Android reports its automatic PiP transition.
    val keepPlayingOnPause: Boolean
        get() = eligiblePlayer() != null

    fun bind(player: Player): Closeable {
        val binding = Any()
        currentBinding = binding
        currentPlayer = player
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
                updateParams()
            }
        }
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
        val player = eligiblePlayer()
        runCatching {
            activity.setPictureInPictureParams(activity.viewerPictureInPictureParams(
                player?.videoSize?.width, player?.videoSize?.height, autoEnter = player != null,
            ))
        }
    }
}
