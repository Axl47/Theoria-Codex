package com.theoriacodex.domain.model

/**
 * What a search listing says about a post's comments, before any comment request.
 * [count] is exact when the listing gives a number, 0 when it says there are none, and null when it
 * only says there are some. A post whose listing says nothing carries no hint at all.
 */
data class CommentHint(val count: Int?) {
    val hasComments: Boolean get() = count != 0

    companion object {
        val None = CommentHint(0)
        val Some = CommentHint(null)
    }
}

/** One read-only comment, already reduced to plain text by its source. */
data class PostComment(
    val id: String,
    val authorName: String,
    /** Absolute URL; requests to it carry the same headers as the post's own images. */
    val authorAvatarUrl: String?,
    val body: String,
    val createdAtEpochMs: Long?,
    /** A sticker posted in place of, or beside, the text (Pixiv stamps). */
    val stampUrl: String? = null,
    /** True when [CommentPage]s of replies can be requested with this comment as the parent. */
    val hasReplies: Boolean = false,
)

/**
 * One page of comments, oldest or newest first as the source orders them. [total] is the number of
 * comments on the post when the source reports it; with [nextPageToken] set and no [total], the
 * post has more comments than this page shows.
 */
data class CommentPage(
    val comments: List<PostComment>,
    val total: Int?,
    val nextPageToken: String?,
)
