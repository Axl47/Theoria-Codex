package com.theoriacodex.app.creator

import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.withTimeoutOrNull

/** Stateless provider boundary. The route owns admission, continuation, and request lifetime. */
interface CreatorRouteEngine {
    fun supports(creator: CreatorProfile): Boolean
    suspend fun load(creator: CreatorProfile, pageToken: String?): Page<Post>
    suspend fun recordAcceptedVisit(creator: CreatorProfile, posts: List<Post>)
    suspend fun resolvePost(postId: PostId): Post?
}

class CreatorProfileCoordinator(
    private val registry: SourceAdapterRegistry,
    private val follows: CreatorFollowsRepository? = null,
) : CreatorRouteEngine {
    override fun supports(creator: CreatorProfile): Boolean = adapterFor(creator) != null

    override suspend fun load(creator: CreatorProfile, pageToken: String?): Page<Post> {
        val adapter = requireNotNull(adapterFor(creator)) {
            "Creator browsing is not available for ${creator.source.name.lowercase()}."
        }
        return withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            adapter.searchCreatorPosts(creator, pageToken)
        } ?: error("Creator uploads timed out. Try again.")
    }

    override suspend fun recordAcceptedVisit(creator: CreatorProfile, posts: List<Post>) {
        follows?.recordVisit(creator, posts)
    }

    override suspend fun resolvePost(postId: PostId): Post? =
        registry.adapterFor(postId.source)?.resolvePost(postId)

    private fun adapterFor(creator: CreatorProfile): CreatorPostsSourceAdapter? =
        registry.adapterFor(creator.source) as? CreatorPostsSourceAdapter

    private companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L
    }
}
