package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.QueryHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ForYouSavedSearchConversionTest {
    private val sourceName = { source: SourceKey -> source.id.lowercase() }

    @Test
    fun `a pinned shelf becomes the source search it ran, keeping its name and identity`() {
        val shelf = saved("shelf", "Forest", mapOf(SourceKey.PIXIV to listOf("forest", "night")))

        val converted = listOf(shelf).withForYouSearchesConverted(sourceName).single()

        val query = sourceQuery(SourceKey.PIXIV, listOf("forest", "night"))
        assertEquals(SavedSearchEntry("shelf", "Forest", RecentSearchEntry(query, QueryHash.from(query), 100), 300), converted)
        assertEquals(RecentSearchKind.SOURCE, converted.search.kind)
        assertEquals(listOf(SourceKey.PIXIV), converted.search.sources)
    }

    @Test
    fun `a saved mix splits into one search per source, named for its source`() {
        val mix = saved("mix", "Evening", linkedMapOf(SourceKey.GELBOORU to listOf("sky"), SourceKey.PIXIV to listOf("空")))

        val converted = listOf(mix).withForYouSearchesConverted(sourceName)

        assertEquals(listOf("Evening · gelbooru", "Evening · pixiv"), converted.map { it.name })
        assertEquals(listOf(sourceQuery(SourceKey.GELBOORU, listOf("sky")), sourceQuery(SourceKey.PIXIV, listOf("空"))),
            converted.map { it.search.query })
        assertEquals("mix", converted.first().id)
        assertEquals(converted, listOf(mix).withForYouSearchesConverted(sourceName))
    }

    @Test
    fun `a For You search without tags is dropped and other searches are untouched`() {
        val other = SavedSearchEntry("other", "Other", RecentSearchEntry(sourceQuery(SourceKey.PIXIV, listOf("a")), "h", 1), 1)
        val empty = saved("empty", "Empty", mapOf(SourceKey.PIXIV to emptyList(), SourceKey.GELBOORU to listOf(" ")))

        assertEquals(listOf(other), listOf(empty, other).withForYouSearchesConverted(sourceName))
    }

    @Test
    fun `split searches take only free slots, so every existing search stays`() {
        val existing = List(MAX_SAVED_SEARCHES - 2) { index ->
            SavedSearchEntry("id-$index", "Search $index", RecentSearchEntry(sourceQuery(SourceKey.PIXIV, listOf("t$index")), "h$index", 1), 1)
        }
        val mix = saved("mix", "Mix", linkedMapOf(
            SourceKey.GELBOORU to listOf("a"), SourceKey.PIXIV to listOf("b"), SourceKey.DANBOORU to listOf("c"),
        ))

        val converted = (listOf(mix) + existing).withForYouSearchesConverted(sourceName)

        assertEquals(MAX_SAVED_SEARCHES, converted.size)
        assertEquals(listOf("Mix · gelbooru", "Mix · pixiv"), converted.take(2).map { it.name })
        assertEquals(existing, converted.drop(2))
    }

    @Test
    fun `an ordinary search wins a collision with a converted one, whichever comes first`() {
        val query = sourceQuery(SourceKey.PIXIV, listOf("forest"))
        val ordinary = SavedSearchEntry("mine", "My forest", RecentSearchEntry(query, QueryHash.from(query), 5), 7)
        val shelf = saved("shelf", "Forest", mapOf(SourceKey.PIXIV to listOf("forest")))
        val sameId = SavedSearchEntry("shelf", "Other", RecentSearchEntry(sourceQuery(SourceKey.PIXIV, listOf("x")), "h", 1), 1)

        assertEquals(listOf(ordinary), listOf(shelf, ordinary).withForYouSearchesConverted(sourceName))
        assertEquals(listOf(ordinary), listOf(ordinary, shelf).withForYouSearchesConverted(sourceName))
        assertEquals(listOf(sameId), listOf(shelf, sameId).withForYouSearchesConverted(sourceName))
    }

    @Test
    fun `a mix that loses one source's search to an ordinary one keeps the other`() {
        val query = sourceQuery(SourceKey.GELBOORU, listOf("sky"))
        val ordinary = SavedSearchEntry("mine", "Sky", RecentSearchEntry(query, QueryHash.from(query), 5), 7)
        val mix = saved("mix", "Evening", linkedMapOf(SourceKey.GELBOORU to listOf("sky"), SourceKey.PIXIV to listOf("空")))

        val converted = listOf(mix, ordinary).withForYouSearchesConverted(sourceName)

        assertEquals(listOf("Evening · pixiv", "Sky"), converted.map { it.name })
        assertEquals(ordinary, converted.last())
    }

    @Test
    fun `a library without For You searches comes back as it is`() {
        val library = listOf(SavedSearchEntry("a", "A", RecentSearchEntry(sourceQuery(SourceKey.PIXIV, listOf("a")), "h", 1), 1))

        assertSame(library, library.withForYouSearchesConverted(sourceName))
    }

    private fun saved(id: String, name: String, tags: Map<SourceKey, List<String>>) = SavedSearchEntry(
        id = id,
        name = name,
        search = RecentSearchEntry(
            query = Query(QueryMode.Unified, tags.values.flatten().filter(String::isNotBlank), emptyList(),
                SortMode.TOP, null, null).copy(media = MediaFilter.ANIMATED),
            queryHash = "for_you:$id",
            searchedAtEpochMs = 100,
            kind = RecentSearchKind.UNIFIED,
            sources = tags.keys.toList(),
            sourceTags = tags,
        ),
        savedAtEpochMs = 300,
    )

    private fun sourceQuery(source: SourceKey, tags: List<String>) =
        Query(QueryMode.Source(source), tags, emptyList(), SortMode.TOP, null, null).copy(media = MediaFilter.ANIMATED)
}
