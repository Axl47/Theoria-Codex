package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DataStoreRepositoryContractsTest(
    private val backend: Backend,
) {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dataStoreScopes = mutableListOf<CoroutineScope>()
    private var directoryIndex = 0

    @After
    fun closeDataStores() = runBlocking {
        dataStoreScopes.forEach { scope ->
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    @Test
    fun `settings normalization and profile-owned mutations share one contract`() = runTest {
        val repository = createSettingsRepository()
        val initial = repository.observeSettings().first()
        assertEquals(SourceKey.entries.toSet(), initial.runtime.enabledSources)
        assertEquals(1.0, initial.runtime.sourceWeights.values.sum(), 0.0000001)

        repository.setEnabledSources(setOf(SourceKey.PIXIV, SourceKey.GELBOORU))
        repository.setSourceWeights(
            mapOf(
                SourceKey.PIXIV to Double.MAX_VALUE,
                SourceKey.GELBOORU to Double.MAX_VALUE,
            )
        )
        val profile = repository.addRecommendationProfile("  Contract Profile  ")
        assertTrue(repository.addFavoriteTag(profile.profileId, SourceKey.GELBOORU, "Blue Hair"))
        assertFalse(repository.addFavoriteTag(profile.profileId, SourceKey.GELBOORU, "blue_hair"))
        assertTrue(repository.addForYouBlacklistEntry(profile.profileId, SourceKey.PIXIV, listOf("Night", "Cat")))

        val updated = repository.observeSettings().first()
        assertEquals(profile.profileId, updated.activeProfileId)
        assertEquals("Contract Profile", updated.recommendationProfiles.last().name)
        assertEquals(listOf("blue_hair"), updated.favoriteTagsByProfile.getValue(profile.profileId).map { it.tag })
        assertEquals(
            listOf("cat", "night"),
            updated.forYouBlacklistByProfile.getValue(profile.profileId).single().tags,
        )
        assertEquals(1.0, updated.runtime.sourceWeights.values.sum(), 0.0000001)
    }

    @Test
    fun `ui restore preserves one-time tab scroll and launch context semantics`() = runTest {
        val repository = createUiRestoreRepository()
        val launch = ViewerLaunchContext(
            queryHash = "query",
            startIndex = 4,
            streamSource = ViewerStreamSource.CODEX,
            scrollOffsetHint = 21,
            recentsSection = RecentPostSection.CODEX,
        )

        assertEquals("codex", repository.migrateLegacyLastTab(" codex "))
        assertEquals("codex", repository.migrateLegacyLastTab("search"))
        repository.setSearchScrollState(
            "query",
            SearchScrollState(firstVisibleItemIndex = 5, firstVisibleItemOffsetPx = 17),
        )
        repository.setViewerLaunchContext(launch)

        assertEquals("codex", repository.getLastTab())
        assertEquals(5, repository.getSearchScrollState("query")?.firstVisibleItemIndex)
        assertEquals(launch, repository.observeViewerLaunchContext().first())
    }

    private fun createSettingsRepository(): SettingsRepository {
        return when (backend) {
            Backend.IN_MEMORY -> InMemorySettingsRepository()
            Backend.FILE_BACKED -> FileBackedSettingsRepository(newDirectory())
            Backend.DATASTORE -> DataStoreSettingsRepository(newDirectory(), newDataStoreScope())
        }
    }

    private fun createUiRestoreRepository(): UiRestoreRepository {
        return when (backend) {
            Backend.IN_MEMORY -> InMemoryUiRestoreRepository()
            Backend.FILE_BACKED -> FileBackedUiRestoreRepository(newDirectory())
            Backend.DATASTORE -> DataStoreUiRestoreRepository(newDirectory(), newDataStoreScope())
        }
    }

    private fun newDataStoreScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO).also(dataStoreScopes::add)
    }

    private fun newDirectory(): File {
        directoryIndex += 1
        return tempFolder.newFolder("${backend.name.lowercase()}-$directoryIndex")
    }

    enum class Backend {
        IN_MEMORY,
        FILE_BACKED,
        DATASTORE,
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "backend={0}")
        fun backends(): List<Array<Backend>> = Backend.entries.map { backend -> arrayOf(backend) }
    }
}
