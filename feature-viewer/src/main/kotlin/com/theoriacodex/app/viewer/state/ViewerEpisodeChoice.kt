package com.theoriacodex.app.viewer.state

data class ViewerEpisodeChoice(
    val streamLabel: String? = null,
    val subtitle: ViewerSubtitleChoice? = null,
    val audioLabel: String? = null,
    val episodeStreams: Map<ViewerMediaKey, ViewerEpisodeStream> = emptyMap(),
) {
    fun streamLabelFor(media: ViewerMediaKey?): String? = episodeStreams[media]?.label ?: streamLabel

    fun pickedStream(media: ViewerMediaKey?): String? = episodeStreams[media]?.takeIf { it.pickedByHand }?.label
}

data class ViewerEpisodeStream(val label: String, val pickedByHand: Boolean)

sealed interface ViewerSubtitleChoice {
    data object Off : ViewerSubtitleChoice
    data class Track(val label: String) : ViewerSubtitleChoice
}
