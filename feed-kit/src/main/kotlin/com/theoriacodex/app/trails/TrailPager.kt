package com.theoriacodex.app.trails

import com.theoriacodex.app.triage.TriageFeed
import com.theoriacodex.app.triage.TriageFeedSnapshot
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A trail's posts so far, and whether it can give more. */
data class TrailPage(val posts: List<Post>, val canLoadMore: Boolean)

/** A trail's source of posts: each call loads one more page and returns everything so far. Throws when a page fails. */
fun interface TrailPager {
    suspend fun next(): TrailPage
}

/** What a trail shows: its crumb [label], the [query] it searches (tag trails only) and the [pager] that feeds it. */
class TrailSpec(val label: String, val query: Query?, val pager: TrailPager)

/**
 * The next page, or null when it failed. A cancellation that is not the caller's own (the pager's timeout) is a failed
 * page too.
 */
suspend fun TrailPager.nextOrNull(): TrailPage? = try {
    next()
} catch (error: Exception) {
    if (error is CancellationException) currentCoroutineContext().ensureActive()
    null
}

/**
 * This trail as a deck's feed. It loads its first page when first collected and each later one, in [scope], when the
 * deck asks; [visible] picks the posts the deck shows. A page still counts as loaded when [visible] keeps none of its
 * posts, or when it adds none at all (every one a repeat), so the deck asks for the next one. A failed page keeps the posts loaded until [TriageFeed.retryPage].
 */
fun TrailPager.asTriageFeed(scope: CoroutineScope, visible: (List<Post>) -> List<Post> = { it }): TriageFeed =
    TrailTriageFeed(this, scope, visible)

private class TrailTriageFeed(
    private val pager: TrailPager,
    private val scope: CoroutineScope,
    private val visible: (List<Post>) -> List<Post>,
) : TriageFeed {
    private val state = MutableStateFlow(TriageFeedSnapshot(emptyList(), canLoadMore = true, loadingMore = true))
    private var started = false

    override val snapshots: Flow<TriageFeedSnapshot> = state.onStart {
        if (!started) {
            started = true
            load()
        }
    }

    override fun loadNextPage() {
        val current = state.value
        if (started && current.canLoadMore && !current.loadingMore && !current.pageFailed) load()
    }

    override fun retryPage() {
        val current = state.value
        if (started && current.pageFailed && !current.loadingMore) load()
    }

    private fun load() {
        state.update { it.copy(loadingMore = true, pageFailed = false) }
        scope.launch {
            val page = pager.nextOrNull()
            state.update { current ->
                if (page == null) current.copy(loadingMore = false, pageFailed = true)
                // A page that adds no posts still moves [TriageFeedSnapshot.loaded] on, which is how the deck sees it arrive.
                else TriageFeedSnapshot(visible(page.posts), page.canLoadMore, loaded = maxOf(page.posts.size, current.loaded + 1))
            }
        }
    }
}
