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

data class RelatedPostsPage(
    val request: RelatedPostsRequest,
    val posts: List<Post>,
) {
    init {
        require(posts.isNotEmpty()) { "A related-post page must not be empty" }
    }
}

sealed interface RelatedPostsUiState {
    data object Idle : RelatedPostsUiState
    data class Loading(
        val request: RelatedPostsRequest,
        val retainedPages: List<RelatedPostsPage> = emptyList(),
    ) : RelatedPostsUiState
    data class Loaded(
        val request: RelatedPostsRequest,
        val posts: List<Post>,
        val pages: List<RelatedPostsPage> = listOf(RelatedPostsPage(request, posts)),
        val currentPageIndex: Int = pages.lastIndex,
    ) : RelatedPostsUiState {
        init {
            require(posts.isNotEmpty()) { "Loaded related posts must not be empty" }
            require(currentPageIndex in pages.indices) { "Current related-post page must exist" }
            require(pages[currentPageIndex].posts == posts) {
                "Selected related-post page must match the rendered posts"
            }
        }
    }
    data class Empty(
        val request: RelatedPostsRequest,
        val retainedPages: List<RelatedPostsPage> = emptyList(),
    ) : RelatedPostsUiState
    data class Failed(
        val request: RelatedPostsRequest,
        val message: String,
        val retainedPages: List<RelatedPostsPage> = emptyList(),
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

val RelatedPostsUiState.availablePages: List<RelatedPostsPage>
    get() = when (this) {
        RelatedPostsUiState.Idle -> emptyList()
        is RelatedPostsUiState.Loading -> retainedPages
        is RelatedPostsUiState.Loaded -> pages
        is RelatedPostsUiState.Empty -> retainedPages
        is RelatedPostsUiState.Failed -> retainedPages
    }

val RelatedPostsUiState.currentPageIndex: Int
    get() = when (this) {
        RelatedPostsUiState.Idle -> -1
        is RelatedPostsUiState.Loaded -> currentPageIndex
        else -> availablePages.size
    }

fun RelatedPostsUiState.begin(
    seed: Post,
    anchorCanonicalIndex: Int,
    generation: Long,
    retainedPages: List<RelatedPostsPage> = emptyList(),
): RelatedPostsUiState.Loading = RelatedPostsUiState.Loading(
    request = RelatedPostsRequest(
        seed = seed,
        anchorCanonicalIndex = anchorCanonicalIndex,
        generation = generation,
    ),
    retainedPages = retainedPages,
)

fun RelatedPostsUiState.complete(
    request: RelatedPostsRequest,
    incoming: List<Post>,
    canonicalPostIds: Set<PostId>,
): RelatedPostsUiState {
    if (requestOrNull != request) return this
    val loading = this as? RelatedPostsUiState.Loading
    val retainedPostIds = loading?.retainedPages
        .orEmpty()
        .flatMapTo(mutableSetOf(), RelatedPostsPage::posts)
        .mapTo(mutableSetOf(), Post::id)
    val posts = incoming.asSequence()
        .filterNot { post ->
            post.id == request.seed.id || post.id in canonicalPostIds || post.id in retainedPostIds
        }
        .distinctBy(Post::id)
        .take(MAX_RELATED_POSTS)
        .toList()
    val retainedPages = loading
        ?.retainedPages
        .orEmpty()
        .mapNotNull { page ->
            val retainedPosts = page.posts.filterNot { post -> post.id in canonicalPostIds }
            retainedPosts.takeIf(List<Post>::isNotEmpty)?.let { page.copy(posts = it) }
        }
    return if (posts.isEmpty()) {
        RelatedPostsUiState.Empty(request, retainedPages)
    } else {
        val page = RelatedPostsPage(request, posts)
        val pages = retainedPages + page
        RelatedPostsUiState.Loaded(request, posts, pages, pages.lastIndex)
    }
}

fun RelatedPostsUiState.selectPage(index: Int): RelatedPostsUiState {
    val pages = availablePages
    val page = pages.getOrNull(index) ?: return this
    return RelatedPostsUiState.Loaded(
        request = requestOrNull ?: page.request,
        posts = page.posts,
        pages = pages,
        currentPageIndex = index,
    )
}

fun RelatedPostsUiState.withoutPost(postId: PostId): RelatedPostsUiState {
    val loaded = this as? RelatedPostsUiState.Loaded ?: return this
    val pages = loaded.pages.mapNotNull { page ->
        val retainedPosts = page.posts.filterNot { post -> post.id == postId }
        retainedPosts.takeIf(List<Post>::isNotEmpty)?.let { page.copy(posts = it) }
    }
    if (pages.isEmpty()) return RelatedPostsUiState.Idle
    val selectedIndex = loaded.currentPageIndex.coerceAtMost(pages.lastIndex)
    val selected = pages[selectedIndex]
    return RelatedPostsUiState.Loaded(
        request = loaded.request,
        posts = selected.posts,
        pages = pages,
        currentPageIndex = selectedIndex,
    )
}

fun RelatedPostsUiState.fail(
    request: RelatedPostsRequest,
    message: String,
): RelatedPostsUiState {
    if (requestOrNull != request) return this
    return RelatedPostsUiState.Failed(
        request = request,
        message = message.trim().ifBlank { "Could not load related posts" },
        retainedPages = (this as? RelatedPostsUiState.Loading)?.retainedPages.orEmpty(),
    )
}

fun RelatedPostsUiState.clearIfSeed(seedId: PostId): RelatedPostsUiState {
    return if (requestOrNull?.seed?.id == seedId) RelatedPostsUiState.Idle else this
}

fun promoteRelatedPost(
    canonicalPosts: List<Post>,
    post: Post,
    insertionIndex: Int,
): List<Post> {
    if (canonicalPosts.any { candidate -> candidate.id == post.id }) return canonicalPosts
    return canonicalPosts.toMutableList().apply {
        add(insertionIndex.coerceIn(0, size), post)
    }
}

const val MAX_RELATED_POSTS: Int = 6
