package com.theoriacodex.app.creator.state

import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.feed.mergeFeedPosts
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post

enum class CreatorEmptyReason { NO_CREATOR, UNSUPPORTED_SOURCE, NO_RESULTS }
enum class CreatorFailureReason { UNSUPPORTED_SOURCE, REQUEST_FAILED }
enum class CreatorRequestKind { REFRESH, PAGE }

data class CreatorRequestIdentity(
    val generation: Long,
    val kind: CreatorRequestKind,
    val queryHash: String,
) {
    init {
        require(generation > 0L) { "Creator request generation must be positive" }
        require(queryHash.isNotBlank()) { "Creator request query hash must not be blank" }
    }
}

data class CreatorUiState(
    val creator: CreatorProfile? = null,
    val queryHash: String? = null,
    val results: List<Post> = emptyList(),
    val isRefreshing: Boolean = false,
    val isPaging: Boolean = false,
    val nextPageToken: String? = null,
    val errorMessage: String? = null,
    val failureReason: CreatorFailureReason? = null,
    val emptyReason: CreatorEmptyReason? = CreatorEmptyReason.NO_CREATOR,
    val nextRequestGeneration: Long = 1L,
    val activeRequest: CreatorRequestIdentity? = null,
    val relatedPosts: RelatedPostsUiState = RelatedPostsUiState.Idle,
) {
    val canLoadMore: Boolean get() = !nextPageToken.isNullOrBlank()
}

sealed interface CreatorAction {
    data class OpenCreator(val creator: CreatorProfile) : CreatorAction
    data object Refresh : CreatorAction
    data object LoadNextPage : CreatorAction
    data object RetryPage : CreatorAction
    data object SourceUnavailable : CreatorAction
    data class OpenResult(
        val index: Int,
        val scrollOffsetHint: Int = 0,
        val visibleResults: List<Post>? = null,
        val visibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
    ) : CreatorAction
    data class RelatedLikeCommitted(val post: Post, val outcome: LikeToggleOutcome) : CreatorAction
    data object DismissRelatedPosts : CreatorAction
    data object RetryRelatedPosts : CreatorAction
    data object ShowPreviousRelatedPosts : CreatorAction
    data object ShowNextRelatedPosts : CreatorAction
    data class OpenRelatedResult(val index: Int, val visibleResults: List<Post>) : CreatorAction
    data object Back : CreatorAction
    data class PageLoaded(val request: CreatorRequestIdentity, val page: Page<Post>) : CreatorAction
    data class RequestFailed(
        val request: CreatorRequestIdentity,
        val message: String,
        val reason: CreatorFailureReason = CreatorFailureReason.REQUEST_FAILED,
    ) : CreatorAction
    data class RequestCancelled(val request: CreatorRequestIdentity) : CreatorAction
}

sealed interface CreatorEffect {
    data class LoadPage(
        val request: CreatorRequestIdentity,
        val creator: CreatorProfile,
        val pageToken: String?,
    ) : CreatorEffect
    data class OpenViewer(
        val posts: List<Post>,
        val context: ViewerLaunchContext,
        val visibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
        val liveSearchBinding: Boolean = true,
    ) : CreatorEffect
    data object NavigateBack : CreatorEffect
}

data class CreatorTransition(val state: CreatorUiState, val effect: CreatorEffect? = null)

fun CreatorUiState.reduce(action: CreatorAction): CreatorTransition = when (action) {
    is CreatorAction.OpenCreator -> copy(
        creator = action.creator,
        queryHash = creatorQueryHash(action.creator),
        results = emptyList(),
    ).beginRequest(CreatorRequestKind.REFRESH)
    CreatorAction.Refresh -> if (creator == null || isRefreshing) {
        unchanged()
    } else {
        beginRequest(CreatorRequestKind.REFRESH)
    }
    CreatorAction.LoadNextPage, CreatorAction.RetryPage -> {
        if (creator == null || isRefreshing || isPaging || !canLoadMore ||
            (errorMessage != null && action != CreatorAction.RetryPage)
        ) {
            unchanged()
        } else {
            beginRequest(CreatorRequestKind.PAGE)
        }
    }
    CreatorAction.SourceUnavailable -> CreatorTransition(
        failed(
            "Creator browsing is not available for ${creator?.source?.name?.lowercase()}.",
            CreatorFailureReason.UNSUPPORTED_SOURCE,
            clearResults = true,
        )
    )
    is CreatorAction.OpenResult -> openResult(action)
    is CreatorAction.OpenRelatedResult -> openRelatedResult(action)
    is CreatorAction.RelatedLikeCommitted,
    CreatorAction.DismissRelatedPosts,
    CreatorAction.RetryRelatedPosts,
    CreatorAction.ShowPreviousRelatedPosts,
    CreatorAction.ShowNextRelatedPosts -> unchanged()
    CreatorAction.Back -> CreatorTransition(this, CreatorEffect.NavigateBack)
    is CreatorAction.PageLoaded -> pageLoaded(action)
    is CreatorAction.RequestFailed -> {
        if (activeRequest != action.request) unchanged() else CreatorTransition(
            failed(action.message, action.reason, action.request.kind == CreatorRequestKind.REFRESH)
        )
    }
    is CreatorAction.RequestCancelled -> cancelled(action)
}

private fun CreatorUiState.pageLoaded(action: CreatorAction.PageLoaded): CreatorTransition {
    if (activeRequest != action.request) return unchanged()
    val posts = mergeFeedPosts(
        if (action.request.kind == CreatorRequestKind.REFRESH) emptyList() else results, action.page.items,
    )
    return CreatorTransition(copy(
        results = posts,
        nextPageToken = action.page.nextPageToken?.takeIf(String::isNotBlank),
        isRefreshing = false,
        isPaging = false,
        errorMessage = null,
        failureReason = null,
        emptyReason = if (posts.isEmpty()) CreatorEmptyReason.NO_RESULTS else null,
        activeRequest = null,
    ))
}

private fun CreatorUiState.cancelled(action: CreatorAction.RequestCancelled): CreatorTransition {
    if (activeRequest != action.request) return unchanged()
    return CreatorTransition(copy(
        isRefreshing = false,
        isPaging = false,
        activeRequest = null,
        emptyReason = if (results.isEmpty()) CreatorEmptyReason.NO_RESULTS else null,
    ))
}

private fun CreatorUiState.beginRequest(kind: CreatorRequestKind): CreatorTransition {
    val selected = requireNotNull(creator)
    val request = CreatorRequestIdentity(nextRequestGeneration, kind, creatorQueryHash(selected))
    val token = if (kind == CreatorRequestKind.PAGE) nextPageToken else null
    return CreatorTransition(
        copy(
            isRefreshing = kind == CreatorRequestKind.REFRESH,
            isPaging = kind == CreatorRequestKind.PAGE,
            nextPageToken = token,
            errorMessage = null,
            failureReason = null,
            emptyReason = null,
            nextRequestGeneration = request.generation + 1L,
            activeRequest = request,
        ),
        CreatorEffect.LoadPage(request, selected, token),
    )
}

private fun CreatorUiState.openResult(action: CreatorAction.OpenResult): CreatorTransition {
    val selected = creator ?: return unchanged()
    val posts = action.visibleResults ?: results
    if (action.index !in posts.indices) return unchanged()
    return CreatorTransition(this, CreatorEffect.OpenViewer(
        posts = posts.toList(),
        context = ViewerLaunchContext(
            queryHash = queryHash ?: creatorQueryHash(selected),
            startIndex = action.index,
            streamSource = ViewerStreamSource.CREATOR_PROFILE,
            scrollOffsetHint = action.scrollOffsetHint,
        ),
        visibilityFilters = action.visibilityFilters,
    ))
}

private fun CreatorUiState.failed(
    message: String,
    reason: CreatorFailureReason,
    clearResults: Boolean,
): CreatorUiState = copy(
    results = if (clearResults) emptyList() else results,
    nextPageToken = if (clearResults) null else nextPageToken,
    isRefreshing = false,
    isPaging = false,
    errorMessage = message,
    failureReason = reason,
    emptyReason = if (reason == CreatorFailureReason.UNSUPPORTED_SOURCE) CreatorEmptyReason.UNSUPPORTED_SOURCE else null,
    activeRequest = null,
)

private fun CreatorUiState.unchanged(): CreatorTransition = CreatorTransition(this)

fun creatorQueryHash(creator: CreatorProfile): String {
    val key = creator.uploadsQuery ?: creator.profileId ?: creator.profileUrl ?: creator.displayName
    return "creator:${creator.source.name}:$key"
}

private fun CreatorUiState.openRelatedResult(action: CreatorAction.OpenRelatedResult): CreatorTransition {
    val request = relatedPosts.requestOrNull ?: return unchanged()
    if (action.index !in action.visibleResults.indices) return unchanged()
    return CreatorTransition(this, CreatorEffect.OpenViewer(
        posts = action.visibleResults.toList(),
        context = ViewerLaunchContext(
            queryHash = "related:${request.seed.id.source.name}:${request.seed.id.sourcePostId}",
            startIndex = action.index,
            streamSource = ViewerStreamSource.RELATED,
            scrollOffsetHint = 0,
        ),
        liveSearchBinding = false,
    ))
}
