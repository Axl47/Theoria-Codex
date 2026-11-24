package com.theoriacodex.app.viewer.state

/**
 * What the user chose while watching a series. It holds for every episode of the session and survives the screen
 * leaving composition. Choices go by label, since stream links and track ids change from one episode, or one player,
 * to the next. A null field leaves the choice to the episode: its first stream, Media3's own audio, and the subtitles
 * Media3 picks, else the ones in the device's language, else the first. A subtitle choice the episode lacks shows
 * those too; only [ViewerSubtitleChoice.Off] turns subtitles off.
 */
data class ViewerEpisodeChoice(
    val streamLabel: String? = null,
    val subtitle: ViewerSubtitleChoice? = null,
    val audioLabel: String? = null,
    /** Whether landscape episodes crop to fill the screen instead of fitting with pillarbox/letterbox bars. */
    val fillScreen: Boolean = false,
    /**
     * The streams episodes play in place of [streamLabel]; see [streamLabelFor]. Each episode keeps its own, so an
     * episode that fell back still plays its working stream after another episode fell back too.
     */
    val episodeStreams: Map<ViewerMediaKey, ViewerEpisodeStream> = emptyMap(),
) {
    /** The stream [media] plays: the one it fell back to or was picked by hand in, else the session's choice. */
    fun streamLabelFor(media: ViewerMediaKey?): String? = episodeStreams[media]?.label ?: streamLabel

    /** The stream the user picked by hand in [media], if any: a stream they chose gets no automatic fallback. */
    fun pickedStream(media: ViewerMediaKey?): String? = episodeStreams[media]?.takeIf { it.pickedByHand }?.label
}

/**
 * The stream [label] that one episode plays: picked by hand there ([pickedByHand]), or fallen back to after the one
 * before failed. A fallback holds for that episode only and leaves the session's choice for later episodes alone.
 */
data class ViewerEpisodeStream(val label: String, val pickedByHand: Boolean)

/** Subtitles turned off, or the track labelled [Track.label] shown. */
sealed interface ViewerSubtitleChoice {
    data object Off : ViewerSubtitleChoice
    data class Track(val label: String) : ViewerSubtitleChoice
}
