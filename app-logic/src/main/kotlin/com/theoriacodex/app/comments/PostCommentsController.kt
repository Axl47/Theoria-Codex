package com.theoriacodex.app.comments

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Viewer's comment UI can ask for; a post's state is read from [PostCommentsController.state]. */
interface PostCommentsActions {
    /** Loads the next page of the post's comments; also retries a page that failed. */
    fun loadMore(post: Post)

    /** Opens or closes the replies under [comment], loading their first page on first opening. */
    fun toggleReplies(post: Post, comment: PostComment)

    /** Loads the next page of replies under [comment]; also retries a page that failed. */
    fun loadMoreReplies(post: Post, comment: PostComment)

    companion object {
        val None = object : PostCommentsActions {
            override fun loadMore(post: Post) = Unit
            override fun toggleReplies(post: Post, comment: PostComment) = Unit
            override fun loadMoreReplies(post: Post, comment: PostComment) = Unit
        }
    }
}

/**
 * Owns the comments of the posts one Viewer session has shown, so swiping back to a post is instant. The
 * current post's first page loads in the background ([show]); everything else waits for a tap. Pages come
 * through [loader], which caches them, so this holds only what the screen draws.
 *
 * [beforeFirstFetch] runs before each background first-page request, letting the Viewer keep the shown
 * media's own loading ahead of this speculative request.
 */
class PostCommentsController(
    private val loader: CommentsLoading,
    private val scope: CoroutineScope,
    private val beforeFirstFetch: suspend () -> Unit = {},
) : PostCommentsActions {
    private val lock = Any()
    private val jobs = mutableSetOf<Job>()
    private val mutableState = MutableStateFlow<Map<PostId, PostCommentsState>>(emptyMap())
    val state: StateFlow<Map<PostId, PostCommentsState>> = mutableState.asStateFlow()

    /** Starts the post's first page unless it already started or [shouldFetchFirstPage] says not to. */
    fun show(post: Post) {
        if (post.id in mutableState.value || !loader.shouldFetchFirstPage(post)) return
        synchronized(lock) {
            if (post.id in mutableState.value) return
            mutableState.value += (post.id to PostCommentsState())
        }
        launch { beforeFirstFetch(); fetchTop(post) }
    }

    override fun loadMore(post: Post) {
        val token = synchronized(lock) {
            val top = mutableState.value[post.id]?.top ?: return
            if (top.status == CommentLoadStatus.Loading || top.nextPageToken == null && top.hasLoaded) return
            update(post.id) { it.copy(top = top.loading()) }
            top.nextPageToken
        }
        launch { fetchTop(post, token) }
    }

    override fun toggleReplies(post: Post, comment: PostComment) {
        val needsLoad = synchronized(lock) {
            val current = mutableState.value[post.id] ?: return
            val opening = comment.id !in current.expandedReplies
            update(post.id) {
                it.copy(expandedReplies = if (opening) it.expandedReplies + comment.id else it.expandedReplies - comment.id)
            }
            opening && current.replies[comment.id] == null
        }
        if (needsLoad) loadMoreReplies(post, comment)
    }

    override fun loadMoreReplies(post: Post, comment: PostComment) {
        val token = synchronized(lock) {
            val replies = mutableState.value[post.id]?.replies?.get(comment.id)
            if (replies?.status == CommentLoadStatus.Loading || replies != null && replies.hasLoaded && replies.nextPageToken == null) return
            update(post.id) { it.withReplies(comment.id) { list -> list.loading() } }
            replies?.nextPageToken
        }
        launch { fetch(post, token, comment) }
    }

    /** Forgets every post and stops the requests in flight, for a Viewer session that was replaced. */
    fun reset() {
        synchronized(lock) {
            jobs.toList().forEach(Job::cancel)
            jobs.clear()
            mutableState.value = emptyMap()
        }
    }

    private fun launch(block: suspend () -> Unit) {
        val job = scope.launch { block() }
        synchronized(lock) { jobs += job }
        job.invokeOnCompletion { synchronized(lock) { jobs -= job } }
    }

    private suspend fun fetchTop(post: Post, token: String? = null) = fetch(post, token, parent = null)

    /** Applies one page, or its failure, to the list it belongs to. */
    private suspend fun fetch(post: Post, token: String?, parent: PostComment?) {
        val result = runCatchingPreservingCancellation { loader.load(post, token, parent) }
        synchronized(lock) {
            if (post.id !in mutableState.value) return
            update(post.id) { state ->
                if (parent == null) {
                    state.copy(top = result.fold(state.top::withPage) { state.top.failed() })
                } else {
                    state.withReplies(parent.id) { list -> result.fold(list::withPage) { list.failed() } }
                }
            }
        }
    }

    private fun update(id: PostId, transform: (PostCommentsState) -> PostCommentsState) {
        mutableState.value = mutableState.value.let { map -> map[id]?.let { map + (id to transform(it)) } ?: map }
    }
}
