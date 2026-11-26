@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.MediaTrack
import com.theoriacodex.domain.model.uniqueLabels

data class PlaybackTrack(
    val id: String,
    val label: String,
    val selected: Boolean,
    val sideloadIndex: Int? = null,
)

data class PlaybackTracks(val subtitles: List<PlaybackTrack> = emptyList(), val audio: List<PlaybackTrack> = emptyList())

data class EpisodeClipTracks(
    val subtitles: Boolean = false,
    val subtitleIndex: Int? = null,
    val audioIndex: Int? = null,
    val streamAudioLabel: String? = null,
)

fun EpisodeClipTracks.movedTo(from: MediaStream, to: MediaStream): EpisodeClipTracks = copy(
    subtitleIndex = subtitleIndex?.let { sameTrackIndex(from.subtitles, to.subtitles, it) },
    audioIndex = audioIndex?.let { sameTrackIndex(from.audioTracks, to.audioTracks, it) },
)

private fun sameTrackIndex(from: List<MediaTrack>, to: List<MediaTrack>, index: Int): Int? {
    val source = from.filter { it.url.isNotBlank() }
    val track = source.getOrNull(index) ?: return null
    val before = source.indices.filter { source[it].label == track.label }
    val after = to.filter { it.url.isNotBlank() }.withIndex().filter { it.value.label == track.label }.map { it.index }
    return if (before.size == after.size) after[before.indexOf(index)] else null
}

fun PlaybackTracks.clipTracks(): EpisodeClipTracks {
    val subtitle = subtitles.firstOrNull { it.selected }
    val audio = audio.firstOrNull { it.selected }
    return EpisodeClipTracks(
        subtitles = subtitle != null,
        subtitleIndex = subtitle?.sideloadIndex,
        audioIndex = audio?.sideloadIndex,
        streamAudioLabel = audio?.takeIf { it.sideloadIndex == null }?.label,
    )
}

fun Player.playbackTracks(): PlaybackTracks =
    PlaybackTracks(subtitles = tracksOf(C.TRACK_TYPE_TEXT), audio = tracksOf(C.TRACK_TYPE_AUDIO))

fun Player.selectSubtitle(id: String?) {
    val parameters = trackSelectionParameters.buildUpon()
    if (id == null) {
        trackSelectionParameters = parameters.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        return
    }
    val override = overrideFor(C.TRACK_TYPE_TEXT, id) ?: return
    trackSelectionParameters = parameters.setOverrideForType(override).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
}

fun Player.selectAudio(id: String) {
    val override = overrideFor(C.TRACK_TYPE_AUDIO, id) ?: return
    trackSelectionParameters = trackSelectionParameters.buildUpon().setOverrideForType(override).build()
}

internal fun Player.clearTrackChoices() {
    trackSelectionParameters = trackSelectionParameters.buildUpon().clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
}

private const val TRACK_ID_SEPARATOR = '/'

private fun Player.tracksOf(type: Int): List<PlaybackTrack> {
    val tracks = currentTracks.groups.filter { it.type == type }.flatMap { group ->
        val separateIndex = separateAudioIndex(group)
        val separateLabel = separateIndex?.let { index -> separateAudioLabels()?.labels?.get(index) }
        val sideloadIndex = separateIndex ?: sideloadedSubtitleIndex(group)
        (0 until group.length).filter(group::isTrackSupported).map { track ->
            val format = group.getTrackFormat(track)
            PlaybackTrack(
                id = "${group.mediaTrackGroup.id}$TRACK_ID_SEPARATOR$track",
                label = separateLabel ?: format.label ?: format.language ?: "",
                selected = group.isTrackSelected(track),
                sideloadIndex = sideloadIndex,
            )
        }
    }
    return tracks.zip(uniqueLabels(tracks.map { it.label }) { "Track $it" }) { track, label -> track.copy(label = label) }
}

private fun Player.overrideFor(type: Int, id: String): TrackSelectionOverride? {
    if (TRACK_ID_SEPARATOR !in id) return null
    val groupId = id.substringBeforeLast(TRACK_ID_SEPARATOR)
    val track = id.substringAfterLast(TRACK_ID_SEPARATOR).toIntOrNull() ?: return null
    val group = currentTracks.groups.firstOrNull { it.type == type && it.mediaTrackGroup.id == groupId } ?: return null
    return if (track in 0 until group.length) TrackSelectionOverride(group.mediaTrackGroup, track) else null
}

private fun Player.separateAudioLabels(): SeparateAudioLabels? = currentMediaItem?.localConfiguration?.tag as? SeparateAudioLabels

private fun Player.sideloadedSubtitleIndex(group: Tracks.Group): Int? {
    if (group.type != C.TRACK_TYPE_TEXT) return null
    val child = mergedChildIndex(group) ?: return null
    return (child - 1 - (separateAudioLabels()?.labels?.size ?: 0)).takeIf { it >= 0 }
}

private fun mergedChildIndex(group: Tracks.Group): Int? = group.mediaTrackGroup.id.substringBefore(':', "").toIntOrNull()

internal fun Player.separateAudioIndex(group: Tracks.Group): Int? {
    if (group.type != C.TRACK_TYPE_AUDIO) return null
    val count = separateAudioLabels()?.labels?.size ?: return null
    val child = mergedChildIndex(group) ?: return null
    return (child - 1).takeIf { it in 0 until count }
}
