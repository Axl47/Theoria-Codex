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

data class TrailPage(val posts: List<Post>, val canLoadMore: Boolean)

fun interface TrailPager {
    suspend fun next(): TrailPage
}

class TrailSpec(val label: String, val query: Query?, val pager: TrailPager)

suspend fun TrailPager.nextOrNull(): TrailPage? = try {
    next()
} catch (error: Exception) {
    if (error is CancellationException) currentCoroutineContext().ensureActive()
    null
}

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
                else TriageFeedSnapshot(visible(page.posts), page.canLoadMore, loaded = maxOf(page.posts.size, current.loaded + 1))
            }
        }
    }
}
