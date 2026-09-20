package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FileBackedSavedSearchRepositoryTest : FileBackedRepositoryTestFixture() {
    @Test
    fun `pin and rename survive restart with exact grouped Multi-Search and FYP execution`() = runTest {
        val directory = tempDir("pinned-search-replay")
        val repository = FileBackedSavedSearchRepository(directory, now = { 300 })
        val multi = execution().copy(query = execution().query.withIncludeTermGroups(listOf(
            SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
            SearchTermGroup.single(SearchTerm("night")),
        )))
        val fyp = multi.copy(
            queryHash = "for_you:seed-identity",
            kind = RecentSearchKind.FYP,
            sourceTags = mapOf(SourceKey.GELBOORU to listOf("cat"), SourceKey.PIXIV to listOf("猫", "夜")),
        )
        val multiPin = repository.save("  Evening search  ", multi)
        val fypPin = repository.save("For You", fyp)
        repository.rename(multiPin.id, "Cats or dogs at night")
        repository.rename(fypPin.id, "Exact recommendation")

        val restored = FileBackedSavedSearchRepository(directory).observeSavedSearches().first()

        assertEquals(listOf("Exact recommendation", "Cats or dogs at night"), restored.map { it.name })
        assertEquals(listOf(fyp, multi), restored.map { it.search })
        assertEquals(listOf(fypPin.id, multiPin.id), restored.map { it.id })
        assertTrue(restored.all { it.savedAtEpochMs == 300L })
    }

    @Test
    fun `pinning same execution updates its name while distinct source order stays separate`() = runTest {
        val repository = FileBackedSavedSearchRepository(tempDir("pin-identity"))
        val first = repository.save("First", execution())
        val renamed = repository.save("Second", execution().copy(searchedAtEpochMs = 400))
        repository.save("Different order", execution().copy(sources = execution().sources.reversed()))

        assertEquals(first.id, renamed.id)
        assertEquals(2, repository.observeSavedSearches().first().size)
        assertEquals("Second", repository.observeSavedSearches().first().last().name)
    }

    @Test
    fun `bounded library never silently evicts pins and invalid replacement leaves it intact`() = runTest {
        val directory = tempDir("pin-bound")
        val repository = FileBackedSavedSearchRepository(directory)
        val entries = List(MAX_SAVED_SEARCHES) { index ->
            SavedSearchEntry("id-$index", "Search $index", execution().copy(queryHash = "query-$index"), 1)
        }
        repository.replaceAll(entries)

        assertTrue(runCatching { repository.save("Overflow", execution()) }.isFailure)
        assertTrue(runCatching { repository.replaceAll(listOf(entries.first().copy(name = " "))) }.isFailure)
        assertEquals(entries, repository.observeSavedSearches().first())
        assertEquals(entries, FileBackedSavedSearchRepository(directory).observeSavedSearches().first())
    }

    @Test
    fun `backup merge is idempotent preserves other pins and atomically rejects overflow`() = runTest {
        val repository = FileBackedSavedSearchRepository(tempDir("pin-backup"))
        val original = repository.save("Existing", execution())
        val imported = SavedSearchEntry("import-operation:pin", "Imported", execution().copy(queryHash = "imported"), 2)
        repository.merge(listOf(imported))
        repository.merge(listOf(imported.copy(name = "Imported renamed")))
        assertEquals(listOf("Imported renamed", "Existing"), repository.observeSavedSearches().first().map { it.name })
        assertTrue(repository.observeSavedSearches().first().any { it.id == original.id })

        val oversized = List(MAX_SAVED_SEARCHES) { index -> imported.copy(id = "next-import:$index") }
        assertTrue(runCatching { repository.merge(oversized) }.isFailure)
        assertEquals(2, repository.observeSavedSearches().first().size)
    }

    @Test
    fun `failed file write never publishes a pin or replaces the last durable library`() = runTest {
        val directory = tempDir("pin-write-failure")
        val repository = FileBackedSavedSearchRepository(directory)
        val first = repository.save("First", execution())
        val storage = directory.resolve("pinned_searches.json")
        val previousBytes = storage.readBytes()
        assertTrue(storage.delete())
        assertTrue(storage.mkdir())
        storage.resolve("block-replacement").writeText("occupied")

        assertTrue(runCatching { repository.rename(first.id, "Must not publish") }.isFailure)
        assertEquals(listOf(first), repository.observeSavedSearches().first())
        storage.deleteRecursively()
        storage.writeBytes(previousBytes)
        assertEquals(listOf(first), FileBackedSavedSearchRepository(directory).observeSavedSearches().first())
    }

    @Test
    fun `semantic corruption is quarantined before recovering an empty library`() = runTest {
        val directory = tempDir("pin-corruption")
        val storage = directory.resolve("pinned_searches.json")
        storage.writeText("""{"schemaVersion":1,"searches":[{"name":"missing execution"}]}""")
        val original = storage.readBytes()
        val recovery = LegacyJsonRecoveryRegistry()

        val repository = FileBackedSavedSearchRepository(directory, recoveryRegistry = recovery)

        assertTrue(repository.observeSavedSearches().first().isEmpty())
        assertFalse(storage.exists())
        assertTrue(File(recovery.recoveries.value.single().backupPath!!).readBytes().contentEquals(original))
    }

    @Test
    fun `concurrent saves and removal serialize without losing pins`() = runTest {
        val directory = tempDir("pin-concurrency")
        val repository = FileBackedSavedSearchRepository(directory)
        coroutineScope {
            repeat(12) { index ->
                launch(Dispatchers.Default) { repository.save("Search $index", execution().copy(queryHash = "query-$index")) }
            }
        }
        val entries = repository.observeSavedSearches().first()
        repository.remove(entries.first().id)

        assertEquals(11, repository.observeSavedSearches().first().size)
        assertEquals(entries.drop(1), FileBackedSavedSearchRepository(directory).observeSavedSearches().first())
    }

    private fun execution() = RecentSearchEntry(
        query = sampleQuery().copy(mode = QueryMode.Unified),
        queryHash = "multi-search",
        searchedAtEpochMs = 100,
        kind = RecentSearchKind.MULTI_SEARCH,
        sources = listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
    )
}
