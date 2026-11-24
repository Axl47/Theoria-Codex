package com.theoriacodex.app.comments

import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CommentOrder
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment

enum class CommentLoadStatus { Loading, Loaded, Failed }

data class CommentList(
    val comments: List<PostComment> = emptyList(),
    val total: Int? = null,
    val nextPageToken: String? = null,
    val status: CommentLoadStatus = CommentLoadStatus.Loading,
    val hasLoaded: Boolean = false,
) {
    val hasMore: Boolean get() = status == CommentLoadStatus.Loaded && nextPageToken != null

    fun loading() = copy(status = CommentLoadStatus.Loading)

    fun failed() = copy(status = CommentLoadStatus.Failed)

    fun withPage(page: CommentPage) = copy(
        comments = (comments + page.comments).distinctBy(PostComment::id),
        total = page.total ?: total,
        nextPageToken = page.nextPageToken,
        status = CommentLoadStatus.Loaded,
        hasLoaded = true,
    )
}

data class PostCommentsState(
    val top: CommentList = CommentList(),
    val replies: Map<String, CommentList> = emptyMap(),
    val expandedReplies: Set<String> = emptySet(),
    val focusKey: String? = null,
) {
    val hasSection: Boolean get() = !(top.status == CommentLoadStatus.Loaded && top.comments.isEmpty())

    fun withReplies(parentId: String, transform: (CommentList) -> CommentList) =
        copy(replies = replies + (parentId to transform(replies[parentId] ?: CommentList())))
}

fun commentItemKey(commentId: String) = "comment:$commentId"

fun replyItemKey(parentId: String, replyId: String) = "reply:$parentId:$replyId"

fun List<PostComment>.inOrder(order: CommentOrder): List<PostComment> {
    val (dated, undated) = partition { it.createdAtEpochMs != null }
    val byDate = if (order == CommentOrder.NEWEST) dated.sortedByDescending { it.createdAtEpochMs } else dated.sortedBy { it.createdAtEpochMs }
    return byDate + undated
}

sealed interface CommentCount {
    data class Exact(val value: Int) : CommentCount

    data class AtLeast(val loaded: Int) : CommentCount

    data object Some : CommentCount

    val label: String?
        get() = when (this) {
            is Exact -> value.toString()
            is AtLeast -> "$loaded+"
            Some -> null
        }

    fun description(unknown: String): String = when (this) {
        is Exact -> if (value == 1) "1 comment" else "$value comments"
        is AtLeast -> "$loaded+ comments"
        Some -> unknown
    }
}

fun commentCount(hint: CommentHint?, state: PostCommentsState?): CommentCount? {
    val top = state?.top?.takeIf { it.hasLoaded }
    if (top == null) return hint.countBeforeLoading()
    val hinted = hint?.count?.takeIf { it > 0 }
    return when {
        top.total != null -> top.total.takeIf { it > 0 }?.let(CommentCount::Exact)
        top.comments.isEmpty() -> null
        top.nextPageToken == null -> CommentCount.Exact(top.comments.size)
        hinted != null && hinted > top.comments.size -> CommentCount.Exact(hinted)
        else -> CommentCount.AtLeast(top.comments.size)
    }
}

private fun CommentHint?.countBeforeLoading(): CommentCount? {
    if (this == null || !hasComments) return null
    return count?.let(CommentCount::Exact) ?: CommentCount.Some
}

fun repliesLabel(total: Int?, expanded: Boolean): String = when {
    expanded -> "Hide replies"
    total == null -> "Replies"
    total == 1 -> "1 reply"
    else -> "$total replies"
}
