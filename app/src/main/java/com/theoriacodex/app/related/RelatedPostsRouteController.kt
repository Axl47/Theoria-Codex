package com.theoriacodex.app.related

import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Shared navigation-owner request lifecycle; routes provide only their current canonical posts. */
internal class RelatedPostsRouteController(
    private val scope: CoroutineScope,
    private val loader: RelatedPostsLoading,
    private val currentState: () -> RelatedPostsUiState,
    private val canonicalPosts: () -> List<Post>,
    private val promotePost: (post: Post, insertionIndex: Int) -> Unit = { _, _ -> },
    private val updateState: (RelatedPostsUiState) -> Unit,
    private val requestTimeoutMs: Long = DEFAULT_RELATED_POSTS_TIMEOUT_MS,
) {
    init {
        require(requestTimeoutMs > 0L) { "Related-post timeout must be positive" }
    }

    private var job: Job? = null
    private var nextGeneration = 0L

    fun onLikeCommitted(post: Post, outcome: LikeToggleOutcome) {
        if (outcome == LikeToggleOutcome.UNLIKED) {
            val updated = currentState().clearIfSeed(post.id)
            if (updated !== currentState()) {
                cancelJob("Related-post seed unliked")
                updateState(updated)
            }
            return
        }
        if (!loader.supports(post.id.source)) return
        val current = currentState()
        val posts = canonicalPosts()
        val canonicalIndex = posts.indexOfFirst { candidate -> candidate.id == post.id }
        if (canonicalIndex >= 0) {
            launch(post, canonicalIndex)
            return
        }
        if (current.loadedPosts.none { candidate -> candidate.id == post.id }) return

        val insertionIndex = (current.requestOrNull?.anchorCanonicalIndex ?: return) + 1
        val retainedPages = current.withoutPost(post.id).availablePages
        promotePost(post, insertionIndex)
        launch(post, insertionIndex, retainedPages)
    }

    fun retry() {
        val request = currentState().requestOrNull ?: return
        launch(request.seed, request.anchorCanonicalIndex, currentState().availablePages)
    }

    fun showPreviousPage() {
        val current = currentState()
        updateState(current.selectPage(current.currentPageIndex - 1))
    }

    fun showNextPage() {
        val current = currentState()
        updateState(current.selectPage(current.currentPageIndex + 1))
    }

    fun clear() {
        cancelJob("Related-post shelf cleared")
        if (currentState() != RelatedPostsUiState.Idle) updateState(RelatedPostsUiState.Idle)
    }

    private fun launch(
        seed: Post,
        anchorCanonicalIndex: Int,
        retainedPages: List<RelatedPostsPage> = emptyList(),
    ) {
        cancelJob("Related-post request replaced")
        val loading = currentState().begin(
            seed = seed,
            anchorCanonicalIndex = anchorCanonicalIndex,
            generation = ++nextGeneration,
            retainedPages = retainedPages,
        )
        updateState(loading)
        job = scope.launch {
            try {
                val incoming = withTimeout(requestTimeoutMs) { loader.load(seed.id) }
                updateState(
                    currentState().complete(
                        request = loading.request,
                        incoming = incoming,
                        canonicalPostIds = canonicalPosts().mapTo(mutableSetOf(), Post::id),
                    )
                )
            } catch (_: TimeoutCancellationException) {
                updateState(currentState().fail(loading.request, "Related posts timed out. Try again."))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                updateState(currentState().fail(loading.request, error.message.orEmpty()))
            }
        }
    }

    private fun cancelJob(reason: String) {
        job?.cancel(CancellationException(reason))
        job = null
    }
}

internal const val DEFAULT_RELATED_POSTS_TIMEOUT_MS = 15_000L
