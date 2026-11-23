package com.theoriacodex.app.viewer.state

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

    /** Adds [posts] after the live [session]'s pages, leaving the pages, selection and controls it has untouched. */
    data class AppendPosts(
        val session: ViewerSessionIdentity,
        val posts: List<Post>,
        val resolutionRequiredPostIds: Set<PostId> = emptySet(),
        val initialMediaIndices: Map<PostId, Int> = emptyMap(),
    ) : ViewerAction

    /**
     * Enters, or extends, the recommendation branch from [seedId] with the related shelf's [posts]. Like
     * [AppendPosts] it adds pages only for the posts the session lacks and leaves every other page untouched.
     */
    data class IncludeRelatedPosts(
        val session: ViewerSessionIdentity,
        val seedId: PostId,
        val posts: List<Post>,
        val resolutionRequiredPostIds: Set<PostId> = emptySet(),
        val initialMediaIndices: Map<PostId, Int> = emptyMap(),
    ) : ViewerAction

    data class SelectPage(val pageIndex: Int) : ViewerAction
    data class SelectMedia(val mediaIndex: Int) : ViewerAction
    /** Moves to [mediaIndex] of page [pageIndex], as one step. */
    data class Navigate(val pageIndex: Int, val mediaIndex: Int) : ViewerAction
    data object ToggleChrome : ViewerAction
    /** Sets the requested chrome visibility outright, for callers that must not depend on a frame's snapshot of it. */
    data class SetChromeVisible(val visible: Boolean) : ViewerAction
    data object ToggleOverview : ViewerAction
    data class ShowPanel(val panel: ViewerPanel) : ViewerAction
    /** Closes [panel] if it is the open one. */
    data class HidePanel(val panel: ViewerPanel) : ViewerAction

    data class SetPlaybackMode(val mode: ViewerPlaybackMode) : ViewerAction
    data class PlaybackCompleted(val session: ViewerSessionIdentity, val key: ViewerMediaKey, val loadGeneration: Long) : ViewerAction
    data class SetSlideshow(val running: Boolean) : ViewerAction
    /** A slideshow's hold on the still image [key] ran out. */
    data class SlideshowAdvance(val session: ViewerSessionIdentity, val key: ViewerMediaKey) : ViewerAction
    data object Play : ViewerAction
    data object Pause : ViewerAction
    data object TogglePlayback : ViewerAction
    data object RestartPlayback : ViewerAction
    data class SetPlaybackRate(val rate: Float) : ViewerAction
    /**
     * Plays the episode stream labelled [label], from where playback is. It closes the episode menus, and clears a
     * failure shown on the media and plays again, so a stream picked after another failed starts at once.
     */
    data class ChooseStream(val label: String) : ViewerAction
    /** The episode [media] plays its stream [label] after the one before failed, without changing the session's choice. */
    data class FallBackToStream(val media: ViewerMediaKey, val label: String) : ViewerAction
    /** Applies [choice] to this episode and every later one; it closes the episode menus. */
    data class ChooseSubtitle(val choice: ViewerSubtitleChoice) : ViewerAction
    /** Plays the audio labelled [label] in this episode and every later one that has it; it closes the episode menus. */
    data class ChooseAudio(val label: String) : ViewerAction

    data class RequestPageResolution(val postId: PostId) : ViewerAction
    data class ResolutionCancelled(val session: ViewerSessionIdentity, val postId: PostId) : ViewerAction
    data class ResolutionStarted(
        val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : ViewerAction

    data class ResolutionCompleted(
        val session: ViewerSessionIdentity,
        val post: Post,
        /** When the lookup answered, in wall-clock milliseconds; see [ViewerResolutionState.resolvedAtMs]. */
        val resolvedAtMs: Long? = null,
        /**
         * The lookup asked again for media that failed while its page showed it ([com.theoriacodex.app.viewer.recoverMedia]):
         * the load in progress continues on the new media, so each media keeps its load generation (what a Retry
         * started, and what the load has tried), and a current page keeps its playback controls (paused stays paused).
         */
        val recovered: Boolean = false,
    ) : ViewerAction

    data class ResolutionFailed(
        val session: ViewerSessionIdentity,
        val postId: PostId,
        val message: String,
        val recoverable: Boolean,
    ) : ViewerAction

    /**
     * The media the Viewer wants warm now, most wanted first; anything else in flight is cancelled. The ViewModel
     * runs it and keeps its progress to itself, so prefetching never changes [ViewerUiState].
     */
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
    /** Pauses and closes every surface ahead of [Dismiss], so the return starts from the bare media. */
    data object PrepareDismiss : ViewerAction
    data object Dismiss : ViewerAction
}

/** One media item to warm, [distance] media items from the one on screen; [decode] keeps an image decoded in memory. */
data class ViewerPrefetchRequest(val key: ViewerMediaKey, val distance: Int, val decode: Boolean)

sealed interface ViewerEffect {
    val session: ViewerSessionIdentity

    /** Work the Viewer owner runs itself; the route never sees it. */
    data class ResolvePost(
        override val session: ViewerSessionIdentity,
        val postId: PostId,
    ) : ViewerEffect

    /** An effect the owner hands to the route, which reaches the app outside the Viewer. */
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
