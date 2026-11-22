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
import com.theoriacodex.domain.vision.PredictedTag
import com.theoriacodex.domain.vision.PredictedTagIndex
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
        // Every incoming tag on all 200 posts: full density but for the three posts every Codex is padded by.
        assertEquals(0.05 * 400.0 / 203 / 2, relevance.score(generic), 0.000001)
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
        // Three matches of two tags over two posts, padded by three: duplicates count once.
        assertEquals(3.0 / 5 / 2, relevance.score(listOf(one, two, one)), 0.000001)
        assertEquals(2.0 / 4 / 2, relevance.score(listOf(one.copy(id = PostId(SourceKey.PIXIV, "one")))), 0.000001)
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
        assertEquals(10.0 / 13, CodexSaveRelevance(listOf(incoming)).score(collection(10, listOf("cat"))), 0.000001)
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

    @Test fun `a rare shared tag outweighs many common ones, and one lucky match does not make a tiny Codex lead`() {
        val relevance = CodexSaveRelevance(listOf(post("incoming", listOf("miku", "1girl", "blush"))))
        val library = mapOf(
            "miku" to collection(6, listOf("miku", "1girl"), "miku"),
            "generic" to collection(40, listOf("1girl", "blush"), "generic"),
            "lucky" to collection(1, listOf("miku"), "lucky"),
        )

        val rarity = TagRarity.of(library.values.flatten())
        val scores = library.mapValues { (_, saved) -> relevance.score(saved, rarity) }

        assertTrue(scores.getValue("miku") > scores.getValue("generic"))
        assertTrue(scores.getValue("miku") > scores.getValue("lucky"))
        // Without the library's rarity every tag weighs alike, and the generic Codex wins on two shared tags.
        assertTrue(relevance.score(library.getValue("generic")) > relevance.score(library.getValue("miku")))
    }

    @Test fun `predicted tags match a Codex whose posts were tagged in another language or not at all`() {
        val incoming = post("incoming", emptyList())
        val untaggedFoxes = collection(3, emptyList(), prefix = "fox")
        val untaggedCats = collection(3, emptyList(), prefix = "cat")
        fun guess(tag: String, score: Float) = listOf(PredictedTag(tag, score, PredictedTag.Kind.GENERAL))
        val predicted = PredictedTagIndex(
            byPost = mapOf(incoming.id to guess("fox_ears", 0.9f)) +
                untaggedFoxes.associate { it.id to guess("fox_ears", 0.8f) } + untaggedCats.associate { it.id to guess("cat_ears", 0.8f) },
        )
        val relevance = CodexSaveRelevance(listOf(incoming), predicted = predicted)
        val rarity = TagRarity.of(untaggedFoxes + untaggedCats, predicted)

        assertTrue(relevance.hasSignals)
        assertTrue(relevance.score(untaggedFoxes, rarity) > 0.0)
        assertEquals(0.0, relevance.score(untaggedCats, rarity), 0.0)
        // Below the evidence threshold a prediction says nothing.
        assertFalse(CodexSaveRelevance(listOf(incoming), predicted = PredictedTagIndex(mapOf(incoming.id to guess("fox_ears", 0.4f)))).hasSignals)
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
