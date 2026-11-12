package com.theoriacodex.app.comments

import androidx.compose.runtime.Immutable
import com.theoriacodex.domain.model.CommentOrder
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/** The Viewer screen's view of comments: each shown post's state, and what the screen may ask for. */
@Immutable
internal class ViewerComments(
    private val states: Map<PostId, PostCommentsState>,
    val actions: PostCommentsActions,
    /** How the top-level comments are listed, and the way the user changes it for every post. */
    val order: CommentOrder = CommentOrder.OLDEST,
    val onOrderSelected: (CommentOrder) -> Unit = {},
    /** Opens a web address in the browser, for a comment's link and for authors the app has no page for. */
    val onOpenUrl: (String) -> Unit = {},
) {
    fun stateOf(post: Post): PostCommentsState? = states[post.id]

    /** How many comments [post] has as far as is known, or null when it has none. */
    fun countOf(post: Post): CommentCount? = commentCount(post.commentHint, states[post.id])

    companion object {
        val None = ViewerComments(emptyMap(), PostCommentsActions.None)
    }
}
