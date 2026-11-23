package com.theoriacodex.app.viewer

import com.theoriacodex.app.media.withVideoQuality
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.MediaTrack
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality

/**
 * Everything one player needs to play one video. [VideoPlaybackInfrastructure.bind] turns it into the player's
 * [BoundVideoPlaybackRequest]; the Viewer gets it from [viewerPlayback], so its player and its prefetcher always agree.
 */
data class VideoPlaybackRequest(
    /** An http(s) address, a file path or a content URI. */
    val location: String,
    /** Sent with every request for [location]; part of the cache identity, so prefetch and playback must match. */
    val headers: Map<String, String>,
    /**
     * The container or manifest type. It wins over [location]'s extension, which decides when this is null: `.m3u8` is
     * HLS, `.mpd` DASH, anything else one progressive file.
     */
    val mime: String? = null,
    /** External subtitle files, drawn by the player once one is chosen with [selectSubtitle]; none shows by default. */
    val subtitles: List<MediaTrack> = emptyList(),
    /** Audio served apart from the video, merged with it by the player. The first plays until [selectAudio] picks another. */
    val audioTracks: List<MediaTrack> = emptyList(),
    /**
     * Whether playback reads and writes the shared video cache. False for a full-length episode, which would evict
     * every short video from it; such a stream is never prefetched, since nothing would read what was warmed.
     */
    val cache: Boolean = true,
)

/**
 * What the Viewer plays for this media from [source]; null when it has nothing to play. The one place the Viewer's
 * player and prefetcher get a stream from, so both name the same location, headers and cache identity.
 *
 * Media with [ImageRef.streams] (a series episode) plays the stream labelled [streamLabel], else the first, which is
 * its source's preferred one, outside the shared cache. It sends that stream's headers when it has any, else
 * [source]'s. Any other media plays its saved file, or the rendition [withVideoQuality] picks for [quality], [metered]
 * and a [height] chosen in the playback menu, with [source]'s headers and that rendition's mime.
 */
fun ImageRef.viewerPlayback(
    source: SourceKey,
    quality: VideoQuality,
    metered: Boolean,
    height: Int? = null,
    streamLabel: String? = null,
): VideoPlaybackRequest? {
    val stream = viewerStream(streamLabel)
    if (localPath.isNullOrBlank() && stream != null) {
        return VideoPlaybackRequest(
            location = stream.url,
            headers = stream.headers.ifEmpty { source.requestHeaders() },
            mime = stream.mime,
            subtitles = stream.subtitles,
            audioTracks = stream.audioTracks,
            cache = false,
        )
    }
    val selected = withVideoQuality(quality, metered, height)
    val location = selected.localPath?.takeIf(String::isNotBlank) ?: selected.url?.takeIf(String::isNotBlank) ?: return null
    return VideoPlaybackRequest(location, source.requestHeaders(), mime = selected.mime)
}

/**
 * The stream [viewerPlayback] plays for media with [ImageRef.streams]: the playable one labelled [streamLabel], else
 * the first playable one; null when there is none. A label that no longer exists after its links were fetched again
 * falls back to the first.
 */
fun ImageRef.viewerStream(streamLabel: String?): MediaStream? {
    val playable = streams.filter { it.url.isNotBlank() }
    return playable.firstOrNull { it.label == streamLabel } ?: playable.firstOrNull()
}
