package com.theoriacodex.app.viewer.state

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm

/** Stable identity used to reject late work from a replaced Viewer session. */
internal data class ViewerSessionIdentity(
    val value: String,
    val queryHash: String? = null,
    val streamKey: String? = null,
) {
    init {
        require(value.isNotBlank()) { "Viewer session identity must not be blank" }
    }
}

internal data class ViewerMediaKey(
    val postId: PostId,
    val mediaIndex: Int,
) {
    init {
        require(mediaIndex >= 0) { "Viewer media index must not be negative" }
    }
}

internal enum class ViewerMediaKind {
    IMAGE,
    VIDEO,
    ANIMATED_WEBP,
    GIF,
    ANIMATED_IMAGE,
    UGOIRA,
    UNKNOWN,
}

internal data class ViewerMediaState(
    val key: ViewerMediaKey,
    val ref: ImageRef,
    val kind: ViewerMediaKind,
    val displayLocation: String?,
    val downloadable: Boolean,
    val shareable: Boolean,
)

internal enum class ViewerResolutionStatus {
    NOT_REQUIRED,
    IDLE,
    REQUESTED,
    RESOLVING,
    RESOLVED,
    FAILED,
}

internal data class ViewerResolutionState(
    val status: ViewerResolutionStatus = ViewerResolutionStatus.NOT_REQUIRED,
    val attempt: Int = 0,
    val message: String? = null,
    val recoverable: Boolean = true,
) {
    init {
        require(attempt >= 0) { "Viewer resolution attempt must not be negative" }
    }
}

internal data class ViewerMetadataState(
    val title: String?,
    val authorName: String?,
    val creators: List<CreatorProfile>,
    val taxonomy: List<PostTaxonomyTerm>,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val pageUrl: String?,
)

internal data class ViewerPageState(
    val post: Post,
    val media: List<ViewerMediaState>,
    val selectedMediaIndex: Int = 0,
    val resolution: ViewerResolutionState = ViewerResolutionState(),
    val metadata: ViewerMetadataState,
) {
    val selectedMedia: ViewerMediaState?
        get() = media.getOrNull(selectedMediaIndex)
}

internal sealed interface ViewerPlaybackProgress {
    data object None : ViewerPlaybackProgress

    data class Timeline(
        val positionMs: Long = 0L,
        val durationMs: Long? = null,
    ) : ViewerPlaybackProgress

    data class Frames(
        val frameIndex: Int = 0,
        val frameCount: Int = 0,
    ) : ViewerPlaybackProgress {
        val fraction: Float
            get() = if (frameCount <= 1) {
                0f
            } else {
                frameIndex.coerceIn(0, frameCount - 1).toFloat() / (frameCount - 1).toFloat()
            }
    }
}

internal data class ViewerPlaybackControlsState(
    val available: Boolean = false,
    val playing: Boolean = false,
    val playbackRate: Float = 1f,
    val restartRequest: Long = 0L,
    val progress: ViewerPlaybackProgress = ViewerPlaybackProgress.None,
) {
    init {
        require(playbackRate > 0f) { "Viewer playback rate must be positive" }
        require(restartRequest >= 0L) { "Viewer restart request must not be negative" }
    }
}

internal data class ViewerControlsState(
    val chromeVisible: Boolean = true,
    val actionsMenuVisible: Boolean = false,
    val playbackSettingsVisible: Boolean = false,
    val metadataVisible: Boolean = false,
    val playback: ViewerPlaybackControlsState = ViewerPlaybackControlsState(),
)

internal data class ViewerOverviewItemState(
    val mediaKey: ViewerMediaKey,
    val kind: ViewerMediaKind,
    val posterLocation: String?,
    val selected: Boolean,
)

internal data class ViewerOverviewState(
    val visible: Boolean = false,
    val items: List<ViewerOverviewItemState> = emptyList(),
) {
    val available: Boolean
        get() = items.size > 1
}

internal data class ViewerPrefetchState(
    val queued: Set<ViewerMediaKey> = emptySet(),
    val inFlight: Set<ViewerMediaKey> = emptySet(),
    val ready: Set<ViewerMediaKey> = emptySet(),
    val unavailable: Set<ViewerMediaKey> = emptySet(),
)

internal sealed interface ViewerMediaError {
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

internal data class ViewerUiState(
    val session: ViewerSessionIdentity? = null,
    val pages: List<ViewerPageState> = emptyList(),
    val currentPageIndex: Int = 0,
    val controls: ViewerControlsState = ViewerControlsState(),
    val overview: ViewerOverviewState = ViewerOverviewState(),
    val prefetch: ViewerPrefetchState = ViewerPrefetchState(),
    val mediaError: ViewerMediaError? = null,
) {
    val currentPage: ViewerPageState?
        get() = pages.getOrNull(currentPageIndex)

    val currentMedia: ViewerMediaState?
        get() = currentPage?.selectedMedia

    val currentMetadata: ViewerMetadataState?
        get() = currentPage?.metadata

    companion object {
        val Empty = ViewerUiState()
    }
}
