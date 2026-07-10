package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TagSuggestionStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `incoming metadata overrides weaker seeded entry`() {
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
    }

    @Test
    fun `store size respects max entries per source`() {
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
    }

    @Test
    fun `same text in different facets and namespaces remains distinct`() {
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
    }

    @Test
    fun `legacy runtime metadata is cached with taxonomy while legacy reads stay tag only`() {
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
    }

    @Test
    fun `typed suggestions round trip through disk and support scoped reads`() {
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

        val reopenedStore = FileBackedTagSuggestionStore(storeFile = storeFile)
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
        assertTrue(storeFile.readText().contains("\"facet\":\"ARTIST\""))
        assertTrue(storeFile.readText().contains("\"sourceNamespace\":\"artist\""))
    }

    @Test
    fun `legacy cache rows migrate to portable general tags`() {
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
        val saved = store.getFaceted(SourceKey.GELBOORU, limit = 10).single()

        assertEquals("legacy tag", saved.text)
        assertEquals(SearchFacet.TAG, saved.facet)
        assertNull(saved.sourceNamespace)
        assertEquals(99, saved.count)
        assertEquals("trending", store.get(SourceKey.GELBOORU, limit = 10).single().type)
    }

    private fun tempDir(prefix: String): File {
        return tempFolder.newFolder(prefix)
    }
}
