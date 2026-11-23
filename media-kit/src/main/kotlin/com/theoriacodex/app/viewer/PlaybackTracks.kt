@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/**
 * One subtitle or audio track the playing video offers. [id] names it for [selectSubtitle] and [selectAudio] while
 * this video plays; it is built from the track's group id, so it stays valid as more of the stream's tracks arrive.
 */
data class PlaybackTrack(val id: String, val label: String, val selected: Boolean)

/** The tracks a video offers beyond its picture; both lists are empty until the player has read the stream. */
data class PlaybackTracks(val subtitles: List<PlaybackTrack> = emptyList(), val audio: List<PlaybackTrack> = emptyList())

/**
 * The subtitle and audio tracks of what this player is playing, embedded in the stream or added by its
 * [VideoPlaybackRequest], that this device can play. Separate audio carries its request label. They change as the
 * stream loads, so read them again from `Player.Listener.onTracksChanged`.
 */
fun Player.playbackTracks(): PlaybackTracks =
    PlaybackTracks(subtitles = tracksOf(C.TRACK_TYPE_TEXT), audio = tracksOf(C.TRACK_TYPE_AUDIO))

/** Shows the subtitle track [id] from [playbackTracks]; null turns subtitles off, and an id the video does not offer changes nothing. */
fun Player.selectSubtitle(id: String?) {
    val parameters = trackSelectionParameters.buildUpon()
    if (id == null) {
        trackSelectionParameters = parameters.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        return
    }
    val override = overrideFor(C.TRACK_TYPE_TEXT, id) ?: return
    trackSelectionParameters = parameters.setOverrideForType(override).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
}

/** Plays the audio track [id] from [playbackTracks]; an id the video does not offer changes nothing. */
fun Player.selectAudio(id: String) {
    val override = overrideFor(C.TRACK_TYPE_AUDIO, id) ?: return
    trackSelectionParameters = trackSelectionParameters.buildUpon().setOverrideForType(override).build()
}

/** Undoes every [selectSubtitle] and [selectAudio], so the player's next video starts on Media3's own choice. */
internal fun Player.clearTrackChoices() {
    trackSelectionParameters = trackSelectionParameters.buildUpon().clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
}

/**
 * Plays the first of the request's separate audio tracks once the player has read them, over whichever Media3 would
 * rank higher (more channels, a higher bitrate, the video's own audio). Added by the pool for a request with separate
 * audio; it stops listening once those tracks are known.
 */
internal class FirstSeparateAudio(private val player: Player) : Player.Listener {
    override fun onTracksChanged(tracks: Tracks) {
        val group = tracks.groups.firstOrNull { player.separateAudioIndex(it) == 0 } ?: return
        player.removeListener(this)
        val track = (0 until group.length).firstOrNull(group::isTrackSupported) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
    }
}

/** Separates a track group's id from the track's index in [PlaybackTrack.id]. */
private const val TRACK_ID_SEPARATOR = '/'

private fun Player.tracksOf(type: Int): List<PlaybackTrack> = currentTracks.groups.filter { it.type == type }.flatMap { group ->
    val separateLabel = separateAudioIndex(group)?.let { index -> separateAudioLabels()?.labels?.get(index) }
    (0 until group.length).filter(group::isTrackSupported).map { track ->
        val format = group.getTrackFormat(track)
        PlaybackTrack(
            id = "${group.mediaTrackGroup.id}$TRACK_ID_SEPARATOR$track",
            label = separateLabel ?: format.label ?: format.language ?: "",
            selected = group.isTrackSelected(track),
        )
    }
}

private fun Player.overrideFor(type: Int, id: String): TrackSelectionOverride? {
    val groupId = id.substringBeforeLast(TRACK_ID_SEPARATOR)
    val track = id.substringAfterLast(TRACK_ID_SEPARATOR).toIntOrNull() ?: return null
    val group = currentTracks.groups.firstOrNull { it.type == type && it.mediaTrackGroup.id == groupId } ?: return null
    return if (track in 0 until group.length) TrackSelectionOverride(group.mediaTrackGroup, track) else null
}

private fun Player.separateAudioLabels(): SeparateAudioLabels? = currentMediaItem?.localConfiguration?.tag as? SeparateAudioLabels

/**
 * Which of the request's separate audio tracks [group] comes from, or null for any other group. The merged source
 * ([StreamMediaSourceFactory]) puts them after the video, and Media3 prefixes each group's id with its child's index.
 */
private fun Player.separateAudioIndex(group: Tracks.Group): Int? {
    if (group.type != C.TRACK_TYPE_AUDIO) return null
    val count = separateAudioLabels()?.labels?.size ?: return null
    val child = group.mediaTrackGroup.id.substringBefore(':', "").toIntOrNull() ?: return null
    return (child - 1).takeIf { it in 0 until count }
}
