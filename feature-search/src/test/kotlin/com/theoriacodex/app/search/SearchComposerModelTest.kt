package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchSuggestionsUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.tags.TagChipState
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.adapter.FacetedSearchScope
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

    @Test fun `separators around typed text never reach the committed tag`() {
        assertEquals(SearchAction.CommitTagInput("scenery"), composerTypedAction(ComposerAddTarget.Required, ",scenery, "))
        assertEquals(SearchAction.CommitTagInput("-scenery"), composerTypedAction(ComposerAddTarget.Excluded, "-,scenery;"))
        assertEquals(SearchAction.CommitTagInput("hello,_world"), composerTypedAction(ComposerAddTarget.Required, "hello,_world"))
        assertEquals(null, composerTypedAction(ComposerAddTarget.Required, ","))
    }

    private val blueHair = FavoriteTagEntry(SourceKey.GELBOORU, "blue_hair")
    private fun gelbooruDraft(include: List<SearchTerm> = emptyList(), exclude: List<SearchTerm> = emptyList()) =
        emptySearchQuery(QueryMode.Source(SourceKey.GELBOORU))
            .withIncludeTermGroups(include.map(SearchTermGroup::single))
            .copy(excludeTerms = exclude)

    @Test fun `a saved tag cycles add, exclude, clear and reuses the draft's stored form`() {
        val stored = SearchTerm("Blue_Hair")
        assertEquals(listOf(SearchAction.AddIncludeTerm(blueHair.terms.single())), blueHair.tapActions(gelbooruDraft(), -1))
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
            listOf(SearchAction.AddSavedTagAlternative(SourceKey.GELBOORU, 0, blueHair.terms.single())),
            blueHair.tapActions(tagDraft, 0),
        )
    }

    private val hairColors = FavoriteTagEntry(SourceKey.GELBOORU, listOf("blue_hair", "red_hair"))

    @Test fun `a saved group is added as one group and removed by the next tap, whatever its order`() {
        assertEquals(listOf(SearchAction.AddSavedTagGroup(hairColors.terms)), hairColors.tapActions(gelbooruDraft(), -1))
        val draft = gelbooruDraft(include = listOf(SearchTerm("sea")))
            .let { it.withIncludeTermGroups(it.effectiveIncludeTermGroups + SearchTermGroup(listOf(SearchTerm("Red_Hair"), SearchTerm("blue_hair")))) }
        assertEquals(TagChipState.REQUIRED, hairColors.state(draft))
        assertEquals(listOf(SearchAction.RemoveIncludeGroup(1)), hairColors.tapActions(draft, -1))
        assertFalse(hairColors.canTap(gelbooruDraft(include = listOf(SearchTerm("sea"))), 0))
    }

    @Test fun `tag menus offer to save general tags and find what is already saved`() {
        val saved = ComposerSavedTags(listOf(blueHair, hairColors), SourceKey.GELBOORU)
        assertEquals(hairColors, saved.entryFor(listOf(SearchTerm("red_hair"), SearchTerm("blue_hair"))))
        assertEquals(blueHair, saved.entryFor(listOf(SearchTerm("blue_hair", sourceNamespace = "tag"))))
        assertEquals(null, saved.entryFor(listOf(SearchTerm("blue_hair", SearchFacet.ARTIST))))
        assertTrue(saved.canSave(listOf(SearchTerm("sea"), SearchTerm("sky"))))
        assertFalse(saved.canSave(listOf(SearchTerm("mika", SearchFacet.ARTIST))))
    }

    @Test fun `a named group shows its name, an unnamed one its alternatives`() {
        assertEquals("blue_hair or red_hair", hairColors.label)
        assertEquals("Hair", hairColors.copy(name = "Hair").label)
    }

    @Test fun `saved groups stay out of suggestions`() {
        val rows = ComposerSuggestions(emptyList(), 0)
            .withSavedTags(ComposerAddTarget.Required, "hair", listOf(hairColors, blueHair), gelbooruDraft()).rows
        assertEquals(listOf("blue_hair"), rows.map { it.text })
    }

    @Test fun `typed text puts matching saved tags first, merging a row the provider also returned`() {
        val other = FavoriteTagEntry(SourceKey.GELBOORU, "dark_blue_sky")
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
        assertTrue(none.withSavedTags(ComposerAddTarget.Required, "blue", listOf(blueHair), gelbooruDraft(exclude = listOf(blueHair.terms.single()))).rows.isEmpty())
        assertTrue(none.withSavedTags(ComposerAddTarget.Required, " ", listOf(blueHair), gelbooruDraft()).rows.isEmpty())
        assertEquals(
            SearchAction.AddExcludeTerm(blueHair.terms.single()),
            none.withSavedTags(ComposerAddTarget.Excluded, "-blue", listOf(blueHair), gelbooruDraft()).rows.single().add,
        )
        assertTrue(none.withSavedTags(ComposerAddTarget.Alternative(0, artist), "blue", listOf(blueHair), gelbooruDraft(include = listOf(artist.terms.single()))).rows.isEmpty())
    }

    @Test fun `saved suggestions ignore an exclude sign and a tag scope prefix`() {
        val none = ComposerSuggestions(emptyList(), 0)
        for (input in listOf("tag:blu", " tag: blu", "-tag:blu", "- blu")) {
            assertEquals(input, listOf("blue_hair"), none.withSavedTags(ComposerAddTarget.Required, input, listOf(blueHair), gelbooruDraft()).rows.map { it.text })
        }
    }

    @Test fun `searched tags lead in their given order, merging provider rows and skipping draft tags`() {
        val provider = composerSuggestions(
            ComposerAddTarget.Required, emptyList(),
            listOf(TagSuggestion("blueberry", type = null, count = 5), TagSuggestion("blue_hair", type = null, count = 9)),
        )
        val searched = listOf(SearchTerm("sky"), SearchTerm("Blue_Hair"), SearchTerm("blue_eyes"))
        val draft = gelbooruDraft(include = listOf(SearchTerm("SKY")))

        val rows = provider.withSearchedTags(ComposerAddTarget.Required, searched, draft).rows
        assertEquals(listOf("blue_hair", "blue_eyes", "blueberry"), rows.map { it.text })
        assertEquals(listOf(true, true, false), rows.map { it.searched })
        assertEquals(provider.rows[1].meta, rows[0].meta)
        assertEquals(SearchAction.AddIncludeTerm(SearchTerm("blue_eyes")), rows[1].add)
        assertTrue(provider.withSearchedTags(ComposerAddTarget.Alternative(0, artist), searched, draft).rows.none { it.searched })
    }

    private val nhentaiArtist = SearchTerm("shindol", SearchFacet.ARTIST, "artist")
    private val hitomiFemale = SearchTerm("sole female", SearchFacet.TAG, "female")
    private val nhentaiScopes = listOf(
        FacetedSearchScope(SearchFacet.TAG, "tag"),
        FacetedSearchScope(SearchFacet.ARTIST, "artist"),
        FacetedSearchScope(SearchFacet.GROUP, "group"),
    )

    private fun historyRows(
        input: String,
        searched: List<SearchTerm>,
        scope: FacetedSearchScope = FacetedSearchScope.All,
        supported: List<FacetedSearchScope> = nhentaiScopes,
        saved: List<FavoriteTagEntry> = emptyList(),
        target: ComposerAddTarget = ComposerAddTarget.Required,
    ) = composerSuggestions(
        target,
        SearchUiState(
            query = SearchQueryUiState(
                draft = emptySearchQuery(QueryMode.Source(SourceKey.NHENTAI)),
                selectedScope = scope,
                scopesBySource = mapOf(SourceKey.NHENTAI to supported),
            ),
            suggestions = SearchSuggestionsUiState(input = input, searchedTags = searched),
        ),
        saved,
    ).rows

    @Test fun `searched terms show only in a scope of their type, picked or typed`() {
        val searched = listOf(nhentaiArtist, SearchTerm("shibari"), hitomiFemale)
        fun texts(input: String, scope: FacetedSearchScope = FacetedSearchScope.All) =
            historyRows(input, searched, scope).map { it.text }

        assertEquals(listOf("shindol"), texts("sh", FacetedSearchScope(SearchFacet.ARTIST, "artist")))
        assertEquals(listOf("shindol"), texts("artist:sh"))
        assertEquals(listOf("shindol"), texts("artist:sh", FacetedSearchScope(SearchFacet.TAG, "tag")))
        assertEquals(listOf("shibari"), texts("sh", FacetedSearchScope(SearchFacet.TAG, "tag")))
        assertEquals(listOf("shibari"), texts("tag:sh", FacetedSearchScope(SearchFacet.ARTIST, "artist")))
        assertEquals(emptyList<String>(), texts("sh", FacetedSearchScope(SearchFacet.GROUP, "group")))
        // nhentai has no female tags, so the prefix finds nothing to suggest.
        assertEquals(emptyList<String>(), texts("female:so"))
        assertEquals(listOf("shindol", "shibari", "sole female"), texts("s"))
    }

    @Test fun `a searched typed term shows its type and is added with its facet and namespace`() {
        val row = historyRows("shin", listOf(nhentaiArtist)).single()
        assertEquals("Artist", row.meta)
        assertTrue(row.searched)
        assertEquals(SearchAction.AddIncludeTerm(nhentaiArtist), row.add)
        assertEquals(
            SearchAction.AddExcludeTerm(nhentaiArtist),
            historyRows("-shin", listOf(nhentaiArtist), target = ComposerAddTarget.Excluded).single().add,
        )
        assertEquals("", historyRows("shib", listOf(SearchTerm("shibari"))).single().meta)
    }

    @Test fun `an alternative group takes searched terms of its own type only`() {
        val artists = SearchTermGroup.single(SearchTerm("kisaragi", SearchFacet.ARTIST, "artist"))
        val tags = SearchTermGroup.single(SearchTerm("glasses", SearchFacet.TAG, "tag"))
        val searched = listOf(nhentaiArtist, SearchTerm("shibari"))

        assertEquals(
            listOf(SearchAction.AddIncludeAlternative(2, nhentaiArtist)),
            historyRows("sh", searched, target = ComposerAddTarget.Alternative(2, artists)).map { it.add },
        )
        // A plain general tag joins a `tag:` group in the group's spelling.
        assertEquals(
            listOf(SearchAction.AddIncludeAlternative(1, SearchTerm("shibari", SearchFacet.TAG, "tag"))),
            historyRows("sh", searched, target = ComposerAddTarget.Alternative(1, tags)).map { it.add },
        )
    }

    @Test fun `a typed draft term hides only its own type from history`() {
        val provider = ComposerSuggestions(emptyList(), 0)
        val draft = emptySearchQuery(QueryMode.Source(SourceKey.NHENTAI)).withIncludeTermsAsRequired(listOf(nhentaiArtist))
        val rows = provider.withSearchedTags(ComposerAddTarget.Required, listOf(nhentaiArtist, SearchTerm("shindol")), draft).rows
        assertEquals(listOf(SearchTerm("shindol")), rows.map { it.term })
    }

    @Test fun `general tag scopes show saved tags, typed scopes do not`() {
        val saved = listOf(FavoriteTagEntry(SourceKey.NHENTAI, "sky_blue"))
        fun texts(input: String, scope: FacetedSearchScope = FacetedSearchScope.All) =
            historyRows(input, emptyList(), scope, saved = saved).map { it.text }

        assertEquals(listOf("sky_blue"), texts("sk"))
        assertEquals(listOf("sky_blue"), texts("sk", FacetedSearchScope(SearchFacet.TAG, "tag")))
        assertEquals(listOf("sky_blue"), texts("tag:sk", FacetedSearchScope(SearchFacet.ARTIST, "artist")))
        assertEquals(emptyList<String>(), texts("sk", FacetedSearchScope(SearchFacet.ARTIST, "artist")))
        assertEquals(emptyList<String>(), texts("artist:sk"))
    }

    @Test fun `tag prefix means general tags even where the mode cannot search scopes`() {
        fun rows(input: String) = historyRows(
            input, listOf(SearchTerm("sky")), supported = emptyList(),
            saved = listOf(FavoriteTagEntry(SourceKey.NHENTAI, "sky_blue")),
        ).map { it.text }

        assertEquals(listOf("sky_blue", "sky"), rows("tag:sk"))
        assertEquals(emptyList<String>(), rows("artist:sk"))
        assertEquals(emptyList<String>(), rows("female:sk"))
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
