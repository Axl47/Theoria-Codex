package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.domain.adapter.FacetedSearchScope
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

class SearchScopedInputPolicyTest {
    @Test
    fun `all scoped prefixes preserve facet namespace aliases and polarity`() {
        val expected = mapOf(
            "tag" to SearchScopePrefix(SearchFacet.TAG, "tag"),
            "female" to SearchScopePrefix(SearchFacet.TAG, "female", true),
            "male" to SearchScopePrefix(SearchFacet.TAG, "male", true),
            "artist" to SearchScopePrefix(SearchFacet.ARTIST),
            "character" to SearchScopePrefix(SearchFacet.CHARACTER),
            "series" to SearchScopePrefix(SearchFacet.SERIES),
            "parody" to SearchScopePrefix(SearchFacet.SERIES, "parody", true),
            "group" to SearchScopePrefix(SearchFacet.GROUP),
            "type" to SearchScopePrefix(SearchFacet.TYPE),
            "category" to SearchScopePrefix(SearchFacet.TYPE, "category", true),
            "language" to SearchScopePrefix(SearchFacet.LANGUAGE),
        )

        expected.forEach { (alias, prefix) ->
            val included = parseScopedInput(" $alias : value ")
            val excluded = parseScopedInput(" -${alias.uppercase()}: value ")

            assertEquals(prefix, included.explicitScope)
            assertEquals("value", included.value)
            assertFalse(included.isExclude)
            assertEquals(prefix, excluded.explicitScope)
            assertEquals("value", excluded.value)
            assertTrue(excluded.isExclude)
        }

        val unknown = parseScopedInput("-unknown:value")
        assertNull(unknown.explicitScope)
        assertEquals("unknown:value", unknown.value)
        assertTrue(unknown.isExclude)
    }

    @Test
    fun `namespace aliases resolve exactly while general aliases may fall back by facet`() {
        val genericTag = FacetedSearchScope(SearchFacet.TAG, null)
        val female = FacetedSearchScope(SearchFacet.TAG, "female")
        val artist = FacetedSearchScope(SearchFacet.ARTIST, "artist")
        val scopes = listOf(FacetedSearchScope.All, genericTag, female, artist)

        assertEquals(female, resolveSupportedScope(parseScopedInput("female:x").explicitScope!!, scopes))
        assertNull(resolveSupportedScope(parseScopedInput("male:x").explicitScope!!, scopes))
        assertEquals(artist, resolveSupportedScope(parseScopedInput("artist:x").explicitScope!!, scopes))
    }

    @Test
    fun `draft commit uses canonical unified unsupported and removal messages`() {
        val unified = state(QueryMode.Unified)
        val unifiedRejected = SearchDraftReducer.commitInput(unified, "artist:najar")
        assertFalse(unifiedRejected.accepted)
        assertEquals(UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE, unifiedRejected.state.query.validationMessage)

        val hitomi = state(QueryMode.Source(SourceKey.HITOMI), listOf(FacetedSearchScope.All))
        val unsupported = SearchDraftReducer.commitInput(hitomi, "artist:najar")
        assertFalse(unsupported.accepted)
        assertEquals(UNSUPPORTED_SEARCH_SCOPE_MESSAGE, unsupported.state.query.validationMessage)

        val sourceOwned = state(QueryMode.Source(SourceKey.HITOMI)).let { current ->
            current.copy(
                query = current.query.copy(
                    draft = current.query.draft.copy(
                        includeTerms = listOf(SearchTerm("najar", SearchFacet.ARTIST, "artist")),
                    ),
                ),
            )
        }
        val switched = SearchDraftReducer.selectMode(
            sourceOwned,
            QueryMode.Unified,
            com.theoriacodex.app.search.state.SearchDraftContext(SourceKey.entries.toSet(), emptyMap()),
        ) { emptyList() }
        assertEquals(UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE, switched.state.query.validationMessage)
    }

    private fun state(
        mode: QueryMode,
        scopes: List<FacetedSearchScope> = emptyList(),
    ): SearchUiState {
        val query = Query(
            mode = mode,
            includeTerms = emptyList(),
            excludeTerms = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        return SearchUiState(
            query = SearchQueryUiState(
                draft = query,
                applied = query,
                draftSourceScope = SearchSourceScope.fromQuery(query),
                appliedSourceScope = SearchSourceScope.fromQuery(query),
                availableSources = SourceKey.entries,
                modeOptions = listOf(QueryMode.Unified) + SourceKey.entries.map(QueryMode::Source),
                supportedScopes = scopes,
            ),
        )
    }
}
