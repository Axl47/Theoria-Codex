package com.theoriacodex.app.related

import com.theoriacodex.domain.model.Post

/**
 * A related shelf as a screen renders it: the current state, the owner that drives it, and how its
 * posts open. Used by screens whose shelf owner is not their own state holder (live Codices, Recents).
 */
internal data class RelatedShelfBinding(
    val state: RelatedPostsUiState,
    val controller: RelatedPostsRouteController,
    val onOpenPost: (posts: List<Post>, index: Int) -> Unit,
)
