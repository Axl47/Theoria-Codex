package com.theoriacodex.app.codex

import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSaveRelevanceTest {
    @Test fun `small search specialist beats a large common-tag collection`() {
        val relevance = CodexSaveRelevance(listOf(post("incoming", listOf("yellow", "common", "popular"))), query("yellow"))
        val specialized = collection(10, listOf("yellow"))
        val generic = collection(200, listOf("common", "popular"))
        assertTrue(relevance.score(specialized) > relevance.score(generic))
        assertEquals(0.95, relevance.score(specialized), 0.000001)
        assertEquals(0.05, relevance.score(generic), 0.000001)
        assertEquals(relevance.score(specialized), relevance.score(collection(200, listOf("yellow"))), 0.000001)
    }

    @Test fun `all required groups must co-occur on a post and OR alternatives are equivalent`() {
        val query = query("yellow").withIncludeTermGroups(listOf(
            SearchTermGroup.single(SearchTerm("yellow")),
            SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
        ))
        val relevance = CodexSaveRelevance(listOf(post("incoming", listOf("yellow", "cat", "common"))), query)
        val full = relevance.score(collection(10, listOf("yellow", "dog")))
        val split = relevance.score(collection(100, listOf("yellow")) + collection(100, listOf("cat"), "other"))
        val partial = relevance.score(collection(200, listOf("yellow")))
        assertEquals(0.95, full, 0.000001)
        assertEquals(full, relevance.score(collection(10, listOf("yellow", "cat"))), 0.000001)
        assertEquals(0.1, split, 0.000001)
        assertEquals(split, partial, 0.000001)
        assertTrue(full > split)
    }

    @Test fun `no context normalizes incidental overlap and counts each saved post and tag once`() {
        val incoming = post("incoming", listOf("Blue Hair", "cat", "cat", " "))
        val relevance = CodexSaveRelevance(listOf(incoming, incoming))
        val one = post("one", listOf("blue_hair", "CAT", "cat"))
        val two = post("two", listOf("cat"))
        assertEquals(0.75, relevance.score(listOf(one, two, one)), 0.000001)
        assertEquals(1.0, relevance.score(listOf(one.copy(id = PostId(SourceKey.PIXIV, "one")))), 0.000001)
        assertTrue(relevance.score(collection(10, listOf("blue_hair", "cat"))) >
            relevance.score(collection(200, listOf("cat"))))
        assertEquals(0.0, relevance.score(emptyList()), 0.000001)
    }

    @Test fun `missing tags use available search context and raw tags remain a fallback`() {
        val empty = post("empty", emptyList())
        assertFalse(CodexSaveRelevance(listOf(empty)).hasSignals)
        val relevance = CodexSaveRelevance(listOf(empty), query("yellow"))
        assertTrue(relevance.hasSignals)
        assertEquals(0.95, relevance.score(collection(10, listOf("yellow"))), 0.000001)
        val incoming = empty.copy(rawTags = listOf("cat"))
        assertEquals(1.0, CodexSaveRelevance(listOf(incoming)).score(collection(10, listOf("cat"))), 0.000001)
        assertEquals(0.0, CodexSaveRelevance(listOf(empty)).score(collection(10, listOf("cat"))), 0.000001)
    }

    @Test fun `typed search terms keep facet and namespace identity and exclusions do not boost matches`() {
        val query = emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm("yellow", SearchFacet.ARTIST, "artist")))
            .copy(excludeTerms = listOf(SearchTerm("cat")))
        val relevance = CodexSaveRelevance(listOf(post("incoming", listOf("common"))), query)
        val artist = post("artist", emptyList()).copy(taxonomy = listOf(PostTaxonomyTerm("yellow", SearchFacet.ARTIST, "artist")))
        assertEquals(0.95, relevance.score(listOf(artist)), 0.000001)
        assertEquals(0.0, relevance.score(listOf(post("tag", listOf("yellow", "cat")))), 0.000001)
    }

    private fun query(vararg tags: String) = emptySearchQuery().withIncludeTermsAsRequired(tags.map(::SearchTerm))
    private fun collection(count: Int, tags: List<String>, prefix: String = "saved") =
        (1..count).map { post("$prefix-$it", tags) }

    private fun post(id: String, tags: List<String>) = Post(
        id = PostId(SourceKey.GELBOORU, id), preview = ImageRef(null, null, null), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = tags, rawTags = tags,
        authorName = null, createdAtEpochMs = null,
    )
}
