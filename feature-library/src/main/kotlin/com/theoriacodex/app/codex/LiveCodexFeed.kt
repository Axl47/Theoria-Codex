package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.UnsupportedRelatedPostsLoader
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.distinctByFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun sortLiveCodexPosts(
    posts: List<Post>,
    sort: CodexSortMode,
    usage: Map<String, CodexUsage> = emptyMap(),
    durations: Map<PostId, Long> = emptyMap(),
): List<Post> = posts.sortedWith(liveCodexOrder(sort, usage, durations))

/** Live posts were never saved, so their post date stands in for save time. */
private fun liveCodexOrder(sort: CodexSortMode, usage: Map<String, CodexUsage>, durations: Map<PostId, Long>): Comparator<Post> {
    val date: Comparator<Post> = if (sort == CodexSortMode.OLDEST_SAVED) {
        compareBy { it.createdAtEpochMs }
    } else {
        compareByDescending { it.createdAtEpochMs }
    }
    return codexPostRefinement(sort, usage, durations)?.then(date) ?: date
}

data class LiveCodexFeedState(
    val posts: List<Post> = emptyList(),
    val loading: Boolean = false,
    val canLoadMore: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val generation: Long = 0,
    val loadedAtEpochMs: Long? = null,
)

fun LiveCodexFeedState.predates(marks: Collection<NewPostMarks>): Boolean {
    val loadedAt = loadedAtEpochMs ?: return false
    return marks.any { it.newPostCount > 0 && (it.checkedAtEpochMs ?: 0) > loadedAt }
}

@Composable
fun rememberNewPostsReload(owner: LiveCodexFeedOwner, feed: LiveCodexFeedState, marks: Collection<NewPostMarks>): Boolean {
    val opened = remember(owner) { marks }
    val stale = feed.predates(opened)
    LaunchedEffect(owner, stale) { if (stale) owner.refresh() }
    return stale
}

interface LiveCodexFeedOwner {
    val state: StateFlow<LiveCodexFeedState>
    val relatedState: StateFlow<RelatedPostsUiState>
    /** The feed's related shelf, anchored in the order the grid shows ([sortLiveCodexPosts]). */
    val related: RelatedPostsRouteController
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

/**
 * Shared virtual-collection scheduling: stable branch order, bounded overlap and stale-result rejection.
 * Also owns the collection's related shelf, which a reset or re-sort clears because its anchor moves.
 */
internal class LiveCodexFeedController<B : LiveCodexBranch>(
    private val scope: CoroutineScope,
    private val fetch: suspend (B, Boolean) -> B,
    private val cacheCover: suspend (Post) -> Unit,
    relatedPostsLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
) {
    private val mutableState = MutableStateFlow(LiveCodexFeedState())
    val state = mutableState.asStateFlow()
    private val branches = linkedMapOf<String, B>()
    private var job: Job? = null
    private var generation = 0L
    private var coverPostId: PostId? = null
    private var loadedAt: Long? = null
    private var coverSort = CodexSortMode.NEWEST_SAVED
    private var coverUsage = emptyMap<String, CodexUsage>()
    private var coverDurations = emptyMap<PostId, Long>()
    private val mutableRelated = MutableStateFlow<RelatedPostsUiState>(RelatedPostsUiState.Idle)
    val relatedState = mutableRelated.asStateFlow()
    // Live collections never promote shelf posts: their posts come only from their branches.
    val related = RelatedPostsRouteController(
        scope = scope,
        loader = relatedPostsLoader,
        currentState = { mutableRelated.value },
        canonicalPosts = { sortLiveCodexPosts(state.value.posts, coverSort, coverUsage, coverDurations) },
        updateState = { mutableRelated.value = it },
    )

    /** Newly resolved [durations] refine the order quietly; only a new sort or usage clears the related shelf. */
    fun updateCoverSort(
        sort: CodexSortMode,
        usage: Map<String, CodexUsage> = emptyMap(),
        durations: Map<PostId, Long> = emptyMap(),
    ) {
        coverDurations = durations
        if (coverSort == sort && coverUsage == usage) return
        coverSort = sort
        coverUsage = usage
        related.clear("live codex re-sorted")
        scope.launch { cacheFirstPost(generation, root = true) }
    }

    fun reset(initial: List<B>) {
        job?.cancel()
        related.clear("live codex reset")
        generation++
        coverPostId = null
        loadedAt = System.currentTimeMillis()
        branches.clear()
        initial.associateByTo(branches) { it.id }
        mutableState.value = LiveCodexFeedState(generation = generation, loadedAtEpochMs = loadedAt)
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
        val post = state.value.posts.minWithOrNull(liveCodexOrder(coverSort, coverUsage, coverDurations)) ?: return
        if (post.id == coverPostId || (!root && coverPostId != null)) return
        coverPostId = post.id
        val result = runCatchingPreservingCancellation { cacheCover(post) }
        if (result.isFailure && generation == admitted && coverPostId == post.id) coverPostId = null
    }

    private fun publish(loading: Boolean) {
        mutableState.value = LiveCodexFeedState(
            posts = branches.values.flatMap { it.posts }.distinctByFile(),
            loading = loading,
            canLoadMore = branches.values.any { it.canLoadMore },
            errors = branches.values.mapNotNull { branch -> branch.error?.let { branch.id to it } }.toMap(),
            generation = generation,
            loadedAtEpochMs = loadedAt,
        )
    }
}
