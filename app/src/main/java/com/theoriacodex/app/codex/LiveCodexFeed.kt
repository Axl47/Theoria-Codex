package com.theoriacodex.app.codex

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal fun sortLiveCodexPosts(posts: List<Post>, sort: CodexSortMode): List<Post> = posts.sortedWith(liveCodexOrder(sort))

private fun liveCodexOrder(sort: CodexSortMode): Comparator<Post> = when (sort) {
    CodexSortMode.NEWEST_SAVED -> compareByDescending { it.createdAtEpochMs }
    CodexSortMode.OLDEST_SAVED -> compareBy { it.createdAtEpochMs }
    CodexSortMode.BY_SOURCE -> compareBy { it.id.source.ordinal }
}

internal data class LiveCodexFeedState(
    val posts: List<Post> = emptyList(),
    val loading: Boolean = false,
    val canLoadMore: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val generation: Long = 0,
)

internal interface LiveCodexFeedOwner {
    val state: StateFlow<LiveCodexFeedState>
    fun refresh()
    fun loadMore()
    fun retry()
}

internal interface LiveCodexBranch {
    val id: String
    val posts: List<Post>
    val canLoadMore: Boolean
    val error: String?
}

/** Shared virtual-collection scheduling: stable branch order, bounded overlap and stale-result rejection. */
internal class LiveCodexFeedController<B : LiveCodexBranch>(
    private val scope: CoroutineScope,
    private val fetch: suspend (B, Boolean) -> B,
    private val cacheCover: suspend (Post) -> Unit,
) {
    private val mutableState = MutableStateFlow(LiveCodexFeedState())
    val state = mutableState.asStateFlow()
    private var branches = linkedMapOf<String, B>()
    private var job: Job? = null
    private var generation = 0L
    private var coverPostId: PostId? = null
    private var coverSort = CodexSortMode.NEWEST_SAVED

    fun updateCoverSort(sort: CodexSortMode) {
        if (coverSort == sort) return
        coverSort = sort
        scope.launch { cacheFirstPost(generation, root = true) }
    }

    fun reset(initial: List<B>) {
        job?.cancel()
        generation++
        coverPostId = null
        branches = initial.associateByTo(linkedMapOf()) { it.id }
        mutableState.value = LiveCodexFeedState(generation = generation)
        load(branches.keys.toList(), retry = false, cacheInitialCover = true)
    }

    fun loadMore() {
        if (!state.value.loading) load(branches.values.filter { it.canLoadMore }.map { it.id }, retry = false)
    }

    fun retry() {
        if (!state.value.loading) load(branches.values.filter { it.error != null }.map { it.id }, retry = true)
    }

    private fun load(ids: List<String>, retry: Boolean, cacheInitialCover: Boolean = false) {
        if (ids.isEmpty()) return
        val admitted = ++generation
        mutableState.value = state.value.copy(loading = true, generation = generation)
        job = scope.launch {
            try {
                ids.mapConcurrent(concurrency = 2) { id ->
                    val branch = branches[id] ?: return@mapConcurrent
                    val result = fetch(branch, retry)
                    if (generation == admitted) {
                        branches[id] = result
                        publish(loading = true)
                        cacheFirstPost(admitted, cacheInitialCover)
                    }
                }
            } finally {
                if (generation == admitted) {
                    publish(loading = false)
                }
            }
        }
    }

    private suspend fun cacheFirstPost(admitted: Long, root: Boolean) {
        if (generation != admitted) return
        val post = state.value.posts.minWithOrNull(liveCodexOrder(coverSort)) ?: return
        if (post.id == coverPostId || (!root && coverPostId != null)) return
        coverPostId = post.id
        val result = runCatchingPreservingCancellation { cacheCover(post) }
        if (result.isFailure && generation == admitted && coverPostId == post.id) coverPostId = null
    }

    private fun publish(loading: Boolean) {
        mutableState.value = LiveCodexFeedState(
            posts = branches.values.flatMap { it.posts }.distinctBy(Post::id),
            loading = loading,
            canLoadMore = branches.values.any { it.canLoadMore },
            errors = branches.values.mapNotNull { branch -> branch.error?.let { branch.id to it } }.toMap(),
            generation = generation,
        )
    }
}
