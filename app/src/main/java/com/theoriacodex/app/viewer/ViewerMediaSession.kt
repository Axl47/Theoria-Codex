@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/** External controls belong to the current Viewer, never to concurrently autoplaying feed cards. */
internal fun createViewerMediaSession(
    context: Context,
    player: Player,
    onPlaybackIntentChanged: (Boolean) -> Unit,
): MediaSession {
    val controlledPlayer = object : ForwardingPlayer(player) {
        override fun play() {
            onPlaybackIntentChanged(true)
            super.play()
        }
        override fun pause() {
            onPlaybackIntentChanged(false)
            super.pause()
        }
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            onPlaybackIntentChanged(playWhenReady)
            super.setPlayWhenReady(playWhenReady)
        }
    }
    return MediaSession.Builder(context.applicationContext, controlledPlayer)
        .setId("theoria-viewer")
        .build()
}

/** A stopped Viewer must not keep receiving external commands that could start hidden playback. */
internal fun attachViewerMediaSession(
    lifecycle: androidx.lifecycle.Lifecycle,
    create: () -> java.io.Closeable,
): java.io.Closeable {
    var session: java.io.Closeable? = null
    fun reconcile() {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            if (session == null) session = create()
        } else {
            session?.close()
            session = null
        }
    }
    val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> reconcile() }
    lifecycle.addObserver(observer)
    reconcile()
    return java.io.Closeable {
        lifecycle.removeObserver(observer)
        session?.close()
        session = null
    }
}
