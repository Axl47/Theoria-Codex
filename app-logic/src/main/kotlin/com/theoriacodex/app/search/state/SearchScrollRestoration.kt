package com.theoriacodex.app.search.state

val SearchRestorationUiState.allowsScrollPersistence: Boolean
    get() = this is SearchRestorationUiState.Restored && scrollState == null

val SearchRestorationUiState.openScrollRequestId: Long?
    get() = (this as? SearchRestorationUiState.Restored)?.takeIf { it.scrollState != null }?.scrollRequestId

val SearchUiState.canApplyScrollRestoration: Boolean
    get() {
        val request = restoration as? SearchRestorationUiState.Restored ?: return false
        val target = request.scrollState ?: return false
        if (!content.hasExecutedSearch || loading || loadingMore) return false
        return request.resetToTop || (content.error == null &&
            (target.firstVisibleItemIndex < content.results.size || !content.canLoadMore))
    }

sealed interface ScrollStep {
    data object Wait : ScrollStep
    data class Jump(val requestId: Long, val anchorIndex: Int, val offsetPx: Int) : ScrollStep
    data class Finish(val requestId: Long) : ScrollStep
}

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

fun SearchUiState.scrollStepAfterJump(reachedTarget: Boolean, canScrollForward: Boolean): ScrollStep {
    val request = restoration as? SearchRestorationUiState.Restored ?: return ScrollStep.Wait
    return if (reachedTarget || canScrollForward || !content.canLoadMore) {
        ScrollStep.Finish(request.scrollRequestId)
    } else {
        ScrollStep.Wait
    }
}
