package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.ImageRef
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

abstract class RepositoryContract {
    protected abstract fun createCodexRepository(): CodexRepository
    protected abstract fun createRecentsRepository(clock: () -> Long): RecentsRepository
    protected abstract fun createSettingsRepository(): SettingsRepository
    protected abstract fun createLikesRepository(): LikesRepository
    protected abstract fun createUiRestoreRepository(): UiRestoreRepository

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
    fun `recents keeps one newest watched and search entry per identity`() = runTest {
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
        repository.recordWatchedPost(post, ViewerStreamSource.FOR_YOU, "new-query")
        repository.recordSearch(query, "query-hash")
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
        assertEquals(listOf("updated"), searches.single().query.includeTags)
    }

    @Test
    fun `settings applies the same source-aware duplicate policy`() = runTest {
        val repository = createSettingsRepository()
        val profileId = defaultRecommendationProfiles().first().profileId

        assertTrue(repository.addFavoriteTag(profileId, SourceKey.GELBOORU, "Blue Hair"))
        assertFalse(repository.addFavoriteTag(profileId, SourceKey.GELBOORU, "blue_hair"))
        assertTrue(repository.addForYouBlacklistEntry(profileId, SourceKey.PIXIV, listOf("Night", "Cat")))
        assertFalse(repository.addForYouBlacklistEntry(profileId, SourceKey.PIXIV, listOf("cat", "night")))

        val settings = repository.observeSettings().first()
        assertEquals(listOf("blue_hair"), settings.favoriteTagsByProfile.getValue(profileId).map(FavoriteTagEntry::tag))
        assertEquals(
            listOf(listOf("cat", "night")),
            settings.forYouBlacklistByProfile.getValue(profileId).map(ForYouBlacklistEntry::tags),
        )
    }

    @Test
    fun `likes are profile-scoped and toggling is reversible`() = runTest {
        val repository = createLikesRepository()
        val postId = repositoryTestPost(id = "post-1").id

        assertTrue(repository.toggleLike("profile-main", postId, listOf(" Tag ", "tag", "Other")))
        assertTrue(postId in repository.observeLikedPostIds("profile-main").first())
        assertTrue(repository.observeLikedPostIds("profile-alt").first().isEmpty())
        assertEquals(listOf("Tag", "Other"), repository.observeLikes("profile-main").first().single().tags)

        assertFalse(repository.toggleLike("profile-main", postId, emptyList()))
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
}

class InMemoryRepositoryContractTest : RepositoryContract() {
    override fun createCodexRepository(): CodexRepository = InMemoryCodexRepository()

    override fun createRecentsRepository(clock: () -> Long): RecentsRepository {
        return InMemoryRecentsRepository(clock = clock)
    }

    override fun createSettingsRepository(): SettingsRepository = InMemorySettingsRepository()

    override fun createLikesRepository(): LikesRepository = InMemoryLikesRepository()

    override fun createUiRestoreRepository(): UiRestoreRepository = InMemoryUiRestoreRepository()
}

class FileBackedRepositoryContractTest : RepositoryContract() {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private var directoryIndex = 0

    override fun createCodexRepository(): CodexRepository = FileBackedCodexRepository(newDirectory())

    override fun createRecentsRepository(clock: () -> Long): RecentsRepository {
        return FileBackedRecentsRepository(baseDirectory = newDirectory(), clock = clock)
    }

    override fun createSettingsRepository(): SettingsRepository = FileBackedSettingsRepository(newDirectory())

    override fun createLikesRepository(): LikesRepository = FileBackedLikesRepository(newDirectory())

    override fun createUiRestoreRepository(): UiRestoreRepository = FileBackedUiRestoreRepository(newDirectory())

    private fun newDirectory(): File {
        directoryIndex += 1
        return tempFolder.newFolder("repository-contract-$directoryIndex")
    }
}
