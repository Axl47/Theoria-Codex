package com.theoriacodex.app.feed

data class FeedPageDemandInput(
    val contextKey: String,
    val completedGeneration: Long,
    val canonicalCount: Int,
    val visibleCount: Int,
    val canLoadMore: Boolean,
    val hasLocalFilters: Boolean = false,
    val ready: Boolean = true,
    val refreshing: Boolean = false,
    val paging: Boolean = false,
    val failed: Boolean = false,
    val resolvingDurations: Boolean = false,
    val lastVisibleCanonicalIndex: Int? = null,
    val atVisibleEnd: Boolean = false,
    val scrolling: Boolean = false,
)

data class FeedPageDemandResult(
    val loadNextPage: Boolean = false,
    val showContinue: Boolean = false,
)

/** Bounds page draining between user demands, independently of provider continuation and visible counts. */
class FeedPageDemand(
    private val automaticPageLimit: Int = 3,
    private val minimumVisiblePosts: Int = 12,
) {
    init {
        require(automaticPageLimit > 0)
        require(minimumVisiblePosts > 0)
    }

    private var contextKey: String? = null
    private var requestedGeneration: Long? = null
    private var automaticPages = 0
    private var failureBlocked = false
    private var wasRefreshing = false
    private var wasScrolling = false
    private var previousScrollAnchor: Int? = null

    fun continueLoading() {
        automaticPages = 0
    }

    fun update(input: FeedPageDemandInput): FeedPageDemandResult {
        reconcileContextAndViewport(input)

        if (input.failed) failureBlocked = true
        if (!input.ready || input.refreshing || input.paging || failureBlocked) return FeedPageDemandResult()
        // A generation changes even when a provider page adds no canonical posts. Repeated viewport
        // callbacks cannot acknowledge a request; only its route owner's settled generation can.
        requestedGeneration?.let { requested ->
            if (requested == input.completedGeneration) return FeedPageDemandResult()
            requestedGeneration = null
        }
        if (!input.canLoadMore || input.resolvingDurations) return FeedPageDemandResult()

        val nearCanonicalEnd = input.lastVisibleCanonicalIndex?.let { index ->
            input.canonicalCount > 0 && index >= ((input.canonicalCount - 1) * PREFETCH_RATIO).toInt()
        } == true
        val needsVisiblePosts = input.canonicalCount == 0 ||
            (input.hasLocalFilters && (input.visibleCount < minimumVisiblePosts || input.atVisibleEnd))
        if (!nearCanonicalEnd && !needsVisiblePosts) return FeedPageDemandResult()
        if (automaticPages >= automaticPageLimit) return FeedPageDemandResult(showContinue = true)

        automaticPages += 1
        requestedGeneration = input.completedGeneration
        return FeedPageDemandResult(loadNextPage = true)
    }
    private fun reconcileContextAndViewport(input: FeedPageDemandInput) {
        if (input.contextKey != contextKey || (input.refreshing && !wasRefreshing)) {
            contextKey = input.contextKey
            requestedGeneration = null
            automaticPages = 0
            failureBlocked = false
        }
        val advancedWhileScrolling = input.scrolling && (
            !wasScrolling || input.lastVisibleCanonicalIndex?.let { anchor ->
                previousScrollAnchor?.let { anchor > it } ?: false
            } == true
        )
        if (advancedWhileScrolling) automaticPages = 0
        wasRefreshing = input.refreshing
        wasScrolling = input.scrolling
        previousScrollAnchor = input.lastVisibleCanonicalIndex

    }

}

private const val PREFETCH_RATIO = 0.7
