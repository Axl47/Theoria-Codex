package com.theoriacodex.app.related

import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** A controller over the caller's own shelf state; [UnsupportedRelatedPostsLoader] means it never loads. */
fun testRelatedController(
    state: () -> RelatedPostsUiState = { RelatedPostsUiState.Idle },
    updateState: (RelatedPostsUiState) -> Unit = {},
    canonicalPosts: () -> List<Post> = { emptyList() },
): RelatedPostsRouteController = RelatedPostsRouteController(
    scope = CoroutineScope(SupervisorJob()),
    loader = UnsupportedRelatedPostsLoader,
    currentState = state,
    canonicalPosts = canonicalPosts,
    updateState = updateState,
)

/** The binding a feed screen takes; the default is an empty shelf nothing drives. */
fun testRelatedShelf(
    state: RelatedPostsUiState = RelatedPostsUiState.Idle,
    controller: RelatedPostsRouteController = testRelatedController({ state }),
    onOpenPost: (posts: List<Post>, index: Int) -> Unit = { _, _ -> },
): RelatedShelfBinding = RelatedShelfBinding(state, controller, onOpenPost)
