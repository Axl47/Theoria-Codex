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

/**
 * [mime] in the spelling Media3 matches, or null when blank. HLS goes by several names
 * (`application/vnd.apple.mpegurl`, `audio/mpegurl`), and Media3 only recognises `application/x-mpegURL`.
 */
internal fun streamMimeType(mime: String?): String? {
    val lower = mime?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    return when (lower) {
        "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
        else -> MimeTypes.normalizeMimeType(lower)
    }
}

/** How Media3 reads a stream: an HLS or DASH manifest, or anything else as one progressive file. */
internal enum class StreamType { HLS, DASH, PROGRESSIVE }

/**
 * The [StreamType] of [uri], decided by [mime] when there is one, else by the extension of its path (`.m3u8`, `.mpd`;
 * the query is ignored). Formats the app does not play as such, SmoothStreaming and RTSP, are progressive files, so
 * they fail as an ordinary player error instead of a missing media source.
 */
internal fun streamType(uri: Uri, mime: String?): StreamType = when (Util.inferContentTypeForUriAndMimeType(uri, mime)) {
    C.CONTENT_TYPE_HLS -> StreamType.HLS
    C.CONTENT_TYPE_DASH -> StreamType.DASH
    else -> StreamType.PROGRESSIVE
}

/**
 * Whether this request plays through, and may be prefetched into, the shared video cache: a progressive web file
 * that does not opt out. A manifest never does, whatever [VideoPlaybackRequest.cache] says: its playlist can change
 * between reads, and the cache would answer with the stale copy, and its segments are separate files that one cache
 * identity cannot name.
 */
fun VideoPlaybackRequest.usesSharedCache(): Boolean =
    cache && isHttpVideoLocation(location) && streamType(videoLocationToUri(location), streamMimeType(mime)) == StreamType.PROGRESSIVE

/** The labels of a request's separate audio, in merge order, carried as its media item's tag for [playbackTracks]. */
internal data class SeparateAudioLabels(val labels: List<String>)

/**
 * Media3's media sources for one request: progressive, HLS or DASH as [streamType] decides, with the request's
 * separate [audio] and [subtitles] merged in, every one read through [dataSourceFactory] so it carries the stream's
 * headers. The merged children are ordered video, audio, subtitles, and Media3 prefixes each track group's id with
 * its child's index, which is how [playbackTracks] labels separate audio.
 *
 * Subtitles are loaded only once selected, and a failed one ends its track instead of the video. Media3's own side
 * loading (`MediaItem.subtitleConfigurations`) downloads every subtitle file before playback can start, and one dead
 * link fails the whole player.
 */
internal class StreamMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val audio: List<MediaTrack> = emptyList(),
    private val subtitles: List<MediaTrack> = emptyList(),
) : MediaSource.Factory {
    private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null

    // Each is built on first use: a feed card binds a factory per clip and needs only one of them.
    private val progressiveSources by lazy(LazyThreadSafetyMode.NONE) { ProgressiveMediaSource.Factory(dataSourceFactory) }
    private val dashSources by lazy(LazyThreadSafetyMode.NONE) { DashMediaSource.Factory(dataSourceFactory) }

    // Media3's default HLS extractors, except that they no longer list a closed-caption track the playlist does not
    // declare: an empty CEA-608 track would otherwise show on nearly every HLS stream. Declared captions still appear.
    private val hlsSources by lazy(LazyThreadSafetyMode.NONE) {
        HlsMediaSource.Factory(dataSourceFactory).setExtractorFactory(
            DefaultHlsExtractorFactory(/* payloadReaderFactoryFlags = */ 0, /* exposeCea608WhenMissingDeclarations = */ false),
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
                sourceFor(streamType(uri, null), MediaItem.fromUri(uri))
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
