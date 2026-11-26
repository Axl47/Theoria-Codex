@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.theoriacodex.domain.model.MediaTrack

internal fun streamMimeType(mime: String?): String? {
    val lower = mime?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    return when (lower) {
        "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
        else -> MimeTypes.normalizeMimeType(lower)
    }
}

internal enum class StreamType { HLS, DASH, PROGRESSIVE }

internal fun streamType(uri: Uri, mime: String?): StreamType = when (Util.inferContentTypeForUriAndMimeType(uri, mime)) {
    C.CONTENT_TYPE_HLS -> StreamType.HLS
    C.CONTENT_TYPE_DASH -> StreamType.DASH
    else -> StreamType.PROGRESSIVE
}

fun VideoPlaybackRequest.usesSharedCache(): Boolean =
    cache && isHttpVideoLocation(location) && streamType(videoLocationToUri(location), streamMimeType(mime)) == StreamType.PROGRESSIVE

fun VideoPlaybackRequest.readsAheadFreely(): Boolean = usesSharedCache() || !isHttpVideoLocation(location)

fun VideoPlaybackRequest.previewsScrubFrames(): Boolean =
    readsAheadFreely() && streamType(videoLocationToUri(location), streamMimeType(mime)) == StreamType.PROGRESSIVE

private fun separateAudioType(uri: Uri, main: StreamType): StreamType =
    if ('.' in uri.lastPathSegment.orEmpty()) streamType(uri, null) else main

internal data class SeparateAudioLabels(val labels: List<String>)

internal class StreamMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val audio: List<MediaTrack> = emptyList(),
    private val subtitles: List<MediaTrack> = emptyList(),
) : MediaSource.Factory {
    private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null

    private val progressiveSources by lazy(LazyThreadSafetyMode.NONE) { ProgressiveMediaSource.Factory(dataSourceFactory) }
    private val dashSources by lazy(LazyThreadSafetyMode.NONE) { DashMediaSource.Factory(dataSourceFactory) }

    private val hlsSources by lazy(LazyThreadSafetyMode.NONE) {
        HlsMediaSource.Factory(dataSourceFactory).setExtractorFactory(
            DefaultHlsExtractorFactory(0, false),
        )
    }
    private val subtitleSources by lazy(LazyThreadSafetyMode.NONE) {
        SingleSampleMediaSource.Factory(dataSourceFactory).setTreatLoadErrorsAsEndOfStream(true)
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val configuration = requireNotNull(mediaItem.localConfiguration)
        val type = streamType(configuration.uri, configuration.mimeType)
        val main = sourceFor(type, mediaItem)
        if (audio.isEmpty() && subtitles.isEmpty()) return main
        return MergingMediaSource(
            main,
            *audio.map { track ->
                val uri = videoLocationToUri(track.url)
                sourceFor(separateAudioType(uri, type), MediaItem.fromUri(uri))
            }.toTypedArray(),
            *subtitles.map { track ->
                val configuration = MediaItem.SubtitleConfiguration.Builder(videoLocationToUri(track.url))
                    .setMimeType(subtitleMimeType(track.url) ?: MimeTypes.TEXT_UNKNOWN)
                    .setLabel(track.label)
                    .build()
                subtitleSources.apply { loadErrorHandlingPolicy?.let(::setLoadErrorHandlingPolicy) }
                    .createMediaSource(configuration, C.TIME_UNSET)
            }.toTypedArray(),
        )
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { drmSessionManagerProvider = provider }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { loadErrorHandlingPolicy = policy }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_OTHER)

    private fun sourceFor(type: StreamType, mediaItem: MediaItem): MediaSource {
        val factory = when (type) {
            StreamType.HLS -> hlsSources
            StreamType.DASH -> dashSources
            StreamType.PROGRESSIVE -> progressiveSources
        }
        drmSessionManagerProvider?.let(factory::setDrmSessionManagerProvider)
        loadErrorHandlingPolicy?.let(factory::setLoadErrorHandlingPolicy)
        return factory.createMediaSource(mediaItem)
    }
}

internal fun isHttpVideoLocation(location: String): Boolean =
    location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)
