package com.theoriacodex.app.related

import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

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

fun testRelatedShelf(
    state: RelatedPostsUiState = RelatedPostsUiState.Idle,
    controller: RelatedPostsRouteController = testRelatedController({ state }),
    onOpenPost: (posts: List<Post>, index: Int) -> Unit = { _, _ -> },
): RelatedShelfBinding = RelatedShelfBinding(state, controller, onOpenPost)
