package com.theoriacodex.app.viewer.state

import com.theoriacodex.app.viewer.EpisodeClipRange
import com.theoriacodex.app.viewer.EpisodeClipTracks

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.app.viewer.ViewerSeries
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm

sealed interface ViewerAction {
    data class ReplaceSession(
        val session: ViewerSessionIdentity,
        val posts: List<Post>,
        val initialPageIndex: Int = 0,
        val resolutionRequiredPostIds: Set<PostId> = emptySet(),
        val initialMediaIndices: Map<PostId, Int> = emptyMap(),
        val series: ViewerSeries? = null,
    ) : ViewerAction

    data class AppendPosts(
        val session: ViewerSessionIdentity,
        val posts: List<Post>,
        val resolutionRequiredPostIds: Set<PostId> = emptySet(),
        val initialMediaIndices: Map<PostId, Int> = emptyMap(),
    ) : ViewerAction

    data class IncludeRelatedPosts(
        val session: ViewerSessionIdentity,
        val seedId: PostId,
        val posts: List<Post>,
        val resolutionRequiredPostIds: Set<PostId> = emptySet(),
        val initialMediaIndices: Map<PostId, Int> = emptyMap(),
    ) : ViewerAction

    data class SelectPage(val pageIndex: Int) : ViewerAction
    data class SelectMedia(val mediaIndex: Int) : ViewerAction
    data class Navigate(val pageIndex: Int, val mediaIndex: Int) : ViewerAction
    data object ToggleChrome : ViewerAction
    data class SetChromeVisible(val visible: Boolean) : ViewerAction
    data object ToggleOverview : ViewerAction
    data class ShowPanel(val panel: ViewerPanel) : ViewerAction
    data class HidePanel(val panel: ViewerPanel) : ViewerAction

    data class SetPlaybackMode(val mode: ViewerPlaybackMode) : ViewerAction
    data class PlaybackCompleted(
        val session: ViewerSessionIdentity, val key: ViewerMediaKey, val loadGeneration: Long, val atMs: Long, val looped: Boolean = false,
    ) : ViewerAction
    data class RestartDwell(val atMs: Long, val interacting: Boolean) : ViewerAction
    data class SetSlideshow(val running: Boolean) : ViewerAction
    data class SlideshowAdvance(val session: ViewerSessionIdentity, val key: ViewerMediaKey, val dwellStartedAtMs: Long? = null) : ViewerAction
    data object Play : ViewerAction
    data object Pause : ViewerAction
    data object TogglePlayback : ViewerAction
    data object RestartPlayback : ViewerAction
    data class SetPlaybackRate(val rate: Float) : ViewerAction
    data class ChooseStream(val label: String) : ViewerAction
    data class FallBackToStream(val media: ViewerMediaKey, val label: String) : ViewerAction
    data class ChooseSubtitle(val choice: ViewerSubtitleChoice) : ViewerAction
    data class ChooseAudio(val label: String) : ViewerAction
    data class SetLocked(val locked: Boolean) : ViewerAction
    data object ToggleFillScreen : ViewerAction
    sealed interface Clip : ViewerAction
    data object StartClip : Clip
    data class SetClipRange(val range: EpisodeClipRange) : Clip
    data object CancelClip : Clip
    data class SaveClip(val tracks: EpisodeClipTracks) : Clip

    data class RequestPageResolution(val postId: PostId) : ViewerAction
    data class ResolutionCancelled(val session: ViewerSessionIdentity, val postId: PostId) : ViewerAction
    data class ResolutionStarted(
        val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : ViewerAction

    data class ResolutionCompleted(
        val session: ViewerSessionIdentity,
        val post: Post,
        val resolvedAtMs: Long? = null,
        val recovered: Boolean = false,
    ) : ViewerAction

    data class ResolutionFailed(
        val session: ViewerSessionIdentity,
        val postId: PostId,
        val message: String,
        val recoverable: Boolean,
    ) : ViewerAction

    data class QueuePrefetch(val requests: List<ViewerPrefetchRequest>) : ViewerAction

    data class MediaFailed(
        val session: ViewerSessionIdentity,
        val error: ViewerMediaError,
        val loadGeneration: Long? = null,
    ) : ViewerAction

    data class MediaReady(
        val session: ViewerSessionIdentity,
        val key: ViewerMediaKey,
        val loadGeneration: Long,
    ) : ViewerAction

    data object RetryMedia : ViewerAction

    data object Save : ViewerAction
    data object Download : ViewerAction
    data object ToggleLike : ViewerAction
    data class OpenCreator(val creator: CreatorProfile) : ViewerAction
    data class IncludeTag(val term: PostTaxonomyTerm) : ViewerAction
    data class ExcludeTag(val term: PostTaxonomyTerm) : ViewerAction
    data object LoadMore : ViewerAction
    data object PrepareDismiss : ViewerAction
    data object Dismiss : ViewerAction
}

data class ViewerPrefetchRequest(val key: ViewerMediaKey, val distance: Int, val decode: Boolean, val pageWidthPx: Int? = null)

sealed interface ViewerEffect {
    val session: ViewerSessionIdentity

    data class ResolvePost(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : ViewerEffect

    sealed interface Route : ViewerEffect

    data class SavePost(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : Route

    data class DownloadMedia(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
        val mediaKey: ViewerMediaKey,
    ) : Route

    data class ClipEpisode(
        override val session: ViewerSessionIdentity,
        val mediaKey: ViewerMediaKey,
        val range: EpisodeClipRange,
        val tracks: EpisodeClipTracks,
    ) : Route

    data class SetLiked(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : Route

    data class OpenCreatorProfile(
        override val session: ViewerSessionIdentity,
        val creator: CreatorProfile,
    ) : Route

    data class ApplyTag(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
        val term: PostTaxonomyTerm,
        val excluded: Boolean,
    ) : Route

    data class RetryMedia(
        override val session: ViewerSessionIdentity,
        val mediaKey: ViewerMediaKey,
    ) : Route

    data class LoadMore(
        override val session: ViewerSessionIdentity,
    ) : Route

    data class Dismiss(
        override val session: ViewerSessionIdentity,
    ) : Route
}

data class ViewerReduction(
    val state: ViewerUiState,
    val effects: List<ViewerEffect> = emptyList(),
)
