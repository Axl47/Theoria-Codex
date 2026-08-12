package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RepositoryContractTest(
    private val backend: Backend,
) {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private var directoryIndex = 0

    @Test
    fun `codex membership deduplicates while explicit hydration updates the shared post`() = runTest {
        val repository = createCodexRepository()
        val codex = repository.createCodex("Contract")
        val sparse = repositoryTestPost(id = "post-1").copy(full = null, title = null)

        repository.addItem(codex.codexId, sparse)
        repository.addItem(codex.codexId, sparse)

        assertEquals(1, repository.observeCodexItems(codex.codexId).first().size)
        assertEquals(listOf(sparse), repository.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED).first())

        val hydrated = sparse.copy(
            title = "Hydrated",
            full = ImageRef(
                url = "https://example.com/full/post-1.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
        )
        repository.updatePost(hydrated)

        assertEquals(hydrated, repository.getPost(hydrated.id))
        assertEquals(listOf(hydrated), repository.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED).first())
    }

    @Test
    fun `codex names order and source sort follow one policy`() = runTest {
        val repository = createCodexRepository()
        val first = repository.createCodex(" Favorites ")
        val second = repository.createCodex("favorites")
        val third = repository.createCodex("  ")
        repository.addItem(first.codexId, repositoryTestPost(id = "pixiv", source = SourceKey.PIXIV))
        repository.addItem(first.codexId, repositoryTestPost(id = "ai", source = SourceKey.AIBOORU))

        repository.reorderCodex(third.codexId, targetIndex = -10)

        assertEquals("Favorites", first.name)
        assertEquals("favorites 2", second.name)
        assertEquals("Codex", third.name)
        assertEquals(
            listOf(third.codexId, first.codexId, second.codexId),
            repository.observeCodices().first().map { codex -> codex.codexId },
        )
        assertEquals(
            listOf(SourceKey.AIBOORU, SourceKey.PIXIV),
            repository.observeCodexPosts(first.codexId, CodexSortMode.BY_SOURCE)
                .first()
                .map { post -> post.id.source },
        )
    }

    @Test
    fun `codex automatic tags are source aware idempotent and reversible`() = runTest {
        val repository = createCodexRepository()
        val codex = repository.createCodex("Automatic")

        repository.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue sky"),
            enabled = true,
        )
        repository.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue_sky"),
            enabled = true,
        )
        repository.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.PIXIV, "blue sky"),
            enabled = true,
        )

        assertEquals(
            listOf(
                CodexAutomaticTag(SourceKey.PIXIV, "blue sky"),
                CodexAutomaticTag(SourceKey.GELBOORU, "blue sky"),
            ),
            repository.observeCodex(codex.codexId).first()?.automaticTags,
        )

        repository.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue_sky"),
            enabled = false,
        )
        assertEquals(
            listOf(CodexAutomaticTag(SourceKey.PIXIV, "blue sky")),
            repository.observeCodex(codex.codexId).first()?.automaticTags,
        )
    }

    @Test
    fun `recents keeps one newest watched and search entry per identity`() = runTest {
        assumeTrue("Room owns the second durable Recents contract lane", backend == Backend.IN_MEMORY)
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        val post = repositoryTestPost(id = "post-1")
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        repository.recordWatchedPost(post, ViewerStreamSource.SEARCH, "old-query")
        repository.recordWatchedPost(
            post,
            ViewerStreamSource.FOR_YOU,
            "new-query",
            RecentPostSection.WATCHED,
        )
        repository.recordSearch(query, " query-hash ")
        repository.recordSearch(
            query.copy(includeTerms = listOf(SearchTerm("updated"))),
            "query-hash",
        )

        val watched = repository.observeWatchedPosts().first()
        val searches = repository.observeSearches().first()
        assertEquals(1, watched.size)
        assertEquals(ViewerStreamSource.FOR_YOU, watched.single().origin)
        assertEquals("new-query", watched.single().originQueryHash)
        assertEquals(1, searches.size)
        assertEquals("query-hash", searches.single().queryHash)
        assertEquals(listOf("updated"), searches.single().query.includeTags)
    }

    @Test
    fun `recents restore preserves exact cleared entries without replacing newer activity`() = runTest {
        assumeTrue("Room owns the second durable Recents contract lane", backend == Backend.IN_MEMORY)
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        val watchedPost = repositoryTestPost(id = "watched")
        val codexPost = repositoryTestPost(id = "codex")
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("restore"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        repository.recordWatchedPost(watchedPost, ViewerStreamSource.SEARCH, "search:restore")
        repository.recordWatchedPost(codexPost, ViewerStreamSource.CODEX, "codex:restore")
        repository.recordSearch(query, "restore-query")
        val watchedSnapshot = repository.observeWatchedPosts().first()
        val searchSnapshot = repository.observeSearches().first()

        repository.clearAll()
        now = 500L
        repository.recordWatchedPost(
            watchedPost,
            ViewerStreamSource.FOR_YOU,
            "newer",
            RecentPostSection.WATCHED,
        )
        repository.recordSearch(query.copy(includeTerms = listOf(SearchTerm("newer"))), "restore-query")
        repository.restoreEntries(watchedSnapshot, searchSnapshot)

        val restoredWatched = repository.observeWatchedPosts().first()
        val restoredSearch = repository.observeSearches().first().single()
        assertEquals(2, restoredWatched.size)
        assertEquals(
            ViewerStreamSource.FOR_YOU,
            restoredWatched.single { it.post.id == watchedPost.id }.origin,
        )
        assertEquals(
            watchedSnapshot.single { it.section == RecentPostSection.CODEX },
            restoredWatched.single { it.section == RecentPostSection.CODEX },
        )
        assertEquals(listOf("newer"), restoredSearch.query.includeTags)
        assertEquals(501L, restoredSearch.searchedAtEpochMs)
    }

    @Test
    fun `recents preserves a posts section when it is reopened from history`() = runTest {
        assumeTrue("Room owns the second durable Recents contract lane", backend == Backend.IN_MEMORY)
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        val codexPost = repositoryTestPost(id = "codex-post")
        val watchedPost = repositoryTestPost(id = "watched-post")

        repository.recordWatchedPost(codexPost, ViewerStreamSource.CODEX, "codex:art")
        repository.recordWatchedPost(watchedPost, ViewerStreamSource.SEARCH, "search:art")
        repository.recordWatchedPost(
            codexPost,
            ViewerStreamSource.RECENTS,
            "recents:codex",
            RecentPostSection.CODEX,
        )
        repository.recordWatchedPost(
            watchedPost,
            ViewerStreamSource.RECENTS,
            "recents:watched",
            RecentPostSection.WATCHED,
        )

        val watched = repository.observeWatchedPosts().first()
        assertEquals(ViewerStreamSource.SEARCH, watched.first { it.post.id == watchedPost.id }.origin)
        assertEquals(ViewerStreamSource.CODEX, watched.first { it.post.id == codexPost.id }.origin)
        assertEquals("codex:art", watched.first { it.post.id == codexPost.id }.originQueryHash)
    }

    @Test
    fun `recents keeps independent watched and codex memberships for one post`() = runTest {
        assumeTrue("Room owns the second durable Recents contract lane", backend == Backend.IN_MEMORY)
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        val post = repositoryTestPost(id = "shared-post")

        repository.recordWatchedPost(post, ViewerStreamSource.SEARCH, "search:art")
        repository.recordWatchedPost(post, ViewerStreamSource.CODEX, "codex:art")

        val watched = repository.observeWatchedPosts().first()
        assertEquals(2, watched.size)
        assertEquals(
            setOf(RecentPostSection.WATCHED, RecentPostSection.CODEX),
            watched.map { entry -> entry.section }.toSet(),
        )
        assertEquals(1, repository.observeActivity().first().filterIsInstance<RecentActivityEntry.Watched>().size)
    }

    @Test
    fun `FYP searches are activity entries and clear independently from applied searches`() = runTest {
        assumeTrue("Room owns the second durable Recents contract lane", backend == Backend.IN_MEMORY)
        val repository = createRecentsRepository(clock = { 100L })
        val fypQuery = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("favorite"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        repository.recordSearch(fypQuery.copy(mode = QueryMode.Source(SourceKey.PIXIV)), "search:manual")
        repository.recordSearch(fypQuery, "for_you:seed", RecentSearchKind.FYP, listOf(SourceKey.PIXIV))

        assertEquals(2, repository.observeActivity().first().size)
        repository.clearSearches("for_you:")
        assertEquals(listOf("search:manual"), repository.observeSearches().first().map { it.queryHash })
    }

    @Test
    fun `legacy FYP result posts stay out of combined activity`() = runTest {
        assumeTrue("Room owns the second durable Recents contract lane", backend == Backend.IN_MEMORY)
        val repository = createRecentsRepository(clock = { 100L })
        repository.recordWatchedPost(
            repositoryTestPost(id = "legacy-fyp"),
            ViewerStreamSource.FOR_YOU,
            "for_you:legacy",
            RecentPostSection.FYP,
        )

        assertTrue(repository.observeActivity().first().isEmpty())
    }

    @Test
    fun `legacy Recents viewer identities restore their typed section`() {
        assertEquals(
            RecentPostSection.CODEX,
            decodeRestoredRecentsSection(null, ViewerStreamSource.RECENTS, "recents:codex"),
        )
        assertEquals(
            RecentPostSection.WATCHED,
            decodeRestoredRecentsSection(null, ViewerStreamSource.RECENTS, "recents:watched"),
        )
        assertNull(decodeRestoredRecentsSection(null, ViewerStreamSource.SEARCH, "recents:codex"))
    }

    @Test
    fun `recents clears codex and external watched sections independently`() = runTest {
        assumeTrue("Room owns the second durable Recents contract lane", backend == Backend.IN_MEMORY)
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        repository.recordWatchedPost(repositoryTestPost(id = "codex-post"), ViewerStreamSource.CODEX, null)
        repository.recordWatchedPost(repositoryTestPost(id = "watched-post"), ViewerStreamSource.SEARCH, null)

        repository.clearWatchedPosts(RecentPostSection.WATCHED)
        assertEquals(listOf(RecentPostSection.CODEX), repository.observeWatchedPosts().first().map { it.section })

        repository.clearWatchedPosts(RecentPostSection.CODEX)
        assertTrue(repository.observeWatchedPosts().first().isEmpty())
    }

    @Test
    fun `settings starts with a complete normalized source catalog`() = runTest {
        val settings = createSettingsRepository().observeSettings().first()

        assertEquals(SourceKey.entries.toSet(), settings.runtime.enabledSources)
        assertEquals(SourceKey.entries.toSet(), settings.runtime.sourceWeights.keys)
        assertEquals(1.0, settings.runtime.sourceWeights.values.sum(), 0.0000001)
        assertTrue(settings.runtime.sourceWeights.values.all { weight -> weight.isFinite() && weight >= 0.0 })
        assertEquals(1.0, SourceRuntimeSettings().sourceWeights.values.sum(), 0.0000001)
    }

    @Test
    fun `settings repairs invalid and all-zero source weights without NaN`() = runTest {
        val repository = createSettingsRepository()
        val enabled = setOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.NHENTAI)
        repository.setEnabledSources(enabled)
        repository.setSourceWeights(
            mapOf(
                SourceKey.PIXIV to Double.NaN,
                SourceKey.GELBOORU to Double.POSITIVE_INFINITY,
                SourceKey.NHENTAI to -1.0,
            )
        )

        var weights = repository.observeSettings().first().runtime.sourceWeights
        assertEquals(enabled, weights.keys)
        assertEquals(1.0, weights.values.sum(), 0.0000001)
        assertTrue(weights.values.all { weight -> weight.isFinite() && weight > 0.0 })
        assertTrue(weights.values.all { weight -> kotlin.math.abs(weight - (1.0 / 3.0)) < 0.0000001 })

        repository.setSourceWeights(
            mapOf(
                SourceKey.PIXIV to Double.MAX_VALUE,
                SourceKey.GELBOORU to Double.MAX_VALUE,
                SourceKey.NHENTAI to 0.0,
            )
        )
        weights = repository.observeSettings().first().runtime.sourceWeights
        assertEquals(0.5, weights.getValue(SourceKey.PIXIV), 0.0000001)
        assertEquals(0.5, weights.getValue(SourceKey.GELBOORU), 0.0000001)
        assertEquals(0.0, weights.getValue(SourceKey.NHENTAI), 0.0000001)
        assertEquals(1.0, weights.values.sum(), 0.0000001)

        repository.setEnabledSources(emptySet())
        assertTrue(repository.observeSettings().first().runtime.sourceWeights.isEmpty())
    }

    @Test
    fun `settings applies one profile and source-aware tag policy`() = runTest {
        val repository = createSettingsRepository()
        val profileId = defaultRecommendationProfiles().first().profileId

        assertTrue(repository.addFavoriteTag(" $profileId ", SourceKey.GELBOORU, "Blue Hair"))
        assertFalse(repository.addFavoriteTag(profileId, SourceKey.GELBOORU, "blue_hair"))
        assertTrue(repository.addForYouBlacklistEntry(profileId, SourceKey.PIXIV, listOf("Night", "Cat")))
        assertFalse(repository.addForYouBlacklistEntry(profileId, SourceKey.PIXIV, listOf("cat", "night")))
        assertFalse(repository.addFavoriteTag("missing", SourceKey.PIXIV, "cat"))
        assertFalse(repository.addForYouBlacklistEntry("missing", SourceKey.PIXIV, listOf("cat")))

        var settings = repository.observeSettings().first()
        assertEquals(listOf("blue_hair"), settings.favoriteTagsByProfile.getValue(profileId).map(FavoriteTagEntry::tag))
        assertEquals(
            listOf(listOf("cat", "night")),
            settings.forYouBlacklistByProfile.getValue(profileId).map(ForYouBlacklistEntry::tags),
        )

        assertTrue(repository.removeFavoriteTag(profileId, SourceKey.GELBOORU, " BLUE HAIR "))
        assertTrue(repository.removeForYouBlacklistEntry(profileId, SourceKey.PIXIV, listOf("NIGHT", "cat")))
        settings = repository.observeSettings().first()
        assertTrue(settings.favoriteTagsByProfile[profileId].isNullOrEmpty())
        assertTrue(settings.forYouBlacklistByProfile[profileId].isNullOrEmpty())
    }

    @Test
    fun `recommendation profile mutation trims names and cleans profile-owned state`() = runTest {
        val repository = createSettingsRepository()
        val created = repository.addRecommendationProfile("  Sketching  ")
        assertEquals("Sketching", created.name)
        assertEquals(created.profileId, repository.observeSettings().first().activeProfileId)
        assertTrue(repository.addFavoriteTag(created.profileId, SourceKey.PIXIV, "portrait"))
        assertTrue(repository.addForYouBlacklistEntry(created.profileId, SourceKey.PIXIV, listOf("draft")))

        assertTrue(repository.removeRecommendationProfile(" ${created.profileId} "))

        val settings = repository.observeSettings().first()
        assertTrue(settings.recommendationProfiles.none { profile -> profile.profileId == created.profileId })
        assertTrue(settings.favoriteTagsByProfile[created.profileId].isNullOrEmpty())
        assertTrue(settings.forYouBlacklistByProfile[created.profileId].isNullOrEmpty())
        assertFalse(repository.removeRecommendationProfile("missing"))
    }

    @Test
    fun `likes are profile-scoped normalized and toggling is reversible`() = runTest {
        val repository = createLikesRepository(clock = { 123L })
        val postId = repositoryTestPost(id = "post-1").id

        assertTrue(repository.toggleLike(" profile-main ", postId, listOf(" Tag ", "tag", "Other")))
        assertTrue(postId in repository.observeLikedPostIds("profile-main").first())
        assertTrue(repository.observeLikedPostIds("profile-alt").first().isEmpty())
        assertEquals(listOf("Tag", "Other"), repository.observeLikes("profile-main").first().single().tags)
        assertEquals(123L, repository.observeLikes("profile-main").first().single().likedAtEpochMs)

        assertFalse(repository.toggleLike("profile-main", postId, emptyList()))
        assertTrue(repository.observeLikes("profile-main").first().isEmpty())
        assertFalse(repository.toggleLike("  ", postId, listOf("ignored")))
        assertTrue(repository.observeLikes("").first().isEmpty())

        assertTrue(repository.toggleLike("profile-main", postId, listOf("Again")))
        repository.clearLikes(" profile-main ")
        assertTrue(repository.observeLikes("profile-main").first().isEmpty())
    }

    @Test
    fun `ui restore starts empty and imports the default legacy tab`() = runTest {
        val repository = createUiRestoreRepository()

        assertEquals(null, repository.getLastTab())
        assertEquals(null, repository.migrateLegacyLastTab("  "))
        assertEquals("search", repository.migrateLegacyLastTab(AppSettings().lastSelectedTabRoute))
        assertEquals("search", repository.getLastTab())
    }

    @Test
    fun `ui restore imports a nonblank legacy tab only once`() = runTest {
        val repository = createUiRestoreRepository()

        assertEquals("codex", repository.migrateLegacyLastTab(" codex "))
        assertEquals("codex", repository.migrateLegacyLastTab("search"))
        assertEquals("codex", repository.getLastTab())
    }

    @Test
    fun `later ui restore state wins over legacy settings`() = runTest {
        val repository = createUiRestoreRepository()

        repository.setLastTab("settings")

        assertEquals("settings", repository.migrateLegacyLastTab("for-you"))
        assertEquals("settings", repository.getLastTab())
    }

    @Test
    fun `ui restore persists settings section expansion`() = runTest {
        val repository = createUiRestoreRepository()
        val expansion = mapOf(
            "UNIFIED_MODE" to false,
            "STORAGE_AND_CACHING" to false,
        )

        repository.setSettingsSectionExpansion(expansion)

        assertEquals(expansion, repository.getSettingsSectionExpansion())
    }

    @Test
    fun `ui restore keeps feed FAB contexts independent`() = runTest {
        val repository = createUiRestoreRepository()
        val search = FeedFabRestoreState(animatedOnly = true, hideWatched = true)
        val forYou = FeedFabRestoreState(sortMode = SortMode.RANDOM.name, durationMinBucket = 2)

        repository.setFeedFabRestoreState("search", search)
        repository.setFeedFabRestoreState("for-you", forYou)

        assertEquals(mapOf("search" to search, "for-you" to forYou), repository.getFeedFabRestoreStates())
    }

    private fun createCodexRepository(): CodexRepository {
        return InMemoryCodexRepository()
    }

    private fun createRecentsRepository(clock: () -> Long): RecentsRepository {
        return InMemoryRecentsRepository(clock = clock)
    }

    private fun createSettingsRepository(): SettingsRepository {
        return InMemorySettingsRepository()
    }

    private fun createLikesRepository(clock: () -> Long): LikesRepository {
        return InMemoryLikesRepository(clock = clock)
    }

    private fun createUiRestoreRepository(): UiRestoreRepository {
        return InMemoryUiRestoreRepository()
    }

    private fun newDirectory(): File {
        directoryIndex += 1
        return tempFolder.newFolder("repository-contract-$directoryIndex")
    }

    enum class Backend {
        IN_MEMORY,
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<Backend>> {
            return Backend.entries.map { backend -> arrayOf(backend) }
        }
    }
}
