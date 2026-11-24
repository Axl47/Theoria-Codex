package com.theoriacodex.app.related

import com.theoriacodex.domain.model.Post

data class RelatedShelfBinding(
    val state: RelatedPostsUiState,
    val controller: RelatedPostsRouteController,
    val onOpenPost: (posts: List<Post>, index: Int) -> Unit,
)
