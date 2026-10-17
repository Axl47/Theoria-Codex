package com.theoriacodex.app.search.state

/** Initial layout and programmatic restoration must not replace the saved anchor. */
val SearchRestorationUiState.allowsScrollPersistence: Boolean
    get() = this is SearchRestorationUiState.Restored && scrollState == null

/** An absent anchor is only final after the provider continuation is exhausted. */
val SearchUiState.canApplyScrollRestoration: Boolean
    get() {
        val request = restoration as? SearchRestorationUiState.Restored ?: return false
        val target = request.scrollState ?: return false
        if (!content.hasExecutedSearch || loading || loadingMore) return false
        return request.resetToTop || (content.error == null &&
            (target.firstVisibleItemIndex < content.results.size || !content.canLoadMore))
    }
