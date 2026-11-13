package com.theoriacodex.app.creator

import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.app.source.PostResolutionFlights
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.CreatorFollowsRepository
import com.theoriacodex.domain.adapter.AccountProfileSourceAdapter
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.withTimeoutOrNull

/** Stateless provider boundary. The route owns admission, continuation, and request lifetime. */
interface CreatorRouteEngine {
    fun supports(creator: CreatorProfile): Boolean

    /** The feeds [creator] offers as a site account, in tab order; empty for creators that are artist tags or searches. */
    fun accountFeeds(creator: CreatorProfile): List<AccountFeed> = emptyList()

    /** Header facts for an account; null when the source has none. Failures throw. */
    suspend fun accountDetails(creator: CreatorProfile): AccountDetails? = null
    suspend fun load(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post>
    suspend fun recordAcceptedVisit(creator: CreatorProfile, posts: List<Post>)
    suspend fun resolvePost(postId: PostId): Post?
}

class CreatorProfileCoordinator(
    private val registry: SourceAdapterRegistry,
    private val follows: CreatorFollowsRepository? = null,
    /** Shared with the Viewer, so a card and the Viewer asking for one post at once fetch it once. */
    private val postResolutions: PostResolutionFlights = PostResolutionFlights(),
    private val fileMirrors: FileMirrorIndex = FileMirrorIndex(),
) : CreatorRouteEngine {
    override fun supports(creator: CreatorProfile): Boolean = accountAdapterFor(creator) != null || adapterFor(creator) != null

    override fun accountFeeds(creator: CreatorProfile): List<AccountFeed> =
        (registry.adapterFor(creator.source) as? AccountProfileSourceAdapter)?.accountFeeds(creator).orEmpty()

    override suspend fun accountDetails(creator: CreatorProfile): AccountDetails? {
        val adapter = accountAdapterFor(creator) ?: return null
        return withTimeoutOrNull(SOURCE_TIMEOUT_MS) { adapter.accountDetails(creator) }
    }

    /** An account's feeds, uploads included, come from its account adapter; any other creator is an uploads search. */
    override suspend fun load(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post> {
        val account = accountAdapterFor(creator)
        val search = adapterFor(creator)
        require(account != null || search != null) {
            "Creator browsing is not available for ${creator.source.displayName()}."
        }
        return withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            account?.accountPosts(creator, feed, pageToken) ?: search?.searchCreatorPosts(creator, pageToken)
        }?.also { page -> fileMirrors.learn(page.items) }
            ?: error(if (feed == AccountFeed.FAVORITES) "Favorites timed out. Try again." else "Creator uploads timed out. Try again.")
    }

    override suspend fun recordAcceptedVisit(creator: CreatorProfile, posts: List<Post>) {
        follows?.recordVisit(creator, posts)
    }

    override suspend fun resolvePost(postId: PostId): Post? {
        val adapter = registry.adapterFor(postId.source) ?: return null
        return postResolutions.resolve(postId) { adapter.resolvePost(postId) }
    }

    private fun adapterFor(creator: CreatorProfile): CreatorPostsSourceAdapter? =
        registry.adapterFor(creator.source) as? CreatorPostsSourceAdapter

    private fun accountAdapterFor(creator: CreatorProfile): AccountProfileSourceAdapter? =
        (registry.adapterFor(creator.source) as? AccountProfileSourceAdapter)?.takeIf { it.accountFeeds(creator).isNotEmpty() }

    private companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L
    }
}
