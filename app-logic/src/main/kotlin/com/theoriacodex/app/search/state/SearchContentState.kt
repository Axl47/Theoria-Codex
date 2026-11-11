package com.theoriacodex.app.search.state

import com.theoriacodex.app.comments.keepingCommentHintOf
import com.theoriacodex.domain.model.Post

/** Refreshes known posts without changing the applied search, canonical order, or pagination. */
fun SearchUiState.withResolvedPosts(queryHash: String, posts: List<Post>): SearchUiState {
    if (query.appliedQueryHash != queryHash) return this
    val replacements = posts.associateBy(Post::id)
    if (replacements.isEmpty()) return this
    var changed = false
    val updated = content.results.map { post ->
        replacements[post.id]?.keepingCommentHintOf(post)?.also { replacement ->
            if (replacement != post) changed = true
        } ?: post
    }
    if (!changed) return this
    return copy(
        content = content.copy(
            results = updated,
            displayVersion = content.displayVersion + 1,
        ),
    )
}
