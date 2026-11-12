package com.theoriacodex.app.comments

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CommentTarget
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.commentLink
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

    /** The link that opens [target] on [post], or null when the post has no page URL to build it on. */
    fun linkTo(post: Post, target: CommentTarget): String?

    /** The list scrolled to the post's focused comment; clears [PostCommentsState.focusKey] so it acts once. */
    fun focusShown(post: Post)

    companion object {
        val None = object : PostCommentsActions {
            override fun loadMore(post: Post) = Unit
            override fun toggleReplies(post: Post, comment: PostComment) = Unit
            override fun loadMoreReplies(post: Post, comment: PostComment) = Unit
            override fun linkTo(post: Post, target: CommentTarget): String? = null
            override fun focusShown(post: Post) = Unit
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

    override fun linkTo(post: Post, target: CommentTarget): String? {
        val pageUrl = post.pageUrl?.takeIf(String::isNotBlank) ?: return null
        return commentLink(pageUrl, target, loader.nativeCommentAnchor(post.id.source, target.id))
    }

    override fun focusShown(post: Post) {
        synchronized(lock) { update(post.id) { it.copy(focusKey = null) } }
    }

    /**
     * Looks for the comment [target] names, so the sheet can scroll to it: pages the post's comments until it
     * (or, for a reply, its parent) shows up, then opens the parent's replies and pages those. Both searches stop
     * after [MAX_FOCUS_PAGES] requests. On success [PostCommentsState.focusKey] names the item; otherwise nothing
     * changes, and the post's comments simply open as usual. Like the first page, it waits for [beforeFirstFetch].
     */
    fun focus(post: Post, target: CommentTarget) {
        if (!loader.shouldFetchFirstPage(post)) return
        synchronized(lock) {
            if (post.id !in mutableState.value) mutableState.value += (post.id to PostCommentsState())
        }
        launch { beforeFirstFetch(); findFocus(post, target) }
    }

    private suspend fun findFocus(post: Post, target: CommentTarget) {
        val top = pageUntilFound(post, parent = null, id = target.parentId ?: target.id) ?: return
        if (target.parentId == null) {
            synchronized(lock) { update(post.id) { it.copy(focusKey = commentItemKey(top.id)) } }
            return
        }
        pageUntilFound(post, parent = top, id = target.id) ?: return
        synchronized(lock) {
            update(post.id) {
                it.copy(expandedReplies = it.expandedReplies + top.id, focusKey = replyItemKey(top.id, target.id))
            }
        }
    }

    /** Pages the replies under [parent] (the post's own comments when null) until one has [id]; null if none does, or a page fails. */
    private suspend fun pageUntilFound(post: Post, parent: PostComment?, id: String): PostComment? {
        var requests = 0
        while (true) {
            val list = listFor(post.id, parent) ?: return null
            list.comments.firstOrNull { it.id == id }?.let { return it }
            if (requests == MAX_FOCUS_PAGES || list.hasLoaded && list.nextPageToken == null) return null
            synchronized(lock) {
                update(post.id) { state ->
                    if (parent == null) state.copy(top = state.top.loading()) else state.withReplies(parent.id, CommentList::loading)
                }
            }
            fetch(post, list.nextPageToken, parent)
            requests++
            if (listFor(post.id, parent)?.status == CommentLoadStatus.Failed) return null
        }
    }

    private fun listFor(id: PostId, parent: PostComment?): CommentList? =
        mutableState.value[id]?.let { state -> if (parent == null) state.top else state.replies[parent.id] ?: CommentList() }

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

    private companion object {
        /** How many pages [focus] requests per list before giving up on a comment. */
        const val MAX_FOCUS_PAGES = 10
    }
}
