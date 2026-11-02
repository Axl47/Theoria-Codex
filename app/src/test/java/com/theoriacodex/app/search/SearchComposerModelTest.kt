package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchComposerModelTest {
    private val artist = SearchTermGroup.single(SearchTerm("mika", SearchFacet.ARTIST))
    private val female = SearchTermGroup.single(SearchTerm("glasses", SearchFacet.TAG, "female"))
    private val male = SearchTermGroup.single(SearchTerm("beard", SearchFacet.TAG, "male"))

    @Test fun `typed sources get ordered sections while keeping draft indexes`() {
        val sections = composerSections(listOf(male, female, artist), typedSource = true)
        assertEquals(listOf("Artists", "Female tags", "Male tags"), sections.map { it.title })
        assertEquals(2, sections.first().groups.single().index)
    }

    @Test fun `untyped sources get one flat section in draft order`() {
        val section = composerSections(listOf(male, artist), typedSource = false).single()
        assertEquals("Required", section.title)
        assertEquals(listOf(male, artist), section.groups.map { it.group })
    }

    @Test fun `any of only offers suggestions matching the group type`() {
        val faceted = listOf(
            FacetedTagSuggestion("rin", SearchFacet.ARTIST, null, null),
            FacetedTagSuggestion("rin", SearchFacet.CHARACTER, null, null),
        )
        val suggestion = composerSuggestions(
            SearchAddMode.ANY_OF, IndexedTermGroup(3, artist), faceted, emptyList(),
        ).single()
        assertEquals(SearchAction.AddIncludeAlternative(3, faceted.first().toSearchTerm()), suggestion.add)
    }

    @Test fun `plain suggestions cannot join a typed group`() {
        assertTrue(
            composerSuggestions(
                SearchAddMode.ANY_OF, IndexedTermGroup(0, artist), emptyList(), listOf(TagSuggestion("rin", type = null, count = null)),
            ).isEmpty(),
        )
    }

    @Test fun `without mode excludes typed and suggested tags`() {
        assertEquals(SearchAction.CommitTagInput("-sketch"), composerTypedAction(SearchAddMode.EXCLUDED, null, " sketch "))
        assertEquals(SearchAction.CommitTagInput("-sketch"), composerTypedAction(SearchAddMode.EXCLUDED, null, "-sketch"))
        assertEquals(
            SearchAction.AddExcludeTerm(SearchTerm("cat")),
            composerSuggestions(SearchAddMode.EXCLUDED, null, emptyList(), listOf(TagSuggestion("cat", type = null, count = null))).single().add,
        )
    }

    @Test fun `any of without a group starts a new one and blank input commits nothing`() {
        assertEquals(SearchAction.CommitTagInput("cat"), composerTypedAction(SearchAddMode.ANY_OF, null, "cat"))
        assertEquals(null, composerTypedAction(SearchAddMode.REQUIRED, null, "  "))
    }
}
