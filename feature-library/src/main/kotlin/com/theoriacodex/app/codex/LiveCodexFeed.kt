package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var attempted by remember(owner) { mutableStateOf(false) }
    val pending = !attempted && feed.predates(opened)
    LaunchedEffect(owner, pending) {
        if (!pending) return@LaunchedEffect
        attempted = true
        owner.refresh()
    }
    return pending
}

interface LiveCodexFeedOwner {
    val state: StateFlow<LiveCodexFeedState>
    val relatedState: StateFlow<RelatedPostsUiState>
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
    private var keptBranches: Map<String, B>? = null
    private var keptLoadedAt: Long? = null
    private var coverSort = CodexSortMode.NEWEST_SAVED
    private var coverUsage = emptyMap<String, CodexUsage>()
    private var coverDurations = emptyMap<PostId, Long>()
    private val mutableRelated = MutableStateFlow<RelatedPostsUiState>(RelatedPostsUiState.Idle)
    val relatedState = mutableRelated.asStateFlow()
    val related = RelatedPostsRouteController(
        scope = scope,
        loader = relatedPostsLoader,
        currentState = { mutableRelated.value },
        canonicalPosts = { sortLiveCodexPosts(state.value.posts, coverSort, coverUsage, coverDurations) },
        updateState = { mutableRelated.value = it },
    )

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
        keptBranches = null
        loadedAt = System.currentTimeMillis()
        branches.clear()
        initial.associateByTo(branches) { it.id }
        mutableState.value = LiveCodexFeedState(generation = generation, loadedAtEpochMs = loadedAt)
        load(branches.keys.toList(), retry = false, cacheInitialCover = true)
    }

    fun reload(initial: List<B>) {
        if (keptBranches != null || initial.isEmpty()) return
        keptBranches = LinkedHashMap(branches)
        keptLoadedAt = loadedAt
        job?.cancel()
        related.clear("live codex reloaded")
        generation++
        loadedAt = System.currentTimeMillis()
        branches.clear()
        initial.associateByTo(branches) { it.id }
        load(branches.keys.toList(), retry = false, reloading = true)
    }

    fun loadMore() {
        if (!state.value.loading) load(branches.values.filter { it.canLoadMore }.map { it.id }, retry = false)
    }

    fun retry() {
        if (!state.value.loading) load(branches.values.filter { it.error != null }.map { it.id }, retry = true)
    }

    private fun load(ids: List<String>, retry: Boolean, cacheInitialCover: Boolean = false, reloading: Boolean = false) {
        if (ids.isEmpty()) return
        val admitted = ++generation
        mutableState.value = state.value.copy(loading = true, generation = generation)
        job = scope.launch {
            try {
                ids.mapConcurrent(concurrency = 2) { id -> fetchBranch(id, retry, admitted, publishEach = !reloading, cacheInitialCover) }
                if (reloading && generation == admitted) finishReload(admitted)
            } finally {
                if (generation == admitted) {
                    if (reloading) keptBranches = null
                    publish(loading = false)
                }
            }
        }
    }

    private suspend fun fetchBranch(id: String, retry: Boolean, admitted: Long, publishEach: Boolean, cacheInitialCover: Boolean) {
        val branch = branches[id] ?: return
        val result = fetch(branch, retry)
        if (generation != admitted) return
        branches[id] = result
        if (!publishEach) return
        publish(loading = true)
        cacheFirstPost(admitted, cacheInitialCover)
    }

    private suspend fun finishReload(admitted: Long) {
        val kept = keptBranches.orEmpty()
        val failed = branches.filter { (id, branch) -> branch.posts.isEmpty() && branch.error != null && id in kept }.keys
        failed.forEach { branches[it] = kept.getValue(it) }
        if (failed.isNotEmpty()) loadedAt = keptLoadedAt
        publish(loading = true)
        cacheFirstPost(admitted, root = true)
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
