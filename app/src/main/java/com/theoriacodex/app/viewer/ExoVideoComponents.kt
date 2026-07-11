@file:androidx.annotation.OptIn(UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.theoriacodex.app.R
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
): ExoPlayer {
    val appContext = context.applicationContext
    val dataSourceFactory = buildVideoDataSourceFactory(appContext, location, headers)
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    return ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setSeekParameters(SeekParameters.EXACT)
            volume = if (muted) 0f else 1f
            setMediaItem(MediaItem.fromUri(videoLocationToUri(location)))
            prepare()
        }
}

private fun buildVideoDataSourceFactory(
    context: Context,
    location: String,
    headers: Map<String, String>,
): DataSource.Factory {
    if (!isHttpLocation(location)) {
        return DefaultDataSource.Factory(context)
    }
    val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(12_000)
        .setReadTimeoutMs(24_000)
        .setUserAgent("Mozilla/5.0")
    if (headers.isNotEmpty()) {
        httpFactory.setDefaultRequestProperties(headers)
    }
    return DefaultDataSource.Factory(context, httpFactory)
}

private fun isHttpLocation(location: String): Boolean {
    return location.startsWith("http://", ignoreCase = true) ||
        location.startsWith("https://", ignoreCase = true)
}

private fun videoLocationToUri(location: String): Uri {
    return when {
        location.startsWith("http://", ignoreCase = true) ||
            location.startsWith("https://", ignoreCase = true) ||
            location.startsWith("content://", ignoreCase = true) -> location.toUri()

        else -> File(location).toUri()
    }
}
