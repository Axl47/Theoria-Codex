package com.theoriacodex.app.creator.state

import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.feed.mergeFeedPosts
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
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

/**
 * A feed's list while another feed is selected. Requests never outlive a tab switch, so only what the
 * feed would show again is kept; an empty snapshot with no [emptyReason] or [errorMessage] has not
 * finished its first load and is reloaded when selected.
 */
data class CreatorFeedState(
    val results: List<Post> = emptyList(),
    val nextPageToken: String? = null,
    val errorMessage: String? = null,
    val failureReason: CreatorFailureReason? = null,
    val emptyReason: CreatorEmptyReason? = null,
) {
    val needsLoad: Boolean get() = results.isEmpty() && emptyReason == null && errorMessage == null
}

/**
 * The creator page. For an account creator ([feeds] non-empty) it has one list per [AccountFeed]: the
 * selected feed's list lives in the top-level fields below, so the Viewer and every other reader of
 * `results`, `nextPageToken` or `activeRequest` sees the feed on screen, and the others wait in
 * [parkedFeeds]. Other creators keep [feeds] empty and behave as a single uploads list.
 */
data class CreatorUiState(
    val creator: CreatorProfile? = null,
    /** The feeds the source lists for this account, in tab order; empty for a creator that is not an account. */
    val feeds: List<AccountFeed> = emptyList(),
    val selectedFeed: AccountFeed = AccountFeed.UPLOADS,
    val parkedFeeds: Map<AccountFeed, CreatorFeedState> = emptyMap(),
    /** Header facts, loaded after the first feed starts; null while loading or when the source has none. */
    val details: AccountDetails? = null,
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

    /** Tabs to show: the feeds, minus Favorites once the source reports none (private favorites count 0). */
    val tabs: List<AccountFeed>
        get() = feeds.filter { it != AccountFeed.FAVORITES || details?.favoriteCount != 0 }

    /** The uploads list, which Follow records, whichever feed is selected. */
    val uploads: List<Post>
        get() = if (selectedFeed == AccountFeed.UPLOADS) results else parkedFeeds[AccountFeed.UPLOADS]?.results.orEmpty()

    /** Whether the uploads list is still loading its first page; Follow waits for it. */
    val uploadsRefreshing: Boolean get() = isRefreshing && selectedFeed == AccountFeed.UPLOADS
}

sealed interface CreatorAction {
    /** [feeds] is the source's answer for [creator]; the route owner fills it in, and empty means a plain creator. */
    data class OpenCreator(val creator: CreatorProfile, val feeds: List<AccountFeed> = emptyList()) : CreatorAction
    /** Shows [feed]'s list, parking the current one; a feed that has not loaded starts loading. */
    data class SelectFeed(val feed: AccountFeed) : CreatorAction
    data class DetailsLoaded(val creator: CreatorProfile, val details: AccountDetails) : CreatorAction
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
    /** "More like this" from a post's action menu: opens the related shelf without a like. */
    data class ShowRelatedPosts(val post: Post) : CreatorAction
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
        val feed: AccountFeed = AccountFeed.UPLOADS,
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
    is CreatorAction.OpenCreator -> openCreator(action)
    is CreatorAction.SelectFeed -> selectFeed(action.feed)
    is CreatorAction.DetailsLoaded -> detailsLoaded(action)
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
            "Creator browsing is not available for ${creator?.source?.id?.lowercase()}.",
            CreatorFailureReason.UNSUPPORTED_SOURCE,
            clearResults = true,
        ).copy(parkedFeeds = emptyMap())
    )
    is CreatorAction.OpenResult -> openResult(action)
    is CreatorAction.OpenRelatedResult -> openRelatedResult(action)
    is CreatorAction.RelatedLikeCommitted,
    is CreatorAction.ShowRelatedPosts,
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
    val request = CreatorRequestIdentity(nextRequestGeneration, kind, creatorQueryHash(selected, selectedFeed))
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
        CreatorEffect.LoadPage(request, selected, token, selectedFeed),
    )
}

/** Starts [action]'s creator on its first feed, dropping everything the previous creator left. */
private fun CreatorUiState.openCreator(action: CreatorAction.OpenCreator): CreatorTransition {
    val first = action.feeds.firstOrNull() ?: AccountFeed.UPLOADS
    return copy(
        creator = action.creator,
        feeds = action.feeds,
        selectedFeed = first,
        parkedFeeds = emptyMap(),
        details = null,
        queryHash = creatorQueryHash(action.creator, first),
        results = emptyList(),
    ).beginRequest(CreatorRequestKind.REFRESH)
}

/**
 * Records the details of a still-current creator, and leaves a Favorites tab they just hid for the
 * first remaining one.
 */
private fun CreatorUiState.detailsLoaded(action: CreatorAction.DetailsLoaded): CreatorTransition {
    if (action.creator != creator) return unchanged()
    val updated = copy(details = action.details)
    val fallback = updated.tabs.firstOrNull()
    return if (fallback == null || updated.selectedFeed in updated.tabs) CreatorTransition(updated) else updated.selectFeed(fallback)
}

/**
 * Parks the selected feed, cancelling its request, and shows [feed]'s parked list. A feed whose first
 * load never finished (including one never shown) starts loading again.
 */
private fun CreatorUiState.selectFeed(feed: AccountFeed): CreatorTransition {
    val selected = creator ?: return unchanged()
    if (feed == selectedFeed || feed !in tabs) return unchanged()
    val target = parkedFeeds[feed] ?: CreatorFeedState()
    val switched = copy(
        selectedFeed = feed,
        parkedFeeds = parkedFeeds - feed + (selectedFeed to parkedState()),
        queryHash = creatorQueryHash(selected, feed),
        results = target.results,
        nextPageToken = target.nextPageToken,
        errorMessage = target.errorMessage,
        failureReason = target.failureReason,
        emptyReason = target.emptyReason,
        isRefreshing = false,
        isPaging = false,
        activeRequest = null,
    )
    return if (target.needsLoad) switched.beginRequest(CreatorRequestKind.REFRESH) else CreatorTransition(switched)
}

private fun CreatorUiState.parkedState() = CreatorFeedState(results, nextPageToken, errorMessage, failureReason, emptyReason)

private fun CreatorUiState.openResult(action: CreatorAction.OpenResult): CreatorTransition {
    val selected = creator ?: return unchanged()
    val posts = action.visibleResults ?: results
    if (action.index !in posts.indices) return unchanged()
    return CreatorTransition(this, CreatorEffect.OpenViewer(
        posts = posts.toList(),
        context = ViewerLaunchContext(
            queryHash = queryHash ?: creatorQueryHash(selected, selectedFeed),
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

/** Identifies one list of [creator]; the uploads list keeps the key it has always had. */
fun creatorQueryHash(creator: CreatorProfile, feed: AccountFeed = AccountFeed.UPLOADS): String {
    val key = creator.uploadsQuery ?: creator.profileId ?: creator.profileUrl ?: creator.displayName
    val suffix = if (feed == AccountFeed.UPLOADS) "" else ":${feed.name.lowercase()}"
    return "creator:${creator.source.id}:$key$suffix"
}

private fun CreatorUiState.openRelatedResult(action: CreatorAction.OpenRelatedResult): CreatorTransition {
    val request = relatedPosts.requestOrNull ?: return unchanged()
    if (action.index !in action.visibleResults.indices) return unchanged()
    return CreatorTransition(this, CreatorEffect.OpenViewer(
        posts = action.visibleResults.toList(),
        context = ViewerLaunchContext(
            queryHash = "related:${request.seed.id.source.id}:${request.seed.id.sourcePostId}",
            startIndex = action.index,
            streamSource = ViewerStreamSource.RELATED,
            scrollOffsetHint = 0,
        ),
        liveSearchBinding = false,
    ))
}
