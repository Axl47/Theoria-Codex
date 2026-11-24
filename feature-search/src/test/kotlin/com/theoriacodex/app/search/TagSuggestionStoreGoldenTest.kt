package com.theoriacodex.app.search

import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.assertStoredFormat
import com.theoriacodex.domain.adapter.FacetedSearchScope
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

class TagSuggestionStoreGoldenTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `a Gson-written cache loads every suggestion (ADR 0034)`() = runTest {
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
                store.getFaceted(SourceKey.PIXIV, 10, FacetedSearchScope.All),
            )
        }
    }

    @Test
    fun `a sparse legacy cache keeps what Gson kept (ADR 0034)`() = runTest {
        withStore("legacy.json") { store ->
            assertEquals(listOf(TagSuggestion("legacy", "trending", 9)), store.get(SourceKey.GELBOORU, 10))
            assertEquals(emptyList<TagSuggestion>(), store.get(SourceKey.DANBOORU, 10))
        }
    }

    @Test
    fun `the full cache holds every stored key (ADR 0034)`() {
        val full = TagStoreSnapshot(
            mapOf("GELBOORU" to listOf(TagStoreEntry("a & b", "ARTIST", "artist", "artist", 3, "A <b>", listOf("SEEN", "TRENDING")))),
        )
        assertStoredFormat(golden("full.json") to full)
    }

    private fun golden(name: String) = checkNotNull(javaClass.getResource("/golden/tag-suggestions/$name")).readText()

    private suspend fun withStore(golden: String, block: (FileBackedTagSuggestionStore) -> Unit) {
        val file = tempFolder.newFolder().resolve("tag_suggestions.json")
        file.writeText(golden(golden))
        val recoveries = FileStoreRecoveryRegistry()
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
