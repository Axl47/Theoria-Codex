package com.theoriacodex.app.viewer.state

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

enum class ViewerMediaKind {
    IMAGE,
    VIDEO,
    ANIMATED_WEBP,
    GIF,
    ANIMATED_IMAGE,
    UGOIRA,
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
    // Keep a launch/restore target while only a cover or partial gallery is available.
    val pendingMediaIndex: Int? = null,
) {
    val selectedMedia: ViewerMediaState?
        get() = media.getOrNull(selectedMediaIndex)
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

internal typealias ViewerPlaybackMode = com.theoriacodex.domain.model.ViewerPlaybackMode

data class ViewerControlsState(
    val playbackMode: ViewerPlaybackMode = ViewerPlaybackMode.LOOP,
    val chromeVisible: Boolean = true,
    val actionsMenuVisible: Boolean = false,
    val playbackSettingsVisible: Boolean = false,
    val metadataVisible: Boolean = false,
    val playback: ViewerPlaybackControlsState = ViewerPlaybackControlsState(),
    /** A slideshow is moving through the queue; session-only, never saved. */
    val slideshow: Boolean = false,
)

data class ViewerOverviewState(
    val visible: Boolean = false,
    val available: Boolean = false,
)

/** [decoded] is the subset of [warmed] images that were decoded into memory rather than only downloaded. */
data class ViewerPrefetchState(
    val queued: Set<ViewerMediaKey> = emptySet(),
    val inFlight: Set<ViewerMediaKey> = emptySet(),
    val warmed: Map<ViewerMediaKey, Long> = emptyMap(),
    val decoded: Set<ViewerMediaKey> = emptySet(),
    val skipped: Set<ViewerMediaKey> = emptySet(),
    val failed: Set<ViewerMediaKey> = emptySet(),
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

/**
 * The recommendation branch entered from the related shelf: where it leaves the original feed, and which of the
 * pages are recommendations rather than the feed's own posts.
 */
data class ViewerRelatedBranch(
    val navigation: ViewerRelatedNavigation? = null,
    val extraPostIds: Set<PostId> = emptySet(),
)

data class ViewerUiState(
    val session: ViewerSessionIdentity? = null,
    val pages: List<ViewerPageState> = emptyList(),
    val currentPageIndex: Int = 0,
    val controls: ViewerControlsState = ViewerControlsState(),
    val overview: ViewerOverviewState = ViewerOverviewState(),
    val prefetch: ViewerPrefetchState = ViewerPrefetchState(),
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
