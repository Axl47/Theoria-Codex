package com.theoriacodex.app.comments

import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey

interface CommentsLoading {
    fun supports(source: SourceKey): Boolean

    suspend fun load(post: Post, pageToken: String?, parent: PostComment? = null): CommentPage

    fun nativeCommentAnchor(source: SourceKey, commentId: String): String? = null
}

fun CommentsLoading.shouldFetchFirstPage(post: Post): Boolean =
    supports(post.id.source) && post.commentHint != CommentHint.None
