package com.theoriacodex.app.search.state

import com.theoriacodex.app.search.NhentaiLanguageFilter
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDraftReducerTest {
    private val available = SourceKey.entries.toSet()
    private val context = SearchDraftContext(available, emptyMap())
    private val artist = FacetedSearchScope(SearchFacet.ARTIST, "artist")
    private val character = FacetedSearchScope(SearchFacet.CHARACTER, "character")
    private val tag = FacetedSearchScope(SearchFacet.TAG, "tag")
    private val female = FacetedSearchScope(SearchFacet.TAG, "female")
    private val series = FacetedSearchScope(SearchFacet.SERIES, "parody")
    private val scopes = listOf(FacetedSearchScope.All, tag, female, artist, character, series)

    @Test
    fun `draft edits reset to applied state and clear keeps the current mode`() {
        val applied = query(QueryMode.Source(SourceKey.PIXIV), listOf(SearchTerm("applied")))
        val edited = state(applied).copy(
            query = state(applied).query.copy(draft = applied.copy(includeTerms = applied.includeTerms + SearchTerm("draft"))),
        )

        val reset = SearchDraftReducer.resetDraft(edited).state
        val cleared = SearchDraftReducer.clearDraft(reset).state

        assertEquals(applied, reset.query.draft)
        assertEquals(QueryMode.Source(SourceKey.PIXIV), cleared.query.draft.mode)
        assertTrue(cleared.query.draft.includeTerms.isEmpty())
    }

    @Test
    fun `mode selection restores per-mode query and rejects unavailable source`() {
        val restored = query(QueryMode.Source(SourceKey.PIXIV), listOf(SearchTerm("saved")))
        val withHistory = SearchDraftContext(setOf(SourceKey.PIXIV), mapOf(modeKey(restored.mode) to restored))

        val selected = SearchDraftReducer.selectMode(state(), restored.mode, withHistory, ::noScopes).state
        val rejected = SearchDraftReducer.selectMode(selected, QueryMode.Source(SourceKey.GELBOORU), withHistory, ::noScopes).state

        assertEquals(restored, selected.query.draft)
        assertEquals(QueryMode.Unified, rejected.query.draft.mode)
    }

    @Test
    fun `temporary source transitions preserve portable terms and drop source-owned terms`() {
        val source = query(
            QueryMode.Source(SourceKey.PIXIV),
            listOf(SearchTerm("portable"), SearchTerm("artist", SearchFacet.ARTIST, "artist")),
        )
        val initial = state(source).copy(
            query = state(source).query.copy(draftSourceScope = SearchSourceScope.Single(SourceKey.PIXIV)),
        )

        val temporary = SearchDraftReducer.toggleTemporarySource(
            initial,
            SourceKey.GELBOORU,
            context,
            ::noScopes,
        ).state
        val collapsed = SearchDraftReducer.toggleTemporarySource(
            temporary,
            SourceKey.GELBOORU,
            context,
            ::noScopes,
        ).state

        assertEquals(SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV)), temporary.query.draftSourceScope)
        assertEquals(QueryMode.Unified, temporary.query.draft.mode)
        assertEquals(listOf(SearchTerm("portable")), temporary.query.draft.includeTerms)
        assertEquals(SearchSourceScope.Single(SourceKey.PIXIV), collapsed.query.draftSourceScope)
        assertEquals(QueryMode.Source(SourceKey.PIXIV), collapsed.query.draft.mode)
    }

    @Test
    fun `prepare tag search normalizes duplicates clears content and rejects invalid requests`() {
        val dirty = state().copy(
            content = SearchContentUiState(results = listOf(post(SourceKey.PIXIV)), canLoadMore = true),
        )
        val prepared = SearchDraftReducer.prepareTagSearch(
            dirty,
            includeTags = listOf(" fresh ", "fresh"),
            excludeTags = listOf("blocked", "fresh"),
            mode = QueryMode.Source(SourceKey.PIXIV),
            availableSources = available,
            supportedScopes = ::noScopes,
        )

        assertTrue(prepared.accepted)
        assertEquals(listOf("fresh"), prepared.state.query.draft.includeTags)
        assertEquals(listOf("blocked"), prepared.state.query.draft.excludeTags)
        assertEquals(SortMode.NEWEST, prepared.state.query.draft.sort)
        assertTrue(prepared.state.content.results.isEmpty())
        assertFalse(SearchDraftReducer.prepareTagSearch(state(), emptyList(), emptyList(), QueryMode.Unified, available, ::noScopes).accepted)
        assertFalse(SearchDraftReducer.prepareTagSearch(state(), listOf("x"), emptyList(), QueryMode.Source(SourceKey.GELBOORU), setOf(SourceKey.PIXIV), ::noScopes).accepted)
    }

    @Test
    fun `gelbooru input requires an exact suggestion and canonicalizes spaces`() {
        val gelbooru = state(query(QueryMode.Source(SourceKey.GELBOORU))).copy(
            suggestions = SearchSuggestionsUiState(
                autocomplete = listOf(TagSuggestion("blue_hair", "tag", 10)),
            ),
        )

        val rejected = SearchDraftReducer.commitInput(gelbooru, "portrait")
        val accepted = SearchDraftReducer.commitInput(gelbooru, "blue hair")
        val excluded = SearchDraftReducer.commitInput(gelbooru, "-blue hair")

        assertFalse(rejected.accepted)
        assertTrue(rejected.state.query.validationMessage.orEmpty().contains("Gelbooru", true))
        assertEquals(listOf("blue_hair"), accepted.state.query.draft.includeTags)
        assertEquals(listOf("blue_hair"), excluded.state.query.draft.excludeTags)
    }

    @Test
    fun `pixiv and iwara input use suggested canonical tags while faceted sources retain typed scope`() {
        listOf(SourceKey.PIXIV, SourceKey.IWARA).forEach { source ->
            val input = state(query(QueryMode.Source(source))).copy(
                suggestions = SearchSuggestionsUiState(
                    autocomplete = listOf(TagSuggestion("blue_hair", "tag", 10)),
                ),
            )
            assertEquals(
                listOf("blue_hair"),
                SearchDraftReducer.commitInput(input, "blue hair").state.query.draft.includeTags,
            )
        }
        val faceted = state(query(QueryMode.Source(SourceKey.HITOMI))).copy(
            query = state(query(QueryMode.Source(SourceKey.HITOMI))).query.copy(supportedScopes = scopes),
            suggestions = SearchSuggestionsUiState(
                autocomplete = listOf(TagSuggestion("blue_hair", "tag", 10)),
            ),
        )
        assertEquals(
            listOf(SearchTerm("blue hair")),
            SearchDraftReducer.commitInput(faceted, "blue hair").state.query.draft.includeTerms,
        )
    }

    @Test
    fun `nhentai language and full color filters replace only typed filter terms`() {
        val artistChinese = SearchTerm("chinese", SearchFacet.ARTIST, "artist")
        val artistColor = SearchTerm("full color", SearchFacet.ARTIST, "artist")
        val draft = query(
            QueryMode.Source(SourceKey.NHENTAI),
            listOf(artistChinese, artistColor, SearchTerm("japanese", SearchFacet.LANGUAGE, "language")),
        )
        val language = SearchDraftReducer.setNhentaiLanguage(state(draft), NhentaiLanguageFilter.CHINESE).state
        val colored = SearchDraftReducer.setNhentaiFullColor(language, true).state
        val cleared = SearchDraftReducer.setNhentaiFullColor(colored, false).state

        assertTrue(artistChinese in language.query.draft.includeTerms)
        assertTrue(artistColor in language.query.draft.includeTerms)
        assertTrue(SearchTerm("chinese", SearchFacet.LANGUAGE, "language") in language.query.draft.includeTerms)
        assertEquals(NhentaiLanguageFilter.CHINESE, language.query.nhentaiLanguageFilter)
        assertTrue(colored.query.nhentaiFullColorFilter)
        assertFalse(cleared.query.nhentaiFullColorFilter)
        assertTrue(artistColor in cleared.query.draft.includeTerms)
    }

    @Test
    fun `direct nhentai id ignores language and full color filters but rejects exclusions`() {
        val base = query(
            QueryMode.Source(SourceKey.NHENTAI),
            listOf(
                SearchTerm("634609"),
                SearchTerm("english", SearchFacet.LANGUAGE, "language"),
                SearchTerm("full color", SearchFacet.TAG, "tag"),
            ),
        )

        assertEquals("634609", SearchDraftReducer.directNhentaiGalleryIdCandidate(base))
        assertEquals("634609", SearchDraftReducer.directNhentaiGalleryIdCandidate(base.copy(mode = QueryMode.Unified)))
        assertNull(SearchDraftReducer.directNhentaiGalleryIdCandidate(base.copy(excludeTerms = listOf(SearchTerm("x")))))
    }

    @Test
    fun `faceted scoped input preserves taxonomy polarity and rejects unsupported scope`() {
        var current = state(query(QueryMode.Source(SourceKey.NHENTAI))).copy(
            query = state(query(QueryMode.Source(SourceKey.NHENTAI))).query.copy(supportedScopes = scopes),
        )
        listOf("artist:najar", "series:the idolmaster", "female:x-ray", "-character:rin").forEach { input ->
            val reduction = SearchDraftReducer.commitInput(current, input)
            assertTrue(reduction.accepted)
            current = reduction.state
        }

        assertEquals(
            listOf(
                SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                SearchTerm("the idolmaster", SearchFacet.SERIES, "parody"),
                SearchTerm("x-ray", SearchFacet.TAG, "female"),
            ),
            current.query.draft.includeTerms,
        )
        assertEquals(listOf(SearchTerm("rin", SearchFacet.CHARACTER, "character")), current.query.draft.excludeTerms)
        assertFalse(SearchDraftReducer.commitInput(current, "group:test").accepted)
    }

    @Test
    fun `unified input blocks source taxonomy and historical restore removes it`() {
        val scoped = SearchDraftReducer.commitInput(state(), "artist:najar")
        val historical = query(
            QueryMode.Unified,
            listOf(SearchTerm("portable"), SearchTerm("najar", SearchFacet.ARTIST, "artist")),
        ).copy(excludeTerms = listOf(SearchTerm("series", SearchFacet.SERIES, "series")))
        val restored = SearchDraftReducer.restoreDraft(state(), historical, available, ::noScopes)

        assertFalse(scoped.accepted)
        assertTrue(scoped.state.query.validationMessage.orEmpty().contains("specific source"))
        assertEquals(listOf(SearchTerm("portable")), restored.state.query.draft.includeTerms)
        assertTrue(restored.state.query.draft.excludeTerms.isEmpty())
        assertTrue(restored.state.query.validationMessage.orEmpty().contains("removed"))
    }

    @Test
    fun `post taxonomy changes source without losing portable filters and removal is exact`() {
        val draft = query(QueryMode.Unified, listOf(SearchTerm("portable"), SearchTerm("same", SearchFacet.TAG, "tag")))
            .copy(excludeTerms = listOf(SearchTerm("blocked")), sort = SortMode.TOP)
        val added = SearchDraftReducer.addPostTerm(
            state(draft),
            post(SourceKey.HITOMI),
            SearchTerm("same", SearchFacet.ARTIST, "artist"),
            excluded = false,
            availableSources = available,
            supportedScopes = { scopes },
        )
        val removed = SearchDraftReducer.removeTerm(
            added.state,
            SearchTerm("same", SearchFacet.ARTIST, "artist"),
            excluded = false,
        ).state

        assertTrue(added.accepted)
        assertEquals(QueryMode.Source(SourceKey.HITOMI), added.state.query.draft.mode)
        assertEquals(SortMode.TOP, added.state.query.draft.sort)
        assertEquals(listOf(SearchTerm("portable"), SearchTerm("same", SearchFacet.ARTIST, "artist")), added.state.query.draft.includeTerms)
        assertEquals(listOf(SearchTerm("portable")), removed.query.draft.includeTerms)
    }

    @Test
    fun `unavailable source-owned post term leaves draft unchanged while portable post tag stays unified`() {
        val initial = state(query(QueryMode.Unified, listOf(SearchTerm("portable"))))
        val rejected = SearchDraftReducer.addPostTerm(
            initial,
            post(SourceKey.HITOMI),
            SearchTerm("najar", SearchFacet.ARTIST, "artist"),
            false,
            setOf(SourceKey.PIXIV),
            ::noScopes,
        )
        val portable = SearchDraftReducer.addPostTerm(
            state(),
            post(SourceKey.HITOMI),
            SearchTerm("tag"),
            false,
            available,
            ::noScopes,
        )

        assertFalse(rejected.accepted)
        assertEquals(initial, rejected.state)
        assertEquals(QueryMode.Unified, portable.state.query.draft.mode)
        assertEquals(listOf(SearchTerm("tag")), portable.state.query.draft.includeTerms)
    }

    private fun state(query: Query = query(QueryMode.Unified)): SearchUiState = SearchUiState(
        query = SearchQueryUiState(
            draft = query,
            applied = query,
            draftSourceScope = SearchSourceScope.fromQuery(query),
            appliedSourceScope = SearchSourceScope.fromQuery(query),
            availableSources = available.toList(),
            modeOptions = listOf(QueryMode.Unified) + available.map(QueryMode::Source),
        ),
    )

    private fun query(mode: QueryMode, terms: List<SearchTerm> = emptyList()): Query = Query(
        mode = mode,
        includeTerms = terms,
        excludeTerms = emptyList(),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )

    private fun post(source: SourceKey): Post = Post(
        id = PostId(source, "post"),
        preview = ImageRef("https://example.test/post.jpg", null, "image/jpeg"),
        full = null,
        pageUrl = null,
        width = 100,
        height = 100,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )

    private fun noScopes(@Suppress("UNUSED_PARAMETER") mode: QueryMode): List<FacetedSearchScope> = emptyList()
}
