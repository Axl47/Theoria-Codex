package com.theoriacodex.app.related

import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

enum class LikeToggleOutcome {
    LIKED,
    UNLIKED,
}

data class RelatedPostsRequest(
    val seed: Post,
    val anchorCanonicalIndex: Int,
    val generation: Long,
) {
    init {
        require(anchorCanonicalIndex >= 0) { "Related-post anchor must be non-negative" }
        require(generation > 0L) { "Related-post generation must be positive" }
    }
}

sealed interface RelatedPostsUiState {
    data object Idle : RelatedPostsUiState
    data class Loading(val request: RelatedPostsRequest) : RelatedPostsUiState
    data class Loaded(
        val request: RelatedPostsRequest,
        val posts: List<Post>,
    ) : RelatedPostsUiState {
        init {
            require(posts.isNotEmpty()) { "Loaded related posts must not be empty" }
        }
    }
    data class Empty(val request: RelatedPostsRequest) : RelatedPostsUiState
    data class Failed(
        val request: RelatedPostsRequest,
        val message: String,
    ) : RelatedPostsUiState
}

val RelatedPostsUiState.requestOrNull: RelatedPostsRequest?
    get() = when (this) {
        RelatedPostsUiState.Idle -> null
        is RelatedPostsUiState.Loading -> request
        is RelatedPostsUiState.Loaded -> request
        is RelatedPostsUiState.Empty -> request
        is RelatedPostsUiState.Failed -> request
    }

val RelatedPostsUiState.loadedPosts: List<Post>
    get() = (this as? RelatedPostsUiState.Loaded)?.posts.orEmpty()

fun RelatedPostsUiState.begin(
    seed: Post,
    anchorCanonicalIndex: Int,
    generation: Long,
): RelatedPostsUiState.Loading = RelatedPostsUiState.Loading(
    RelatedPostsRequest(
        seed = seed,
        anchorCanonicalIndex = anchorCanonicalIndex,
        generation = generation,
    )
)

fun RelatedPostsUiState.complete(
    request: RelatedPostsRequest,
    incoming: List<Post>,
    canonicalPostIds: Set<PostId>,
): RelatedPostsUiState {
    if (requestOrNull != request) return this
    val posts = incoming.asSequence()
        .filterNot { post -> post.id == request.seed.id || post.id in canonicalPostIds }
        .distinctBy(Post::id)
        .take(MAX_RELATED_POSTS)
        .toList()
    return if (posts.isEmpty()) {
        RelatedPostsUiState.Empty(request)
    } else {
        RelatedPostsUiState.Loaded(request, posts)
    }
}

fun RelatedPostsUiState.fail(
    request: RelatedPostsRequest,
    message: String,
): RelatedPostsUiState {
    if (requestOrNull != request) return this
    return RelatedPostsUiState.Failed(
        request = request,
        message = message.trim().ifBlank { "Could not load related posts" },
    )
}

fun RelatedPostsUiState.clearIfSeed(seedId: PostId): RelatedPostsUiState {
    return if (requestOrNull?.seed?.id == seedId) RelatedPostsUiState.Idle else this
}

const val MAX_RELATED_POSTS: Int = 6
