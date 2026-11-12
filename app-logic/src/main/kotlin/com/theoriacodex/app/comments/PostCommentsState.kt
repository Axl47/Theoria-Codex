package com.theoriacodex.app.comments

import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CommentOrder
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment

/** Where one list of comments (a post's, or one comment's replies) stands. */
enum class CommentLoadStatus { Loading, Loaded, Failed }

/**
 * One list of comments, grown page by page. A failed page keeps what already loaded, and
 * [nextPageToken] still names the page to retry.
 */
data class CommentList(
    val comments: List<PostComment> = emptyList(),
    val total: Int? = null,
    val nextPageToken: String? = null,
    val status: CommentLoadStatus = CommentLoadStatus.Loading,
    /** True once any page arrived, which is how an empty answer differs from none yet. */
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

/**
 * A post's comments in the Viewer: its own list, and the lists of replies opened under its comments.
 * A post with no state was never asked for comments.
 */
data class PostCommentsState(
    val top: CommentList = CommentList(),
    /** Reply lists by parent comment id; kept after collapsing so reopening is instant. */
    val replies: Map<String, CommentList> = emptyMap(),
    val expandedReplies: Set<String> = emptySet(),
    /**
     * The sheet's list key ([commentItemKey], [replyItemKey]) of the comment a link pointed at, set once the
     * search for it ([PostCommentsController.focus]) found it. The sheet scrolls to it and then clears it with
     * [PostCommentsActions.focusShown], so it acts once.
     */
    val focusKey: String? = null,
) {
    /** A fetched page with nothing in it means there is nothing to show, not a section to draw. */
    val hasSection: Boolean get() = !(top.status == CommentLoadStatus.Loaded && top.comments.isEmpty())

    fun withReplies(parentId: String, transform: (CommentList) -> CommentList) =
        copy(replies = replies + (parentId to transform(replies[parentId] ?: CommentList())))
}

/** The key of a top-level comment's item in the sheet's list. */
fun commentItemKey(commentId: String) = "comment:$commentId"

/** The key of the item for a reply to [parentId] in the sheet's list. */
fun replyItemKey(parentId: String, replyId: String) = "reply:$parentId:$replyId"

/** The comments by date in [order]; ones without a date follow in the order the source gave them. */
fun List<PostComment>.inOrder(order: CommentOrder): List<PostComment> {
    val (dated, undated) = partition { it.createdAtEpochMs != null }
    val byDate = if (order == CommentOrder.NEWEST) dated.sortedByDescending { it.createdAtEpochMs } else dated.sortedBy { it.createdAtEpochMs }
    return byDate + undated
}

/** How many comments a post has, as precisely as is known. */
sealed interface CommentCount {
    data class Exact(val value: Int) : CommentCount

    /** More than one page, and the source gave no total. */
    data class AtLeast(val loaded: Int) : CommentCount

    /** The listing says there are some, without saying how many. */
    data object Some : CommentCount

    /** The figure to show next to a comment icon, or null when only the existence is known. */
    val label: String?
        get() = when (this) {
            is Exact -> value.toString()
            is AtLeast -> "$loaded+"
            Some -> null
        }

    /** Screen-reader text; [unknown] stands in when no figure is known. */
    fun description(unknown: String): String = when (this) {
        is Exact -> if (value == 1) "1 comment" else "$value comments"
        is AtLeast -> "$loaded+ comments"
        Some -> unknown
    }
}

/**
 * The post's comment count: what its first page says once loaded, otherwise what its listing hints.
 * Null when the post has no comments, or nothing says it has.
 */
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

/**
 * A resolved post replaces a listed one with the listing's [CommentHint] when the resolved one has none,
 * because detail responses seldom repeat what the listing said about comments.
 */
fun Post.keepingCommentHintOf(listed: Post): Post =
    if (commentHint == null && listed.commentHint != null) copy(commentHint = listed.commentHint) else this

/** The reply button's text: "Hide replies" while open; otherwise the reply count once known, else "Replies". */
fun repliesLabel(total: Int?, expanded: Boolean): String = when {
    expanded -> "Hide replies"
    total == null -> "Replies"
    total == 1 -> "1 reply"
    else -> "$total replies"
}
