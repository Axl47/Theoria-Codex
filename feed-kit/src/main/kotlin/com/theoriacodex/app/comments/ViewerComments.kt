package com.theoriacodex.app.comments

import androidx.compose.runtime.Immutable
import com.theoriacodex.domain.model.CommentOrder
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

@Immutable
class ViewerComments(
    private val states: Map<PostId, PostCommentsState>,
    val actions: PostCommentsActions,
    val order: CommentOrder = CommentOrder.OLDEST,
    val onOrderSelected: (CommentOrder) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
) {
    fun stateOf(post: Post): PostCommentsState? = states[post.id]

    fun countOf(post: Post): CommentCount? = commentCount(post.commentHint, states[post.id])

    companion object {
        val None = ViewerComments(emptyMap(), PostCommentsActions.None)
    }
}
