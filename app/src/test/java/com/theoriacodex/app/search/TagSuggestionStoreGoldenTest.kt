package com.theoriacodex.app.search

import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.data.storage.assertStoredFormat
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * tag_suggestions.json files older builds wrote must load to the same suggestions. gson-written.json is the Gson
 * store's own output (HTML-escaped strings); legacy.json is a sparse file with a quoted count, a null source list,
 * an unknown source, and a retired key.
 */
class TagSuggestionStoreGoldenTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `a Gson-written cache loads every suggestion`() = runTest {
        withStore("gson-written.json") { store ->
            assertEquals(
                listOf(
                    TagSuggestion("a=b", "artist", 3),
                    TagSuggestion("tom_&_jerry's", "general", 12, "Tom & Jerry <cut>"),
                ),
                store.get(SourceKey.GELBOORU, 10),
            )
            assertEquals(listOf(TagSuggestion("a=b", "artist", 3)), store.getTrending(SourceKey.GELBOORU, 10))
            assertEquals(
                listOf(FacetedTagSuggestion("風景", SearchFacet.TAG, null, 99, "landscape")),
                store.getFaceted(SourceKey.PIXIV, 10),
            )
        }
    }

    @Test
    fun `a sparse legacy cache keeps what Gson kept`() = runTest {
        withStore("legacy.json") { store ->
            assertEquals(listOf(TagSuggestion("legacy", "trending", 9)), store.get(SourceKey.GELBOORU, 10))
            assertEquals(emptyList<TagSuggestion>(), store.get(SourceKey.DANBOORU, 10))
        }
    }

    // The Gson store never wrote a source namespace, so full.json, captured from the kotlinx store, sets every key.
    @Test
    fun `the full cache holds every stored key`() {
        val full = TagStoreSnapshot(
            mapOf("GELBOORU" to listOf(TagStoreEntry("a & b", "ARTIST", "artist", "artist", 3, "A <b>", listOf("SEEN", "TRENDING")))),
        )
        assertStoredFormat(golden("full.json") to full)
    }

    private fun golden(name: String) = checkNotNull(javaClass.getResource("/golden/tag-suggestions/$name")).readText()

    private suspend fun withStore(golden: String, block: (FileBackedTagSuggestionStore) -> Unit) {
        val file = tempFolder.newFolder().resolve("tag_suggestions.json")
        file.writeText(golden(golden))
        val recoveries = LegacyJsonRecoveryRegistry()
        val store = FileBackedTagSuggestionStore(file, persistenceDebounceMs = 0L, recoveryRegistry = recoveries)
        try {
            store.awaitLoaded()
            block(store)
            assertTrue(recoveries.recoveries.value.isEmpty())
        } finally {
            store.close()
        }
    }
}
