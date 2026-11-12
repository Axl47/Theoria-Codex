package com.theoriacodex.app.creator

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.AccountProfileSourceAdapter
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.stubs.StubAdapterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCoordinatorTest {
    private val user = CreatorProfile(SourceKey.GELBOORU, "User", "1", uploadsQuery = "user:User")
    private val artistTag = CreatorProfile(SourceKey.GELBOORU, "Artist", "artist", uploadsQuery = "artist")

    @Test
    fun `an account's feeds and details come from its account adapter`() = runTest {
        val adapter = FakeAccountAdapter()
        val coordinator = CreatorProfileCoordinator(registryOf(adapter))

        assertEquals(listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES), coordinator.accountFeeds(user))
        assertEquals(AccountDetails(uploadCount = 2), coordinator.accountDetails(user))
        assertEquals(listOf("uploads"), coordinator.load(user, AccountFeed.UPLOADS, null).items.map { it.id.sourcePostId })
        assertEquals(listOf("favorites"), coordinator.load(user, AccountFeed.FAVORITES, "p2").items.map { it.id.sourcePostId })
        assertEquals(listOf(AccountFeed.UPLOADS to null, AccountFeed.FAVORITES to "p2"), adapter.accountRequests)
    }

    @Test
    fun `a creator the adapter does not call an account stays an uploads search`() = runTest {
        val adapter = FakeAccountAdapter()
        val coordinator = CreatorProfileCoordinator(registryOf(adapter))

        assertEquals(emptyList<AccountFeed>(), coordinator.accountFeeds(artistTag))
        assertEquals(null, coordinator.accountDetails(artistTag))
        assertTrue(coordinator.supports(artistTag))
        assertEquals(listOf("search"), coordinator.load(artistTag, AccountFeed.UPLOADS, null).items.map { it.id.sourcePostId })
        assertEquals(emptyList<Pair<AccountFeed, String?>>(), adapter.accountRequests)
    }

    private fun registryOf(adapter: SourceAdapter): SourceAdapterRegistry {
        val stub = StubAdapterRegistry()
        return object : SourceAdapterRegistry {
            override fun availableSources(): Set<SourceKey> = setOf(adapter.sourceKey)
            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapter.takeIf { sourceKey == adapter.sourceKey }
            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = stub.unifiedOrchestrator()
        }
    }
}

/** Treats creators whose uploads query starts with `user:` as accounts. */
private class FakeAccountAdapter : SourceAdapter, CreatorPostsSourceAdapter, AccountProfileSourceAdapter {
    val accountRequests = mutableListOf<Pair<AccountFeed, String?>>()

    override val sourceKey: SourceKey = SourceKey.GELBOORU
    override val capabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = true,
        supportsExcludeTagsServerSide = true,
        supportsDateRangeServerSide = true,
        supportsMinScoreServerSide = true,
        requiresCredentials = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> = Page(emptyList(), null)
    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
    override suspend fun quickQuery(kind: QuickQueryKind): Query = Query(
        mode = QueryMode.Source(sourceKey),
        includeTags = emptyList(),
        excludeTags = emptyList(),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )
    override suspend fun resolvePost(id: PostId): Post? = null

    override suspend fun searchCreatorPosts(creator: CreatorProfile, pageToken: String?): Page<Post> =
        Page(listOf(testPost(sourcePostId = "search")), null)

    override fun accountFeeds(creator: CreatorProfile): List<AccountFeed> =
        if (creator.uploadsQuery?.startsWith("user:") == true) listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES) else emptyList()

    override suspend fun accountDetails(creator: CreatorProfile): AccountDetails = AccountDetails(uploadCount = 2)

    override suspend fun accountPosts(creator: CreatorProfile, feed: AccountFeed, pageToken: String?): Page<Post> {
        accountRequests += feed to pageToken
        return Page(listOf(testPost(sourcePostId = feed.name.lowercase())), null)
    }
}
