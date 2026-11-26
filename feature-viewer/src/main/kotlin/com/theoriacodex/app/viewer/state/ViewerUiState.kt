package com.theoriacodex.app.viewer.state

import com.theoriacodex.app.viewer.EpisodeClipRange

import com.theoriacodex.app.viewer.ViewerSeries
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

enum class ViewerMediaKind(
    val seekable: Boolean = false,
    val animated: Boolean = seekable,
) {
    IMAGE,
    VIDEO(seekable = true),
    ANIMATED_WEBP(animated = true),
    GIF(seekable = true),
    ANIMATED_IMAGE(animated = true),
    UGOIRA(seekable = true),
    UNKNOWN,
}

data class ViewerMediaState(
    val key: ViewerMediaKey,
    val ref: ImageRef,
    val kind: ViewerMediaKind,
    val displayLocation: String?,
    val loadGeneration: Long = 0L,
    val ready: Boolean = false,
) {
    init {
        require(loadGeneration >= 0L) { "Viewer media load generation must not be negative" }
    }
}

enum class ViewerResolutionStatus {
    NOT_REQUIRED,
    IDLE,
    REQUESTED,
    RESOLVING,
    RESOLVED,
    FAILED,
}

data class ViewerResolutionState(
    val status: ViewerResolutionStatus = ViewerResolutionStatus.NOT_REQUIRED,
    val attempt: Int = 0,
    val message: String? = null,
    val recoverable: Boolean = true,
    val resolvedAtMs: Long? = null,
) {
    init {
        require(attempt >= 0) { "Viewer resolution attempt must not be negative" }
    }
}

data class ViewerPageState(
    val post: Post,
    val media: List<ViewerMediaState>,
    val selectedMediaIndex: Int = 0,
    val resolution: ViewerResolutionState = ViewerResolutionState(),
    val pendingMediaIndex: Int? = null,
) {
    val selectedMedia: ViewerMediaState?
        get() = media.getOrNull(selectedMediaIndex)

    val targetMediaIndex: Int
        get() = pendingMediaIndex ?: selectedMediaIndex
}

data class ViewerPlaybackControlsState(
    val available: Boolean = false,
    val playing: Boolean = false,
    val playbackRate: Float = 1f,
    val restartRequest: Long = 0L,
    val completed: Boolean = false,
) {
    init {
        require(playbackRate > 0f) { "Viewer playback rate must be positive" }
        require(restartRequest >= 0L) { "Viewer restart request must not be negative" }
    }
}

internal val ViewerUiState.loopsPlayback: Boolean
    get() = controls.playbackMode == ViewerPlaybackMode.LOOP && !controls.slideshow && series !is ViewerSeries.Watching

internal typealias ViewerPlaybackMode = com.theoriacodex.domain.model.ViewerPlaybackMode

enum class ViewerPanel(
    val inChrome: Boolean,
) {
    ACTIONS_MENU(inChrome = true),
    PLAYBACK_SETTINGS(inChrome = true),
    INFO(inChrome = false),
    EPISODE_STREAM(inChrome = true),
    EPISODE_SUBTITLES(inChrome = true),
    EPISODE_AUDIO(inChrome = true),
}

data class ViewerControlsState(
    val playbackMode: ViewerPlaybackMode = ViewerPlaybackMode.LOOP,
    val chromeVisible: Boolean = true,
    val panel: ViewerPanel? = null,
    val playback: ViewerPlaybackControlsState = ViewerPlaybackControlsState(),
    val slideshow: Boolean = false,
    val dwell: ViewerDwell = ViewerDwell(),
    val locked: Boolean = false,
    val fillScreen: Boolean = false,
    val episode: ViewerEpisodeChoice = ViewerEpisodeChoice(),
    val clip: ViewerClipState? = null,
)

data class ViewerClipState(val mediaKey: ViewerMediaKey, val range: EpisodeClipRange? = null)

data class ViewerOverviewState(
    val visible: Boolean = false,
    val available: Boolean = false,
)

sealed interface ViewerMediaError {
    val mediaKey: ViewerMediaKey?
    val message: String

    data class Recoverable(
        override val mediaKey: ViewerMediaKey,
        override val message: String,
        val retryCount: Int = 0,
    ) : ViewerMediaError

    data class Fatal(
        override val mediaKey: ViewerMediaKey?,
        override val message: String,
    ) : ViewerMediaError
}

data class ViewerRelatedNavigation(val anchor: PostId, val postIds: List<PostId>)

data class ViewerRelatedBranch(
    val navigation: ViewerRelatedNavigation? = null,
    val extraPostIds: Set<PostId> = emptySet(),
)

data class ViewerUiState(
    val session: ViewerSessionIdentity? = null,
    val series: ViewerSeries? = null,
    val pages: List<ViewerPageState> = emptyList(),
    val currentPageIndex: Int = 0,
    val controls: ViewerControlsState = ViewerControlsState(),
    val overview: ViewerOverviewState = ViewerOverviewState(),
    val mediaError: ViewerMediaError? = null,
    val related: ViewerRelatedBranch = ViewerRelatedBranch(),
) {
    val currentPage: ViewerPageState?
        get() = pages.getOrNull(currentPageIndex)

    val currentMedia: ViewerMediaState?
        get() = currentPage?.selectedMedia

    companion object {
        val Empty = ViewerUiState()
    }
}
