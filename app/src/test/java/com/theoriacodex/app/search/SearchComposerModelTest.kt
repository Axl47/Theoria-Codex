package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchComposerModelTest {
    private val artist = SearchTermGroup.single(SearchTerm("mika", SearchFacet.ARTIST))
    private val pixiv = SearchSourceScope.Single(SourceKey.PIXIV)

    @Test fun `an alternative only offers suggestions matching the group type and counts the rest`() {
        val faceted = listOf(
            FacetedTagSuggestion("rin", SearchFacet.ARTIST, null, null),
            FacetedTagSuggestion("rin", SearchFacet.CHARACTER, null, null),
        )
        val suggestions = composerSuggestions(ComposerAddTarget.Alternative(3, artist), faceted, emptyList())
        assertEquals(
            SearchAction.AddIncludeAlternative(3, faceted.first().toSearchTerm()),
            suggestions.rows.single().add,
        )
        assertEquals(1, suggestions.hiddenCount)
    }

    @Test fun `plain suggestions cannot join a typed group and say so`() {
        val suggestions = composerSuggestions(
            ComposerAddTarget.Alternative(0, artist), emptyList(), listOf(TagSuggestion("rin", type = null, count = null)),
        )
        assertTrue(suggestions.rows.isEmpty())
        assertEquals("1 match hidden: this group only takes Artists.", composerHiddenSuggestionsNote(suggestions.hiddenCount, artist))
    }

    @Test fun `excluded target excludes typed and suggested tags`() {
        assertEquals(SearchAction.CommitTagInput("-sketch"), composerTypedAction(ComposerAddTarget.Excluded, " sketch "))
        assertEquals(SearchAction.CommitTagInput("-sketch"), composerTypedAction(ComposerAddTarget.Excluded, "-sketch"))
        assertEquals(
            SearchAction.AddExcludeTerm(SearchTerm("cat")),
            composerSuggestions(
                ComposerAddTarget.Excluded, emptyList(), listOf(TagSuggestion("cat", type = null, count = null)),
            ).rows.single().add,
        )
    }

    @Test fun `typed alternatives inherit the group type and blank input commits nothing`() {
        assertEquals(
            SearchAction.AddIncludeAlternative(2, SearchTerm("aoi", SearchFacet.ARTIST)),
            composerTypedAction(ComposerAddTarget.Alternative(2, artist), "aoi"),
        )
        assertEquals(null, composerTypedAction(ComposerAddTarget.Required, "  "))
    }

    @Test fun `removal undo stands only until another edit`() {
        val rin = SearchTerm("rin")
        val aoi = SearchTerm("aoi")
        val sketch = SearchTerm("sketch")
        val before = ComposerTerms(listOf(SearchTermGroup(listOf(rin, aoi)), artist), listOf(sketch), pixiv)

        val undo = composerRemovalUndo(before, listOf(rin), excluded = false)
        assertEquals("Removed rin", undo.message)
        assertEquals(ComposerTerms(listOf(SearchTermGroup.single(aoi), artist), listOf(sketch), pixiv), undo.after)
        assertTrue(undo.stillApplies(before))
        assertTrue(undo.stillApplies(undo.after))
        assertFalse(undo.stillApplies(undo.after.copy(excluded = emptyList())))
        assertFalse(undo.stillApplies(undo.after.copy(sourceScope = SearchSourceScope.GlobalUnified)))

        assertEquals(
            ComposerTerms(listOf(artist), listOf(sketch), pixiv),
            composerRemovalUndo(before, listOf(rin, aoi), excluded = false).after,
        )
        assertEquals(
            before.copy(excluded = emptyList()),
            composerRemovalUndo(before, listOf(sketch), excluded = true).after,
        )
    }
}
