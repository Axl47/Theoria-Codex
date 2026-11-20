package com.theoriacodex.app.search

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TagSuggestionStoreSemanticsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `close writes pending changes and rejects later mutations`() = runTest {
        val storeFile = storeFile()
        val store = FileBackedTagSuggestionStore(storeFile, persistenceDebounceMs = 60_000L)
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("closed_in", "seen", null)))

        store.close()
        store.close()
        assertTrue(store.runCatching { put(SourceKey.GELBOORU, listOf(TagSuggestion("late", "seen", null))) }
            .exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("closed_in"), reopen(storeFile).get(SourceKey.GELBOORU, 10).map { it.text })
    }

    @Test
    fun `flush writes only when something changed since the last write`() = runTest {
        var writes = 0
        val store = FileBackedTagSuggestionStore(storeFile(), persistenceDebounceMs = 60_000L, onWriteCompleted = { writes++ })
        store.flush()
        assertEquals(0, writes)
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("a", "seen", null)))
        store.flush()
        store.flush()
        store.put(SourceKey.GELBOORU, emptyList())
        store.flush()
        assertEquals(1, writes)

        store.replaceTrending(SourceKey.GELBOORU, emptyList())
        store.flush()
        assertEquals(1, writes)
        store.replaceTrending(SourceKey.GELBOORU, listOf(TagSuggestion("hot", "trending", null)))
        store.flush()
        assertEquals(2, writes)
        store.close()
    }

    @Test
    fun `non-positive limits and blank prefixes return nothing while a limit of one returns one`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile())
        val tags = listOf("alpha", "alphabet").map { TagSuggestion(it, "trending", null) }
        store.put(SourceKey.GELBOORU, tags)
        store.putFaceted(SourceKey.GELBOORU, listOf(FacetedTagSuggestion("alpha one", SearchFacet.TAG)))
        val all = FacetedSearchScope()

        listOf(0, -1).forEach { limit ->
            assertEquals(emptyList<TagSuggestion>(), store.get(SourceKey.GELBOORU, limit))
            assertEquals(emptyList<TagSuggestion>(), store.find(SourceKey.GELBOORU, "alpha", limit))
            assertEquals(emptyList<TagSuggestion>(), store.getTrending(SourceKey.GELBOORU, limit))
            assertEquals(emptyList<FacetedTagSuggestion>(), store.getFaceted(SourceKey.GELBOORU, limit, all))
            assertEquals(emptyList<FacetedTagSuggestion>(), store.findFaceted(SourceKey.GELBOORU, "alpha", limit, all))
        }
        // A source nothing was ever stored for reads as empty.
        assertEquals(emptyList<TagSuggestion>(), store.get(SourceKey.PIXIV, 5) + store.find(SourceKey.PIXIV, "alpha", 5) + store.getTrending(SourceKey.PIXIV, 5))
        assertEquals(emptyList<FacetedTagSuggestion>(), store.getFaceted(SourceKey.PIXIV, 5, all) + store.findFaceted(SourceKey.PIXIV, "alpha", 5, all))
        assertEquals(emptyList<TagSuggestion>(), store.find(SourceKey.GELBOORU, "  ", 5))
        assertEquals(emptyList<FacetedTagSuggestion>(), store.findFaceted(SourceKey.GELBOORU, "", 5, all))
        assertEquals(1, store.get(SourceKey.GELBOORU, 1).size)
        assertEquals(1, store.find(SourceKey.GELBOORU, "alpha", 1).size)
        assertEquals(1, store.getTrending(SourceKey.GELBOORU, 1).size)
        assertEquals(1, store.getFaceted(SourceKey.GELBOORU, 1, all).size)
        assertEquals(1, store.findFaceted(SourceKey.GELBOORU, "alpha", 1, all).size)
        store.close()
    }

    @Test
    fun `prefix lookup ignores case and underscores and also matches the translation`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile())
        store.put(SourceKey.PIXIV, listOf(TagSuggestion("Blue_Sky", "tag", null), TagSuggestion("初音ミク", "tag", null, alternateText = "Hatsune Miku")))
        store.putFaceted(SourceKey.PIXIV, listOf(FacetedTagSuggestion("Blue_Sky", SearchFacet.TAG, sourceNamespace = "extra"),
            FacetedTagSuggestion("初音ミク", SearchFacet.TAG, alternateText = "Hatsune Miku")))

        assertEquals(listOf("Blue_Sky"), store.find(SourceKey.PIXIV, "BLUE sky", 10).map { it.text })
        assertEquals(listOf("初音ミク"), store.find(SourceKey.PIXIV, "hatsune", 10).map { it.text })
        // The faceted search matches the label only, not the translation.
        assertEquals(emptyList<String>(), store.findFaceted(SourceKey.PIXIV, "hatsune", 10, FacetedSearchScope()).map { it.text })
        assertEquals(2, store.findFaceted(SourceKey.PIXIV, "blue sky", 10, FacetedSearchScope()).size)
        store.close()
    }

    @Test
    fun `get hides case duplicates and non-tag facets while getTrending keeps only trending tags`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile())
        val tags = listOf("Dup" to "tag", "dup" to "female", "some artist" to "artist", "hot" to "trending")
        store.put(SourceKey.NHENTAI, tags.map { (text, type) -> TagSuggestion(text, type, null) })
        store.put(SourceKey.NHENTAI, listOf(TagSuggestion("hot artist", "artist", null)), TagSuggestionOrigin.TRENDING)

        // "Dup" (tag) and "dup" (female) are distinct entries, but get() lists each spelling once.
        assertEquals(listOf("Dup", "hot"), store.get(SourceKey.NHENTAI, 10).map { it.text })
        assertEquals(listOf("hot"), store.getTrending(SourceKey.NHENTAI, 10).map { it.text })
        store.close()
    }

    @Test
    fun `scopes narrow faceted reads by facet and namespace and legacy types map to nhentai namespaces`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile())
        val types = listOf("artist", "character", "series", "parody", "group", "type", "category", "language", "female", "male", "tag", "oddity", " Parody ")
        store.put(SourceKey.NHENTAI, types.mapIndexed { index, type -> TagSuggestion("x$index", type, null) })
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("plain", "artist", null)))

        fun scoped(facet: SearchFacet?, namespace: String? = null) =
            store.getFaceted(SourceKey.NHENTAI, 50, FacetedSearchScope(facet, namespace)).map { it.text to it.sourceNamespace }

        assertEquals(types.size, scoped(null).size)
        assertEquals(listOf("x0" to "artist"), scoped(SearchFacet.ARTIST))
        assertEquals(listOf("x1" to "character"), scoped(SearchFacet.CHARACTER))
        assertEquals(listOf("x2" to "series", "x3" to "parody", "x12" to "parody"), scoped(SearchFacet.SERIES))
        assertEquals(listOf("x3" to "parody", "x12" to "parody"), scoped(SearchFacet.SERIES, "parody"))
        assertEquals(listOf("x4" to "group"), scoped(SearchFacet.GROUP))
        assertEquals(listOf("x5" to "type", "x6" to "category"), scoped(SearchFacet.TYPE))
        assertEquals(listOf("x6" to "category"), scoped(SearchFacet.TYPE, "category"))
        assertEquals(listOf("x7" to "language"), scoped(SearchFacet.LANGUAGE))
        assertEquals(listOf("x8" to "female", "x9" to "male", "x10" to "tag", "x11" to null), scoped(SearchFacet.TAG))
        assertEquals(listOf("x9" to "male"), scoped(SearchFacet.TAG, "male"))
        assertEquals(emptyList<Pair<String, String?>>(), scoped(SearchFacet.ARTIST, "group"))
        // Other sources have no namespaces: their type is just metadata.
        assertEquals(listOf("plain" to null), store.getFaceted(SourceKey.GELBOORU, 5, FacetedSearchScope(SearchFacet.TAG)).map { it.text to it.sourceNamespace })
        assertEquals(emptyList<FacetedTagSuggestion>(), store.getFaceted(SourceKey.GELBOORU, 5, FacetedSearchScope(SearchFacet.ARTIST)))
        store.close()
    }

    @Test
    fun `entries are trimmed and blank or self-translated ones are dropped`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile())
        store.put(SourceKey.PIXIV, listOf(TagSuggestion("  Foo ", "tag", null, alternateText = "foo"), TagSuggestion("   ", "tag", null),
            TagSuggestion("bar", "tag", null, alternateText = "  "), TagSuggestion("baz", "tag", null, alternateText = " Bazaar ")))
        store.putFaceted(SourceKey.PIXIV, listOf(FacetedTagSuggestion("qux", SearchFacet.CHARACTER, sourceNamespace = "  Character "),
            FacetedTagSuggestion("blank ns", SearchFacet.CHARACTER, sourceNamespace = "  ")))

        assertEquals(listOf("Foo" to null, "bar" to null, "baz" to "Bazaar"),
            store.get(SourceKey.PIXIV, 10).map { it.text to it.alternateText })
        assertEquals(listOf("qux" to "character", "blank ns" to null),
            store.getFaceted(SourceKey.PIXIV, 10, FacetedSearchScope(SearchFacet.CHARACTER)).map { it.text to it.sourceNamespace })
        store.close()
    }

    @Test
    fun `merging keeps the highest count, the highest ranked type and the first translation whatever the arrival order`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile())
        val ranked = listOf("tag_count_lookup", "trending", "tag", "seen", "pixiv_tags_page", "seed", null)
        ranked.zipWithNext().forEachIndexed { index, (higher, lower) ->
            store.put(SourceKey.GELBOORU, listOf(TagSuggestion("up$index", lower, null), TagSuggestion("down$index", higher, null)))
            store.put(SourceKey.GELBOORU, listOf(TagSuggestion("up$index", higher, null), TagSuggestion("down$index", lower, null)))
            assertEquals("$higher over $lower", listOf(higher, higher), listOf("up$index", "down$index").map { name ->
                store.get(SourceKey.GELBOORU, 100).single { it.text == name }.type
            })
        }
        // Equal ranks keep the incoming spelling.
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("tie", "custom", null)))
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("tie", "pixiv_tags_page", null)))
        assertEquals("pixiv_tags_page", store.get(SourceKey.GELBOORU, 100).single { it.text == "tie" }.type)
        // An unlisted type outranks "seed" but not "tag".
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("odd", "seed", null), TagSuggestion("odd2", "tag", null)))
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("odd", "custom", null), TagSuggestion("odd2", "custom", null)))

        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("n", "tag", 5, alternateText = "first"), TagSuggestion("m", "tag", null)))
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("n", "tag", 9, alternateText = "second"), TagSuggestion("m", "tag", 4)))
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("n", "tag", 2), TagSuggestion("m", "tag", null)))

        val byText = store.get(SourceKey.GELBOORU, 100).associateBy { it.text }
        assertEquals("custom", byText.getValue("odd").type)
        assertEquals("tag", byText.getValue("odd2").type)
        assertEquals(9, byText.getValue("n").count)
        assertEquals(4, byText.getValue("m").count)
        assertEquals("second", byText.getValue("n").alternateText)
        store.close()
    }

    @Test
    fun `counts are reported per source key only for recommendation tags that carry one`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile())
        store.put(SourceKey.NHENTAI, listOf(TagSuggestion("big one", "female", 7), TagSuggestion("plain", "tag", 3),
            TagSuggestion("no count", "male", null), TagSuggestion("somebody", "artist", 9)))
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("Blue Sky", "tag", 12)))

        assertEquals(7, store.getCount(SourceKey.NHENTAI, "female:big one"))
        assertEquals(3, store.getCount(SourceKey.NHENTAI, "plain"))
        assertNull(store.getCount(SourceKey.NHENTAI, "male:no count"))
        assertNull(store.getCount(SourceKey.NHENTAI, "artist:somebody"))
        assertNull(store.getCount(SourceKey.NHENTAI, "big one"))
        assertEquals(12, store.getCount(SourceKey.GELBOORU, "blue_sky"))
        assertNull(store.getCount(SourceKey.PIXIV, "plain"))
        store.close()
    }

    @Test
    fun `trending replacement swaps trending membership but keeps entries known another way`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile())
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("also_seen", "seen", null)))
        store.put(SourceKey.GELBOORU, listOf(TagSuggestion("also_seen", "trending", null), TagSuggestion("gone", "trending", null)))
        store.replaceTrending(SourceKey.GELBOORU, emptyList())
        assertEquals(listOf("also_seen", "gone"), store.getTrending(SourceKey.GELBOORU, 10).map { it.text })

        store.replaceTrending(SourceKey.GELBOORU, listOf(TagSuggestion("fresh", "tag", null)))

        assertEquals(listOf("fresh"), store.getTrending(SourceKey.GELBOORU, 10).map { it.text })
        assertEquals(setOf("also_seen", "fresh"), store.get(SourceKey.GELBOORU, 10).map { it.text }.toSet())
        store.replaceTrending(SourceKey.PIXIV, listOf(TagSuggestion("first", "tag", null)))
        assertEquals(listOf("first"), store.getTrending(SourceKey.PIXIV, 10).map { it.text })
        store.close()
        assertTrue(runCatching { store.replaceTrending(SourceKey.GELBOORU, listOf(TagSuggestion("x", "tag", null))) }
            .exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `origins follow the legacy type when none is given and survive a reload`() = runTest {
        val file = storeFile()
        val store = FileBackedTagSuggestionStore(file)
        val inferred = mapOf("trending" to "TRENDING", "seen" to "SEEN", "seed" to "SEED", "pixiv_tags_page" to "SEED",
            "tag_count_lookup" to "COUNT_LOOKUP", " Trending " to "TRENDING", "other" to "AUTOCOMPLETE")
        store.put(SourceKey.GELBOORU, inferred.keys.mapIndexed { i, type -> TagSuggestion("t$i", type, null) })
        store.close()

        val stored = DurableJson.decodeFromString(TagStoreSnapshot.serializer(), file.readText())
            .sources.orEmpty().getValue("GELBOORU").orEmpty().map { it?.origins?.single() }
        assertEquals(inferred.values.toList(), stored)
        assertEquals(listOf("t0", "t5"), reopen(file).getTrending(SourceKey.GELBOORU, 10).map { it.text })
    }

    @Test
    fun `loading skips damaged rows and falls back to defaults for missing fields`() = runTest {
        val file = storeFile()
        file.writeText(
            """
            {"sources": {
              "GELBOORU": [
                null,
                {"text": "  ", "type": "tag"},
                {"text": "bad facet", "facet": "NOPE"},
                {"text": "lower facet", "facet": "artist", "sourceNamespace": " Artist "},
                {"text": "trended", "type": "trending", "origins": ["BOGUS"]},
                {"text": "explicit", "type": "trending", "origins": ["SEEN", "BOGUS"]},
                {"text": "plain", "count": 4},
                {"text": "dropped by cap"}
              ],
              "NOT A SOURCE": [{"text": "orphan"}],
              "PIXIV": null
            }}
            """.trimIndent(),
        )
        val store = FileBackedTagSuggestionStore(file, maxEntriesPerSource = 4)
        store.awaitLoaded()

        assertEquals(
            listOf("lower facet" to SearchFacet.ARTIST, "trended" to SearchFacet.TAG, "explicit" to SearchFacet.TAG, "plain" to SearchFacet.TAG),
            store.getFaceted(SourceKey.GELBOORU, 50, FacetedSearchScope()).map { it.text to it.facet },
        )
        assertEquals("artist", store.getFaceted(SourceKey.GELBOORU, 1, FacetedSearchScope()).single().sourceNamespace)
        assertEquals(listOf("trended"), store.getTrending(SourceKey.GELBOORU, 10).map { it.text })
        assertEquals(4, store.getCount(SourceKey.GELBOORU, "plain"))
        assertEquals(emptyList<TagSuggestion>(), store.get(SourceKey.PIXIV, 10))
        store.close()
    }

    @Test
    fun `disk rows merge into seeds and the cache cap applies per source`() = runTest {
        val file = storeFile()
        file.writeText("""{"sources":{"PIXIV":[{"text":"shared","type":"tag","count":50},{"text":"disk_only"},{"text":"overflow"}]}}""")
        val store = FileBackedTagSuggestionStore(
            file,
            seedData = mapOf(SourceKey.PIXIV to listOf(TagSuggestion("shared", "seed", 10), TagSuggestion("seed_only", "seed", null))),
            maxEntriesPerSource = 3,
        )
        store.awaitLoaded()

        val saved = store.get(SourceKey.PIXIV, 10)
        assertEquals(3, saved.size)
        assertEquals(50, saved.single { it.text == "shared" }.count)
        assertEquals("tag", saved.single { it.text == "shared" }.type)
        store.close()
    }

    @Test
    fun `a snapshot over budget is trimmed on load and rewritten smaller`() = runTest {
        val file = storeFile()
        val big = FileBackedTagSuggestionStore(file, maxEntriesPerSource = 100, maxTotalUtf8Bytes = 64 * 1024)
        big.put(SourceKey.GELBOORU, longTags(40))
        big.close()
        val before = file.length()

        val small = FileBackedTagSuggestionStore(file, maxEntriesPerSource = 100, maxTotalUtf8Bytes = 1_024)
        small.awaitLoaded()
        small.close()

        assertTrue("${file.length()} not under $before", file.length() < before)
        assertTrue(file.length() <= 1_024)
    }

    @Test
    fun `byte budget evicts from the largest source and from the end of its list`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile(), maxEntriesPerSource = 100, maxTotalUtf8Bytes = 3_000)
        val tags = longTags(30)
        store.put(SourceKey.GELBOORU, tags)
        store.put(SourceKey.PIXIV, listOf("p1", "p2", "p3").map { TagSuggestion(it, "tag", null) })

        val kept = store.get(SourceKey.GELBOORU, 100).map { it.text }
        assertEquals(tags.map { it.text }.take(kept.size), kept)
        assertTrue(kept.size in 4 until 30)
        assertEquals(listOf("p1", "p2", "p3"), store.get(SourceKey.PIXIV, 10).map { it.text })
        store.close()
    }

    @Test
    fun `replacing a source with identical rows never counts them twice against the budget`() = runTest {
        val store = FileBackedTagSuggestionStore(storeFile(), maxEntriesPerSource = 100, maxTotalUtf8Bytes = 3_000)
        val tags = (0 until 10).map { TagSuggestion("tag_$it", "tag", it) }
        repeat(6) { store.put(SourceKey.GELBOORU, tags) }

        assertEquals(10, store.get(SourceKey.GELBOORU, 100).size)
        store.close()
    }

    @Test
    fun `invalid configuration is rejected`() = runTest {
        val file = storeFile()
        listOf<() -> Any>({ FileBackedTagSuggestionStore(file, maxEntriesPerSource = 0) }, { FileBackedTagSuggestionStore(file, maxTotalUtf8Bytes = 10) },
            { FileBackedTagSuggestionStore(file, persistenceDebounceMs = -1L) }).forEach { create ->
            assertTrue(runCatching { create() }.exceptionOrNull() is IllegalArgumentException)
        }
        // The smallest allowed budget and a zero debounce are valid.
        FileBackedTagSuggestionStore(file, maxEntriesPerSource = 1, maxTotalUtf8Bytes = 256, persistenceDebounceMs = 0L).close()
    }

    private fun longTags(count: Int) = (0 until count).map { TagSuggestion("tag_${it}_" + "x".repeat(80), "tag", it + 1) }

    private fun storeFile(): File = tempFolder.newFolder().resolve("tag_suggestions.json")

    private suspend fun reopen(file: File) = FileBackedTagSuggestionStore(file).also { it.awaitLoaded() }
}
