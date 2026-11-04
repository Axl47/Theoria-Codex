package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.tags.TagChipState
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.QueryMode
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

    private val blueHair = SavedTag(SourceKey.GELBOORU, "blue_hair")
    private fun gelbooruDraft(include: List<SearchTerm> = emptyList(), exclude: List<SearchTerm> = emptyList()) =
        emptySearchQuery(QueryMode.Source(SourceKey.GELBOORU))
            .withIncludeTermGroups(include.map(SearchTermGroup::single))
            .copy(excludeTerms = exclude)

    @Test fun `a saved tag cycles add, exclude, clear and reuses the draft's stored form`() {
        val stored = SearchTerm("Blue_Hair")
        assertEquals(listOf(SearchAction.AddIncludeTerm(blueHair.term)), blueHair.tapActions(gelbooruDraft(), -1))
        assertEquals(
            listOf(SearchAction.AddExcludeTerm(stored), SearchAction.RemoveIncludeTerm(stored)),
            blueHair.tapActions(gelbooruDraft(include = listOf(stored)), -1),
        )
        assertEquals(
            listOf(SearchAction.RemoveExcludeTerm(stored)),
            blueHair.tapActions(gelbooruDraft(exclude = listOf(stored)), -1),
        )
    }

    @Test fun `a saved tag does not match an artist of the same name`() {
        val draft = gelbooruDraft(include = listOf(SearchTerm("blue_hair", SearchFacet.ARTIST)))
        assertEquals(TagChipState.NONE, blueHair.state(draft))
    }

    @Test fun `while a group is the target a saved tag joins it only if the type fits`() {
        val artistDraft = gelbooruDraft(include = listOf(SearchTerm("mika", SearchFacet.ARTIST)))
        assertFalse(blueHair.canTap(artistDraft, 0))
        val tagDraft = gelbooruDraft(include = listOf(SearchTerm("sea")))
        assertTrue(blueHair.canTap(tagDraft, 0))
        assertEquals(
            listOf(SearchAction.AddSavedTagAlternative(SourceKey.GELBOORU, 0, blueHair.term)),
            blueHair.tapActions(tagDraft, 0),
        )
    }

    @Test fun `typed text puts matching saved tags first, merging a row the provider also returned`() {
        val other = SavedTag(SourceKey.GELBOORU, "dark_blue_sky")
        val provider = composerSuggestions(
            ComposerAddTarget.Required, emptyList(),
            listOf(TagSuggestion("blueberry", type = null, count = 5), TagSuggestion("blue_hair", type = null, count = 9)),
        )
        val rows = provider.withSavedTags(ComposerAddTarget.Required, "BLUE", listOf(other, blueHair), gelbooruDraft()).rows
        assertEquals(listOf("blue_hair", "dark_blue_sky", "blueberry"), rows.map { it.text })
        assertEquals(listOf(true, true, false), rows.map { it.saved })
        assertEquals(provider.rows[1].meta, rows[0].meta)
    }

    @Test fun `saved suggestions skip tags already in the search and honor the target`() {
        val none = ComposerSuggestions(emptyList(), 0)
        assertTrue(none.withSavedTags(ComposerAddTarget.Required, "blue", listOf(blueHair), gelbooruDraft(exclude = listOf(blueHair.term))).rows.isEmpty())
        assertTrue(none.withSavedTags(ComposerAddTarget.Required, " ", listOf(blueHair), gelbooruDraft()).rows.isEmpty())
        assertEquals(
            SearchAction.AddExcludeTerm(blueHair.term),
            none.withSavedTags(ComposerAddTarget.Excluded, "-blue", listOf(blueHair), gelbooruDraft()).rows.single().add,
        )
        assertTrue(none.withSavedTags(ComposerAddTarget.Alternative(0, artist), "blue", listOf(blueHair), gelbooruDraft(include = listOf(artist.terms.single()))).rows.isEmpty())
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
