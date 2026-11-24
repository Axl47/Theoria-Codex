package com.theoriacodex.app.viewer

import com.theoriacodex.app.media.withVideoQuality
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.MediaTrack
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality

data class VideoPlaybackRequest(
    val location: String,
    val headers: Map<String, String>,
    val mime: String? = null,
    val subtitles: List<MediaTrack> = emptyList(),
    val audioTracks: List<MediaTrack> = emptyList(),
    val cache: Boolean = true,
)

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
            headers = stream.headers,
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

fun ImageRef.viewerStream(streamLabel: String?): MediaStream? =
    streams.firstOrNull { it.label == streamLabel } ?: streams.firstOrNull()
