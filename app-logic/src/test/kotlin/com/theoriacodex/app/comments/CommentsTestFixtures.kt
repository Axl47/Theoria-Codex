package com.theoriacodex.app.comments

import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

internal fun commentsTestPost(id: String = "1", hint: CommentHint? = null, pageUrl: String? = null) = Post(
    id = PostId(SourceKey.PIXIV, id),
    preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
    full = null,
    pageUrl = pageUrl,
    width = null,
    height = null,
    canonicalTags = emptyList(),
    rawTags = emptyList(),
    authorName = null,
    createdAtEpochMs = null,
    commentHint = hint,
)

internal fun testComment(id: String, hasReplies: Boolean = false) =
    PostComment(id, "author-$id", authorAvatarUrl = null, body = "body-$id", createdAtEpochMs = null, hasReplies = hasReplies)

internal fun commentPage(vararg ids: String, total: Int? = null, next: String? = null) =
    CommentPage(ids.map(::testComment), total, next)

/** A loader that answers from [pages] by "parentId/pageToken", recording each request and failing on demand. */
internal class FakeCommentsLoader(
    private val pages: Map<String, CommentPage> = emptyMap(),
    var supported: Boolean = true,
    /** Top-level comment ids that have a site anchor, by comment id. */
    private val anchors: Map<String, String> = emptyMap(),
) : CommentsLoading {
    val requests = mutableListOf<String>()
    var failNext = false

    override fun supports(source: SourceKey) = supported

    override fun nativeCommentAnchor(source: SourceKey, commentId: String) = anchors[commentId]

    override suspend fun load(post: Post, pageToken: String?, parent: PostComment?): CommentPage {
        val key = "${parent?.id.orEmpty()}/${pageToken.orEmpty()}"
        requests += "${post.id.sourcePostId}:$key"
        if (failNext) {
            failNext = false
            error("offline")
        }
        return pages.getValue(key)
    }
}
