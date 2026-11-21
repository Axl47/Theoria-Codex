package com.theoriacodex.app.search.state

/** Initial layout and programmatic restoration must not replace the saved anchor. */
val SearchRestorationUiState.allowsScrollPersistence: Boolean
    get() = this is SearchRestorationUiState.Restored && scrollState == null

/** The id of a request still owed a move to a saved place; a deliberate scroll or open supersedes it. */
val SearchRestorationUiState.openScrollRequestId: Long?
    get() = (this as? SearchRestorationUiState.Restored)?.takeIf { it.scrollState != null }?.scrollRequestId

/** An absent anchor is only final after the provider continuation is exhausted. */
val SearchUiState.canApplyScrollRestoration: Boolean
    get() {
        val request = restoration as? SearchRestorationUiState.Restored ?: return false
        val target = request.scrollState ?: return false
        if (!content.hasExecutedSearch || loading || loadingMore) return false
        return request.resetToTop || (content.error == null &&
            (target.firstVisibleItemIndex < content.results.size || !content.canLoadMore))
    }

/** What the results grid should do about an open scroll request. */
sealed interface ScrollStep {
    data object Wait : ScrollStep
    data class Jump(val requestId: Long, val anchorIndex: Int, val offsetPx: Int) : ScrollStep
    data class Finish(val requestId: Long) : ScrollStep
}

/**
 * Decides the grid's next step for the open scroll request; the first matching rule wins:
 * 1. no open request: wait;
 * 2. no search has finished, or one is loading: wait;
 * 3. a reset to top skips rules 4 to 6;
 * 4. a failure is showing: wait;
 * 5. the anchor's page is not loaded and more pages can load: wait;
 * 6. the anchor's visibility is undecided: wait;
 * 7. nothing is presented: finish without moving;
 * 8. a keep-live request whose grid was already showing posts when the request arrived: finish without
 *    moving, since that grid kept its place and a jump lines up every column at the target and the grid
 *    later reshuffles;
 * 9. otherwise jump to the anchor, its offset floored at 0.
 * Only rules 1 to 6 wait, so whether the step waits never depends on [presentedCount] or
 * [gridWasShowingPosts].
 */
fun SearchUiState.scrollStep(
    anchorVisibilityDecided: Boolean,
    presentedCount: Int,
    gridWasShowingPosts: Boolean,
): ScrollStep {
    val request = restoration as? SearchRestorationUiState.Restored ?: return ScrollStep.Wait
    val target = request.scrollState ?: return ScrollStep.Wait
    if (!canApplyScrollRestoration) return ScrollStep.Wait
    if (!request.resetToTop && !anchorVisibilityDecided) return ScrollStep.Wait
    if (presentedCount == 0 || (request.keepLiveGrid && gridWasShowingPosts)) {
        return ScrollStep.Finish(request.scrollRequestId)
    }
    return ScrollStep.Jump(
        request.scrollRequestId, target.firstVisibleItemIndex, target.firstVisibleItemOffsetPx.coerceAtLeast(0),
    )
}

/**
 * Accepts a jump that reached its target, left room to scroll forward, or has no further pages to wait for.
 * Otherwise the grid stopped at the list end above the target: wait, since the list end asks for the next
 * page and its posts retry the jump.
 */
fun SearchUiState.scrollStepAfterJump(reachedTarget: Boolean, canScrollForward: Boolean): ScrollStep {
    val request = restoration as? SearchRestorationUiState.Restored ?: return ScrollStep.Wait
    return if (reachedTarget || canScrollForward || !content.canLoadMore) {
        ScrollStep.Finish(request.scrollRequestId)
    } else {
        ScrollStep.Wait
    }
}
