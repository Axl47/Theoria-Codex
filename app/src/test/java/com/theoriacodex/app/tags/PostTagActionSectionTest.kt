package com.theoriacodex.app.tags

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTagActionSectionTest {
    @Test
    fun `typed post actions keep same text facets distinct and group them clearly`() {
        val tag = SearchTerm("najar", SearchFacet.TAG, "tag")
        val artist = SearchTerm("najar", SearchFacet.ARTIST, "artist")
        val female = SearchTerm("x-ray", SearchFacet.TAG, "female")
        val language = SearchTerm("japanese", SearchFacet.LANGUAGE, "language")
        val post = samplePost(
            taxonomy = listOf(
                tag.toTaxonomyTerm(),
                artist.toTaxonomyTerm(),
                tag.toTaxonomyTerm(),
                language.toTaxonomyTerm(),
                female.toTaxonomyTerm(),
            ),
        )

        assertEquals(listOf(tag, artist, language, female), postActionTerms(post))
        assertEquals(
            listOf("Tags", "Female tags", "Artists", "Languages"),
            postActionTermGroups(post).map(PostActionTermGroup::label),
        )
        assertEquals("Tag · najar", postActionTermLabel(tag))
        assertEquals("Artist · najar", postActionTermLabel(artist))
    }

    @Test
    fun `favorites and counts are limited to general tag terms`() {
        val portableTag = SearchTerm("portable")
        val sourceTag = SearchTerm("source tag", SearchFacet.TAG, "tag")
        val female = SearchTerm("female tag", SearchFacet.TAG, "female")
        val male = SearchTerm("male tag", SearchFacet.TAG, "male")
        val artist = SearchTerm("artist", SearchFacet.ARTIST, "artist")
        val post = samplePost(
            taxonomy = listOf(portableTag, sourceTag, female, male, artist).map(SearchTerm::toTaxonomyTerm),
        )

        assertEquals(listOf("portable", "source tag"), generalPostActionTags(post))
        assertTrue(portableTag.isGeneralPostTag())
        assertTrue(sourceTag.isGeneralPostTag())
        assertFalse(female.isGeneralPostTag())
        assertFalse(male.isGeneralPostTag())
        assertFalse(artist.isGeneralPostTag())
    }

    @Test
    fun `legacy tags become portable terms only when taxonomy is absent`() {
        val post = samplePost(
            canonicalTags = listOf("  legacy tag  ", "legacy tag"),
            taxonomy = emptyList(),
        )

        assertEquals(listOf(SearchTerm("legacy tag")), postActionTerms(post))
    }

    @Test
    fun `selection state changes only after acceptance and preserves exact term identity`() {
        val tag = SearchTerm("najar", SearchFacet.TAG, "tag")
        val artist = SearchTerm("najar", SearchFacet.ARTIST, "artist")
        val initial = mapOf(
            TagActionSelection.INCLUDE to setOf(tag),
            TagActionSelection.EXCLUDE to setOf(artist),
        )

        val rejected = initial.afterSelectionAttempt(
            target = TagActionSelection.INCLUDE,
            term = artist,
            accepted = false,
        )
        assertEquals(initial, rejected)

        val accepted = initial.afterSelectionAttempt(
            target = TagActionSelection.INCLUDE,
            term = artist,
            accepted = true,
        )
        assertEquals(setOf(tag, artist), accepted[TagActionSelection.INCLUDE])
        assertTrue(accepted[TagActionSelection.EXCLUDE].orEmpty().isEmpty())

        val removed = accepted.removeSelectedTerm(TagActionSelection.INCLUDE, artist)
        assertEquals(setOf(tag), removed[TagActionSelection.INCLUDE])
    }

    private fun samplePost(
        canonicalTags: List<String> = emptyList(),
        taxonomy: List<PostTaxonomyTerm>,
    ): Post {
        return Post(
            id = PostId(SourceKey.HITOMI, "1"),
            preview = ImageRef("https://example.com/preview.jpg", null, "image/jpeg"),
            full = ImageRef("https://example.com/full.jpg", null, "image/jpeg"),
            pageUrl = "https://example.com/post/1",
            width = 100,
            height = 100,
            canonicalTags = canonicalTags,
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            taxonomy = taxonomy,
        )
    }
}

private fun SearchTerm.toTaxonomyTerm(): PostTaxonomyTerm {
    return PostTaxonomyTerm(
        value = value,
        facet = facet,
        sourceNamespace = sourceNamespace,
    )
}
