package com.theoriacodex.app.viewer

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/** One subtitle or audio track the playing video offers. [id] names it for [selectSubtitle] and [selectAudio] while this video plays. */
data class PlaybackTrack(val id: String, val label: String, val selected: Boolean)

/** The tracks a video offers beyond its picture; both lists are empty until the player has read the stream. */
data class PlaybackTracks(val subtitles: List<PlaybackTrack> = emptyList(), val audio: List<PlaybackTrack> = emptyList())

/**
 * The subtitle and audio tracks of what this player is playing, embedded in the stream or added by its
 * [VideoPlaybackRequest]. They change as the stream loads, so read them again from `Player.Listener.onTracksChanged`.
 */
fun Player.playbackTracks(): PlaybackTracks =
    PlaybackTracks(subtitles = tracksOf(C.TRACK_TYPE_TEXT), audio = tracksOf(C.TRACK_TYPE_AUDIO))

/** Shows the subtitle track [id] from [playbackTracks]; null turns subtitles off. */
fun Player.selectSubtitle(id: String?) {
    val parameters = trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
    val override = id?.let { overrideFor(C.TRACK_TYPE_TEXT, it) }
    if (override != null) parameters.setOverrideForType(override)
    trackSelectionParameters = parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, override == null).build()
}

/** Plays the audio track [id] from [playbackTracks]; an id the video no longer offers changes nothing. */
fun Player.selectAudio(id: String) {
    val override = overrideFor(C.TRACK_TYPE_AUDIO, id) ?: return
    trackSelectionParameters = trackSelectionParameters.buildUpon().setOverrideForType(override).build()
}

private fun Player.groupsOf(type: Int): List<Tracks.Group> = currentTracks.groups.filter { it.type == type }

private fun Player.tracksOf(type: Int): List<PlaybackTrack> = groupsOf(type).flatMapIndexed { groupIndex, group ->
    (0 until group.length).filter(group::isTrackSupported).map { track ->
        val format = group.getTrackFormat(track)
        PlaybackTrack(
            id = "$groupIndex:$track",
            label = format.label ?: format.language ?: "",
            selected = group.isTrackSelected(track),
        )
    }
}

private fun Player.overrideFor(type: Int, id: String): TrackSelectionOverride? {
    val (groupIndex, track) = id.split(':').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return null
    val group = groupsOf(type).getOrNull(groupIndex)?.takeIf { track in 0 until it.length } ?: return null
    return TrackSelectionOverride(group.mediaTrackGroup, track)
}
