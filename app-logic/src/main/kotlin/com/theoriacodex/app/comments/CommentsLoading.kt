package com.theoriacodex.app.comments

import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey

/** Loads read-only comment pages for the Viewer. */
interface CommentsLoading {
    /** Whether [source] can list comments at all. */
    fun supports(source: SourceKey): Boolean

    /** One page of [post]'s comments, or of the replies to [parent]. Throws when the source fails. */
    suspend fun load(post: Post, pageToken: String?, parent: PostComment? = null): CommentPage
}

/**
 * Whether to ask for a post's first comment page without being asked: the source lists comments and the
 * listing did not say there are none. A listing that says nothing still gets asked.
 */
fun CommentsLoading.shouldFetchFirstPage(post: Post): Boolean =
    supports(post.id.source) && post.commentHint != CommentHint.None
