@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
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

/**
 * Whether [uri] is a segmented stream (HLS, DASH), decided by [mime] when there is one, else by the extension of its
 * path (`.m3u8`, `.mpd`; the query is ignored). Anything else plays as one progressive file.
 */
internal fun isSegmentedStream(uri: Uri, mime: String?): Boolean =
    Util.inferContentTypeForUriAndMimeType(uri, mime) != C.CONTENT_TYPE_OTHER

/**
 * Whether this request plays through, and may be prefetched into, the shared video cache: a progressive web file
 * that does not opt out. A segmented stream never does, whatever [VideoPlaybackRequest.cache] says: its playlist can
 * change between reads, and the cache would answer with the stale copy, and its segments are separate files that one
 * cache identity cannot name.
 */
fun VideoPlaybackRequest.usesSharedCache(): Boolean =
    cache && isHttpVideoLocation(location) && !isSegmentedStream(videoLocationToUri(location), streamMimeType(mime))

/** The labels of a request's separate audio, in merge order, carried as its media item's tag for [playbackTracks]. */
internal data class SeparateAudioLabels(val labels: List<String>)

/**
 * Media3's media sources for one request: progressive, HLS or DASH as [isSegmentedStream] decides, with the request's
 * separate [audio] and [subtitles] merged in, every one read through [dataSourceFactory] so it carries the stream's
 * headers. The merged children are ordered video, audio, subtitles, and Media3 prefixes each track group's id with
 * its child's index, which is how [playbackTracks] labels separate audio.
 *
 * Subtitles are loaded only once selected, and a failed one ends its track instead of the video. Media3's own side
 * loading (`MediaItem.subtitleConfigurations`) downloads every subtitle file before playback can start, and one dead
 * link fails the whole player.
 */
internal class StreamMediaSourceFactory(
    dataSourceFactory: DataSource.Factory,
    private val audio: List<MediaTrack> = emptyList(),
    private val subtitles: List<MediaTrack> = emptyList(),
) : MediaSource.Factory {
    private val sources = DefaultMediaSourceFactory(dataSourceFactory)

    // Media3's default HLS extractors, except that they no longer list a closed-caption track the playlist does not
    // declare: an empty CEA-608 track would otherwise show on nearly every HLS stream. Declared captions still appear.
    // Built on first use, like the subtitle sources: a feed card binds a factory per clip and needs neither.
    private val hlsSources by lazy(LazyThreadSafetyMode.NONE) {
        HlsMediaSource.Factory(dataSourceFactory).setExtractorFactory(
            DefaultHlsExtractorFactory(/* payloadReaderFactoryFlags = */ 0, /* exposeCea608WhenMissingDeclarations = */ false)
                .experimentalSetCodecsToParseWithinGopSampleDependencies(C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265),
        )
    }
    private val subtitleSources by lazy(LazyThreadSafetyMode.NONE) {
        SingleSampleMediaSource.Factory(dataSourceFactory).setTreatLoadErrorsAsEndOfStream(true)
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val main = sourceFor(mediaItem)
        if (audio.isEmpty() && subtitles.isEmpty()) return main
        return MergingMediaSource(
            main,
            *audio.map { track -> sourceFor(MediaItem.fromUri(videoLocationToUri(track.url))) }.toTypedArray(),
            *subtitles.map { track ->
                val configuration = MediaItem.SubtitleConfiguration.Builder(videoLocationToUri(track.url))
                    .setMimeType(subtitleMimeType(track.url))
                    .setLabel(track.label)
                    .build()
                subtitleSources.createMediaSource(configuration, C.TIME_UNSET)
            }.toTypedArray(),
        )
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        sources.setDrmSessionManagerProvider(provider)
        hlsSources.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        sources.setLoadErrorHandlingPolicy(policy)
        hlsSources.setLoadErrorHandlingPolicy(policy)
        subtitleSources.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = sources.supportedTypes

    private fun sourceFor(mediaItem: MediaItem): MediaSource {
        val configuration = requireNotNull(mediaItem.localConfiguration)
        val hls = Util.inferContentTypeForUriAndMimeType(configuration.uri, configuration.mimeType) == C.CONTENT_TYPE_HLS
        return if (hls) hlsSources.createMediaSource(mediaItem) else sources.createMediaSource(mediaItem)
    }
}

internal fun isHttpVideoLocation(location: String): Boolean =
    location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)
