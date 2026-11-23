// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.animesource.model

import okhttp3.Headers

/** An external subtitle or audio track: its address and a label (usually a language name, despite the field's name). */
data class Track(val url: String, val lang: String)

enum class ChapterType { Opening, Ending, Recap, MixedOp, Other }

/** A named span of the video, in seconds, such as an opening to skip. */
data class TimeStamp(val start: Double, val end: Double, val name: String, val type: ChapterType = ChapterType.Other)

/**
 * One playable stream. [videoUrl] is what the player opens, with [headers] on every request (manifest, segments, keys);
 * [subtitleTracks] and [audioTracks] are separate files played alongside. A video whose [videoUrl] is empty is
 * resolved first (`AnimeHttpSource.resolveVideo`).
 *
 * The primary constructor is interface 16's layout, which extensions of both interfaces call; the secondary one is
 * interface 14's (`url`, `quality`, `videoUrl`). [mpvArgs] and the ffmpeg arguments are for Aniyomi's own player and
 * are ignored here.
 */
data class Video(
    var videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
) {
    /** The page the video was found on, for interface-14 extensions that resolve [videoUrl] from it later. */
    private var videoPageUrl: String = ""

    val url: String get() = videoPageUrl

    @Deprecated("Use videoTitle instead", ReplaceWith("videoTitle"))
    val quality: String get() = videoTitle

    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(videoTitle = quality, videoUrl = videoUrl ?: "null", headers = headers, subtitleTracks = subtitleTracks, audioTracks = audioTracks) {
        videoPageUrl = url
    }
}
