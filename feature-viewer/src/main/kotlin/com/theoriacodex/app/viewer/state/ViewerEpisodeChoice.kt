package com.theoriacodex.app.viewer.state

/**
 * What the user chose while watching a series. It holds for every episode of the session and survives the screen
 * leaving composition. Choices go by label, since stream links and track ids change from one episode, or one player,
 * to the next. A null field leaves the choice to the episode: its first stream, Media3's own subtitle and audio.
 */
data class ViewerEpisodeChoice(
    val streamLabel: String? = null,
    val subtitle: ViewerSubtitleChoice? = null,
    val audioLabel: String? = null,
    /** The stream one episode plays in place of [streamLabel]; see [streamLabelFor]. */
    val episodeStream: ViewerEpisodeStream? = null,
) {
    /** The stream [media] plays: the one it fell back to or was picked by hand in, else the session's choice. */
    fun streamLabelFor(media: ViewerMediaKey?): String? = episodeStream?.takeIf { it.media == media }?.label ?: streamLabel

    /** Whether the user picked [media]'s stream by hand: a stream they chose gets no automatic fallback. */
    fun pickedByHand(media: ViewerMediaKey?): Boolean = episodeStream?.let { it.media == media && it.pickedByHand } == true
}

/**
 * The stream [label] that the episode [media] plays: picked by hand there ([pickedByHand]), or fallen back to after the
 * one before failed. A fallback holds for that episode only and leaves the session's choice for later episodes alone.
 */
data class ViewerEpisodeStream(val media: ViewerMediaKey, val label: String, val pickedByHand: Boolean)

/** Subtitles turned off, or the track labelled [Track.label] shown. */
sealed interface ViewerSubtitleChoice {
    data object Off : ViewerSubtitleChoice
    data class Track(val label: String) : ViewerSubtitleChoice
}
