package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.domain.model.Post

@Composable
internal fun rememberFeedRelatedShelf(
    state: RelatedPostsUiState,
    controller: RelatedPostsRouteController,
    onOpenPost: (posts: List<Post>, index: Int) -> Unit,
): RelatedShelfBinding = remember(state, controller) { RelatedShelfBinding(state, controller, onOpenPost) }
