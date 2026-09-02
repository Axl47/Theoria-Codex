package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionCoordinatorTest {

    @Test
    fun `cached autocomplete is immediate and fresh exact prefixes avoid duplicate provider calls`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV).apply {
            autocomplete = listOf(TagSuggestion("初音ミク", "tag", null, "Hatsune Miku"))
        }
        val store = RecordingTagStore().apply {
            put(
                SourceKey.PIXIV,
                listOf(TagSuggestion("初音ミク", "tag", null, "Hatsune Miku")),
            )
        }
        val coordinator = testSearchCoordinator(
            TestRegistry(listOf(pixiv)),
            tagSuggestionStore = store,
        )
        coordinator.initializeRoute()
        val query = query(SourceKey.PIXIV, "")

        val cached = coordinator.cachedAutocomplete(
            query,
            SearchSourceScope.Single(SourceKey.PIXIV),
            FacetedSearchScope.All,
            "hatsune",
            emptyList(),
        )
        coordinator.fetchAutocomplete(
            query,
            SearchSourceScope.Single(SourceKey.PIXIV),
            FacetedSearchScope.All,
            "hatsune",
            emptyList(),
        )
        coordinator.fetchAutocomplete(
            query,
            SearchSourceScope.Single(SourceKey.PIXIV),
            FacetedSearchScope.All,
            "hatsune",
            emptyList(),
        )

        assertEquals(listOf("初音ミク"), cached.autocomplete.map(TagSuggestion::text))
        assertEquals(listOf("hatsune"), pixiv.autocompletePrefixes)
    }

    @Test
    fun `unified autocomplete waits for the slowest provider instead of their sum`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV).apply {
            autocompleteResponseDelayMs = 1_000L
            autocomplete = listOf(TagSuggestion("blue pixiv", "tag", null))
        }
        val gelbooru = TestAdapter(SourceKey.GELBOORU).apply {
            autocompleteResponseDelayMs = 1_000L
            autocomplete = listOf(TagSuggestion("blue_gelbooru", "tag", 10))
        }
        val coordinator = testSearchCoordinator(TestRegistry(listOf(pixiv, gelbooru)))
        coordinator.initializeRoute()
        val startedAt = testScheduler.currentTime

        val result = coordinator.fetchAutocomplete(
            unifiedQuery(""),
            SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV)),
            FacetedSearchScope.All,
            "blue",
            emptyList(),
        )

        assertEquals(1_000L, testScheduler.currentTime - startedAt)
        assertEquals(setOf("blue pixiv", "blue_gelbooru"), result.autocomplete.mapTo(mutableSetOf(), TagSuggestion::text))
    }

    @Test
    fun `autocomplete provider work is time bounded and falls back locally`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV).apply {
            autocompleteResponseDelayMs = AUTOCOMPLETE_REQUEST_TIMEOUT_MS + 1_000L
        }
        val store = RecordingTagStore().apply {
            put(SourceKey.PIXIV, listOf(TagSuggestion("blue local", "seed", null)))
        }
        val coordinator = testSearchCoordinator(
            TestRegistry(listOf(pixiv)),
            tagSuggestionStore = store,
        )
        coordinator.initializeRoute()
        val startedAt = testScheduler.currentTime

        val result = coordinator.fetchAutocomplete(
            query(SourceKey.PIXIV, ""),
            SearchSourceScope.Single(SourceKey.PIXIV),
            FacetedSearchScope.All,
            "blue",
            emptyList(),
        )

        assertEquals(AUTOCOMPLETE_REQUEST_TIMEOUT_MS, testScheduler.currentTime - startedAt)
        assertEquals(listOf("blue local"), result.autocomplete.map(TagSuggestion::text))
    }
}
