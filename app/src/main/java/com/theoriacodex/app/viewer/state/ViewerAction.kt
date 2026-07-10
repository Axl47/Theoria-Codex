package com.theoriacodex.app.viewer.state

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm

internal sealed interface ViewerAction {
    data class ReplaceSession(
        val session: ViewerSessionIdentity,
        val posts: List<Post>,
        val initialPageIndex: Int = 0,
        val resolutionRequiredPostIds: Set<PostId> = emptySet(),
    ) : ViewerAction

    data class SelectPage(val pageIndex: Int) : ViewerAction
    data class SelectMedia(val mediaIndex: Int) : ViewerAction
    data class SelectOverviewMedia(val mediaIndex: Int) : ViewerAction
    data object ToggleChrome : ViewerAction
    data object ToggleOverview : ViewerAction
    data object ShowMetadata : ViewerAction
    data object HideMetadata : ViewerAction
    data object ShowActionsMenu : ViewerAction
    data object HideActionsMenu : ViewerAction
    data object ShowPlaybackSettings : ViewerAction
    data object HidePlaybackSettings : ViewerAction

    data object Play : ViewerAction
    data object Pause : ViewerAction
    data object TogglePlayback : ViewerAction
    data object RestartPlayback : ViewerAction
    data class SetPlaybackRate(val rate: Float) : ViewerAction
    data class TimelineProgressChanged(val positionMs: Long, val durationMs: Long?) : ViewerAction
    data class FrameProgressChanged(val frameIndex: Int, val frameCount: Int) : ViewerAction

    data object RequestCurrentPageResolution : ViewerAction
    data class ResolutionStarted(
        val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : ViewerAction

    data class ResolutionCompleted(
        val session: ViewerSessionIdentity,
        val post: Post,
    ) : ViewerAction

    data class ResolutionFailed(
        val session: ViewerSessionIdentity,
        val postId: PostId,
        val message: String,
        val recoverable: Boolean,
    ) : ViewerAction

    data class QueuePrefetch(val mediaKeys: List<ViewerMediaKey>) : ViewerAction
    data class PrefetchStarted(
        val session: ViewerSessionIdentity,
        val mediaKey: ViewerMediaKey,
    ) : ViewerAction

    data class PrefetchCompleted(
        val session: ViewerSessionIdentity,
        val mediaKey: ViewerMediaKey,
        val available: Boolean,
    ) : ViewerAction

    data class MediaFailed(
        val session: ViewerSessionIdentity,
        val error: ViewerMediaError,
    ) : ViewerAction

    data object ClearMediaError : ViewerAction
    data object RetryMedia : ViewerAction

    data object Save : ViewerAction
    data object Share : ViewerAction
    data object Download : ViewerAction
    data object ToggleLike : ViewerAction
    data class OpenCreator(val creator: CreatorProfile) : ViewerAction
    data class IncludeTag(val term: PostTaxonomyTerm) : ViewerAction
    data class ExcludeTag(val term: PostTaxonomyTerm) : ViewerAction
    data object LoadMore : ViewerAction
    data object Dismiss : ViewerAction
}

internal sealed interface ViewerEffect {
    val session: ViewerSessionIdentity

    data class ResolvePost(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : ViewerEffect

    data class PrefetchMedia(
        override val session: ViewerSessionIdentity,
        val mediaKeys: List<ViewerMediaKey>,
    ) : ViewerEffect

    data class SavePost(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : ViewerEffect

    data class ShareMedia(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
        val mediaKey: ViewerMediaKey,
    ) : ViewerEffect

    data class DownloadMedia(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
        val mediaKey: ViewerMediaKey,
    ) : ViewerEffect

    data class SetLiked(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : ViewerEffect

    data class OpenCreatorProfile(
        override val session: ViewerSessionIdentity,
        val creator: CreatorProfile,
    ) : ViewerEffect

    data class ApplyTag(
        override val session: ViewerSessionIdentity,
        val term: PostTaxonomyTerm,
        val excluded: Boolean,
    ) : ViewerEffect

    data class RetryMedia(
        override val session: ViewerSessionIdentity,
        val mediaKey: ViewerMediaKey,
    ) : ViewerEffect

    data class LoadMore(
        override val session: ViewerSessionIdentity,
    ) : ViewerEffect

    data class Dismiss(
        override val session: ViewerSessionIdentity,
    ) : ViewerEffect
}

internal data class ViewerReduction(
    val state: ViewerUiState,
    val effects: List<ViewerEffect> = emptyList(),
)
