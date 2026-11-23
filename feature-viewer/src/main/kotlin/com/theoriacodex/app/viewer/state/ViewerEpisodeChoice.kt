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
)

/** Subtitles turned off, or the track labelled [Track.label] shown. */
sealed interface ViewerSubtitleChoice {
    data object Off : ViewerSubtitleChoice
    data class Track(val label: String) : ViewerSubtitleChoice
}
