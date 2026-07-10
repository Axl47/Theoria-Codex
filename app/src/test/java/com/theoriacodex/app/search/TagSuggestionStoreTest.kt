package com.theoriacodex.app.search

import com.google.gson.GsonBuilder
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TagSuggestionStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `incoming metadata overrides weaker seeded entry`() = runTest {
        val tempDir = tempDir("tag-store-test")
        val store = FileBackedTagSuggestionStore(
            storeFile = tempDir.resolve("tag_suggestions.json"),
            seedData = mapOf(
                SourceKey.PIXIV to listOf(
                    TagSuggestion(text = "landscape", type = "seed", count = null),
                )
            ),
        )

        store.put(
            source = SourceKey.PIXIV,
            suggestions = listOf(
                TagSuggestion(text = "landscape", type = "tag", count = 120),
            ),
        )

        val saved = store.get(SourceKey.PIXIV, limit = 5)
        assertEquals("tag", saved.first().type)
        assertEquals(120, saved.first().count)
        store.close()
    }

    @Test
    fun `store size respects max entries per source`() = runTest {
        val tempDir = tempDir("tag-store-cap-test")
        val store = FileBackedTagSuggestionStore(
            storeFile = tempDir.resolve("tag_suggestions.json"),
            maxEntriesPerSource = 2,
        )

        store.put(
            source = SourceKey.GELBOORU,
            suggestions = listOf(
                TagSuggestion(text = "tag_one", type = "seen", count = null),
                TagSuggestion(text = "tag_two", type = "seen", count = null),
                TagSuggestion(text = "tag_three", type = "seen", count = null),
            ),
        )

        val saved = store.get(SourceKey.GELBOORU, limit = 10)
        assertEquals(2, saved.size)
        assertTrue(saved.map { it.text }.containsAll(listOf("tag_one", "tag_two")))
        store.close()
    }

    @Test
    fun `same text in different facets and namespaces remains distinct`() = runTest {
        val tempDir = tempDir("tag-store-facet-key-test")
        val store = FileBackedTagSuggestionStore(
            storeFile = tempDir.resolve("tag_suggestions.json"),
        )

        store.putFaceted(
            source = SourceKey.NHENTAI,
            suggestions = listOf(
                FacetedTagSuggestion(
                    text = "Some Name",
                    facet = SearchFacet.TAG,
                    count = 1,
                ),
                FacetedTagSuggestion(
                    text = "Some Name",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                    count = 2,
                ),
                FacetedTagSuggestion(
                    text = "some name",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "group",
                    count = 3,
                ),
                FacetedTagSuggestion(
                    text = " SOME NAME ",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = " ARTIST ",
                    count = 7,
                ),
            ),
        )

        val saved = store.getFaceted(SourceKey.NHENTAI, limit = 10)
        assertEquals(3, saved.size)
        assertEquals(
            listOf(
                Triple(SearchFacet.TAG, null, 1),
                Triple(SearchFacet.ARTIST, "artist", 7),
                Triple(SearchFacet.ARTIST, "group", 3),
            ),
            saved.map { suggestion ->
                Triple(suggestion.facet, suggestion.sourceNamespace, suggestion.count)
            },
        )

        val legacyView = store.get(SourceKey.NHENTAI, limit = 10)
        assertEquals(listOf("Some Name"), legacyView.map(TagSuggestion::text))
        store.close()
    }

    @Test
    fun `legacy runtime metadata is cached with taxonomy while legacy reads stay tag only`() = runTest {
        val tempDir = tempDir("tag-store-runtime-taxonomy-test")
        val store = FileBackedTagSuggestionStore(
            storeFile = tempDir.resolve("tag_suggestions.json"),
        )
        store.put(
            source = SourceKey.NHENTAI,
            suggestions = listOf(
                TagSuggestion("general", type = "tag", count = 10),
                TagSuggestion("najar", type = "artist", count = 20),
                TagSuggestion("idolmaster", type = "parody", count = 30),
            ),
        )

        assertEquals(listOf("general"), store.get(SourceKey.NHENTAI, limit = 10).map(TagSuggestion::text))
        assertEquals(
            listOf(SearchFacet.TAG, SearchFacet.ARTIST, SearchFacet.SERIES),
            store.getFaceted(SourceKey.NHENTAI, limit = 10).map(FacetedTagSuggestion::facet),
        )
        store.close()
    }

    @Test
    fun `typed suggestions round trip through disk and support scoped reads`() = runTest {
        val tempDir = tempDir("tag-store-typed-round-trip-test")
        val storeFile = tempDir.resolve("tag_suggestions.json")
        val initialStore = FileBackedTagSuggestionStore(storeFile = storeFile)
        initialStore.putFaceted(
            source = SourceKey.NHENTAI,
            suggestions = listOf(
                FacetedTagSuggestion(
                    text = "sample tag",
                    facet = SearchFacet.TAG,
                    count = 12,
                ),
                FacetedTagSuggestion(
                    text = "sample artist",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                    count = 5,
                ),
            ),
        )
        initialStore.close()

        val reopenedStore = FileBackedTagSuggestionStore(storeFile = storeFile)
        reopenedStore.awaitLoaded()
        val artists = reopenedStore.getFaceted(
            source = SourceKey.NHENTAI,
            scope = FacetedSearchScope(
                facet = SearchFacet.ARTIST,
                sourceNamespace = "artist",
            ),
            limit = 10,
        )

        assertEquals(
            listOf(
                FacetedTagSuggestion(
                    text = "sample artist",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                    count = 5,
                ),
            ),
            artists,
        )
        assertTrue(storeFile.readText().contains("\"facet\": \"ARTIST\""))
        assertTrue(storeFile.readText().contains("\"sourceNamespace\": \"artist\""))
        reopenedStore.close()
    }

    @Test
    fun `legacy cache rows migrate to portable general tags`() = runTest {
        val tempDir = tempDir("tag-store-legacy-row-test")
        val storeFile = tempDir.resolve("tag_suggestions.json")
        storeFile.writeText(
            """
            {
              "sources": {
                "GELBOORU": [
                  {"text":"legacy tag","type":"trending","count":99}
                ]
              }
            }
            """.trimIndent(),
        )

        val store = FileBackedTagSuggestionStore(storeFile = storeFile)
        store.awaitLoaded()
        val saved = store.getFaceted(SourceKey.GELBOORU, limit = 10).single()

        assertEquals("legacy tag", saved.text)
        assertEquals(SearchFacet.TAG, saved.facet)
        assertNull(saved.sourceNamespace)
        assertEquals(99, saved.count)
        assertEquals("trending", store.get(SourceKey.GELBOORU, limit = 10).single().type)
        store.close()
    }

    @Test
    fun `burst mutations coalesce into one debounced atomic write`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gson = GsonBuilder().setPrettyPrinting().create()
        var writeCount = 0
        val store = FileBackedTagSuggestionStore(
            storeFile = tempDir("tag-store-debounce-test").resolve("tag_suggestions.json"),
            persistenceDebounceMs = 500L,
            workDispatcher = dispatcher,
            gson = gson,
            fileStore = AtomicJsonFileStore(ioDispatcher = dispatcher, gson = gson),
            onWriteCompleted = { writeCount += 1 },
        )
        store.awaitLoaded()

        repeat(20) { index ->
            store.put(
                source = SourceKey.GELBOORU,
                suggestions = listOf(TagSuggestion("burst_$index", type = "seen", count = null)),
            )
        }
        runCurrent()
        assertEquals(0, writeCount)

        advanceTimeBy(499L)
        runCurrent()
        assertEquals(0, writeCount)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(1, writeCount)
        store.close()
    }

    @Test
    fun `total serialized cache stays within the configured UTF-8 byte budget`() = runTest {
        val storeFile = tempDir("tag-store-byte-cap-test").resolve("tag_suggestions.json")
        val maxBytes = 1_024
        val store = FileBackedTagSuggestionStore(
            storeFile = storeFile,
            maxEntriesPerSource = 100,
            maxTotalUtf8Bytes = maxBytes,
        )
        val suggestions = (0 until 40).map { index ->
            TagSuggestion(
                text = "suggestion_${index}_" + "x".repeat(80),
                type = "trending",
                count = index,
            )
        }

        store.put(SourceKey.GELBOORU, suggestions)
        store.put(SourceKey.PIXIV, suggestions)
        store.flush()

        val retainedCount = store.get(SourceKey.GELBOORU, 100).size +
            store.get(SourceKey.PIXIV, 100).size
        assertTrue(retainedCount in 1 until suggestions.size * 2)
        assertTrue("${storeFile.length()} bytes exceeded $maxBytes", storeFile.length() <= maxBytes)
        store.close()
    }

    @Test
    fun `flush persists the latest in-memory mutation before the debounce window`() = runTest {
        val storeFile = tempDir("tag-store-flush-test").resolve("tag_suggestions.json")
        var writeCount = 0
        val store = FileBackedTagSuggestionStore(
            storeFile = storeFile,
            persistenceDebounceMs = 60_000L,
            onWriteCompleted = { writeCount += 1 },
        )
        store.put(
            source = SourceKey.RULE34GEN,
            suggestions = listOf(TagSuggestion("flush_me", type = "seen", count = null)),
        )

        store.flush()

        assertEquals(1, writeCount)
        val reopened = FileBackedTagSuggestionStore(storeFile = storeFile)
        reopened.awaitLoaded()
        assertEquals(listOf("flush_me"), reopened.get(SourceKey.RULE34GEN, 10).map(TagSuggestion::text))
        store.close()
        reopened.close()
    }

    @Test
    fun `close rejects mutations before flushing its final snapshot`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gson = GsonBuilder().setPrettyPrinting().create()
        val store = FileBackedTagSuggestionStore(
            storeFile = tempDir("tag-store-close-test").resolve("tag_suggestions.json"),
            workDispatcher = dispatcher,
            gson = gson,
            fileStore = AtomicJsonFileStore(ioDispatcher = dispatcher, gson = gson),
        )
        store.put(
            source = SourceKey.PIXIV,
            suggestions = listOf(TagSuggestion("before_close", type = "seen", count = null)),
        )

        store.requestClose()
        val failure = runCatching {
            store.put(
                source = SourceKey.PIXIV,
                suggestions = listOf(TagSuggestion("too_late", type = "seen", count = null)),
            )
        }.exceptionOrNull()
        advanceUntilIdle()

        assertTrue(failure is IllegalStateException)
        val reopened = FileBackedTagSuggestionStore(
            storeFile = tempFolder.root.resolve("tag-store-close-test/tag_suggestions.json"),
        )
        reopened.awaitLoaded()
        assertEquals(
            listOf("before_close"),
            reopened.get(SourceKey.PIXIV, 10).map(TagSuggestion::text),
        )
        reopened.close()
    }

    private fun tempDir(prefix: String): File {
        return tempFolder.newFolder(prefix)
    }
}
