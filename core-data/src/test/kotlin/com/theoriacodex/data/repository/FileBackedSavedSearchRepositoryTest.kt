package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.SavedSearchSnapshot
import com.theoriacodex.data.storage.SavedSearchSnapshotSerializer
import com.theoriacodex.data.storage.UnsupportedStoreSchemaException
import com.theoriacodex.data.storage.assertStoredFormat
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FileBackedSavedSearchRepositoryTest : FileBackedRepositoryTestFixture() {
    @Test
    fun `pin and rename survive restart with exact grouped Multi-Search execution`() = runTest {
        val directory = tempDir("pinned-search-replay")
        val repository = FileBackedSavedSearchRepository(directory, now = { 300 })
        val multi = execution().copy(query = execution().query.withIncludeTermGroups(listOf(
            SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
            SearchTermGroup.single(SearchTerm("night")),
        )))
        val multiPin = repository.save("  Evening search  ", multi)
        repository.rename(multiPin.id, "Cats or dogs at night")

        val restored = FileBackedSavedSearchRepository(directory).observeSavedSearches().first()

        assertEquals(listOf(SavedSearchEntry(multiPin.id, "Cats or dogs at night", multi, 300)), restored)
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
    fun `a backup's For You pin is imported as source searches`() = runTest {
        val repository = FileBackedSavedSearchRepository(tempDir("pin-backup-for-you"), sourceName = { it.id.lowercase() })

        repository.merge(goldenPins())

        assertEquals(goldenPins().withForYouSearchesConverted { it.id.lowercase() }, repository.observeSavedSearches().first())
        assertTrue(repository.observeSavedSearches().first().none { it.search.isRetiredForYouSearch })
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
        // Complete pins that fail validation (duplicate IDs), not just a structural decode failure.
        storage.writeText(goldenPinnedSearchesFile().replace("\"pin-multi\"", "\"pin-fyp\""))
        val original = storage.readBytes()
        val recovery = FileStoreRecoveryRegistry()

        val repository = FileBackedSavedSearchRepository(directory, recoveryRegistry = recovery)

        assertTrue(repository.observeSavedSearches().first().isEmpty())
        assertFalse(storage.exists())
        assertTrue(File(recovery.recoveries.value.single().backupPath!!).readBytes().contentEquals(original))
    }

    @Test
    fun `a file from a newer build is refused and left in place`() = runTest {
        val directory = tempDir("pin-newer")
        val storage = directory.resolve("pinned_searches.json")
        val newer = """{"schemaVersion":2,"searches":{"shape":"unknown to this build"}}"""
        storage.writeText(newer)
        val recovery = FileStoreRecoveryRegistry()

        assertThrows(UnsupportedStoreSchemaException::class.java) {
            FileBackedSavedSearchRepository(directory, recoveryRegistry = recovery)
        }

        assertEquals(newer, storage.readText())
        assertTrue(recovery.recoveries.value.isEmpty())
    }

    @Test
    fun `a file the Gson store wrote loads with its For You pin as source searches, rewritten once`() = runTest {
        val directory = tempDir("pin-golden")
        val storage = directory.resolve("pinned_searches.json")
        storage.writeText(goldenPinnedSearchesFile())
        val open = { FileBackedSavedSearchRepository(directory, sourceName = { it.id.lowercase() }) }

        val loaded = open().observeSavedSearches().first()

        assertEquals(goldenPins().withForYouSearchesConverted { it.id.lowercase() }, loaded)
        assertEquals(listOf("For 'you' = 猫 · gelbooru", "For 'you' = 猫 · pixiv", "Cats & dogs <night>"), loaded.map { it.name })
        assertFalse(storage.readText().contains("FYP"))
        val rewritten = storage.readText()
        assertEquals(loaded, open().observeSavedSearches().first())
        assertEquals(rewritten, storage.readText())
    }

    // The golden is the Gson store's own output.
    @Test
    fun `the pinned searches golden pins every stored key`() {
        assertStoredFormat(SavedSearchSnapshotSerializer, goldenPinnedSearchesFile() to SavedSearchSnapshot(goldenPins()))
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

/** golden/pinned-searches/gson-written.json, as FileBackedSavedSearchRepository wrote it under Gson. */
internal fun goldenPinnedSearchesFile(): String = checkNotNull(
    FileBackedSavedSearchRepositoryTest::class.java.classLoader.getResource("golden/pinned-searches/gson-written.json"),
).readText()

/** The pins behind the golden file, as it decodes: its For You pin reads as a Unified search with source tags. */
internal fun goldenPins(): List<SavedSearchEntry> {
    val tricky = "a&b=c<d>'e"
    val query = Query(
        QueryMode.Unified,
        listOf(
            SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm(tricky))),
            SearchTermGroup.single(SearchTerm("night", SearchFacet.ARTIST, "artist")),
        ),
        listOf(SearchTerm("<x>")), SortMode.NEWEST, null, null,
    )
    val multi = RecentSearchEntry(query, "multi-hash", 100, RecentSearchKind.MULTI_SEARCH, listOf(SourceKey.GELBOORU, SourceKey.PIXIV))
    val fyp = multi.copy(
        queryHash = "for_you:seed",
        kind = RecentSearchKind.UNIFIED,
        sourceTags = mapOf(SourceKey.GELBOORU to listOf(tricky), SourceKey.PIXIV to listOf("猫", "夜")),
    )
    return listOf(SavedSearchEntry("pin-fyp", "For 'you' = 猫", fyp, 300), SavedSearchEntry("pin-multi", "Cats & dogs <night>", multi, 300))
}
