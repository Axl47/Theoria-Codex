package com.theoriacodex.app.related

import com.theoriacodex.domain.model.Post

/**
 * A related shelf as a screen renders it: the current state, the owner that drives it, and how its
 * posts open. Screens render and drive their shelf through it instead of dispatching shelf actions to
 * their own state holder; the owner behind [controller] may be the screen's own feed or another one
 * (live Codices, Recents).
 */
data class RelatedShelfBinding(
    val state: RelatedPostsUiState,
    val controller: RelatedPostsRouteController,
    val onOpenPost: (posts: List<Post>, index: Int) -> Unit,
)
