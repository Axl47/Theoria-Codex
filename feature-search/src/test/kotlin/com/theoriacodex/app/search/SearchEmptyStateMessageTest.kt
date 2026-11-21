package com.theoriacodex.app.search

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEmptyStateMessageTest {
    private fun message(filters: SearchVisibilityFilters) = buildEmptySearchMessage(
        sourceResults = listOf(testPost(SourceKey.GELBOORU, "1")),
        visibilityFilters = filters,
        loadingMore = false,
        canLoadMore = false,
    )

    @Test
    fun `the message names every hidden kind of post`() {
        val expected = mapOf(
            SearchVisibilityFilters(hideLiked = true) to "No results remain after hiding liked posts.",
            SearchVisibilityFilters(hideSaved = true) to "No results remain after hiding saved posts.",
            SearchVisibilityFilters(hideWatched = true) to "No results remain after hiding watched posts.",
            SearchVisibilityFilters(hideLiked = true, hideSaved = true) to
                "No results remain after hiding liked and saved posts.",
            SearchVisibilityFilters(hideLiked = true, hideWatched = true) to
                "No results remain after hiding liked and watched posts.",
            SearchVisibilityFilters(hideSaved = true, hideWatched = true) to
                "No results remain after hiding saved and watched posts.",
            SearchVisibilityFilters(hideLiked = true, hideSaved = true, hideWatched = true) to
                "No results remain after hiding liked, saved, and watched posts.",
        )

        assertEquals(expected, expected.mapValues { (filters, _) -> message(filters) })
    }
}
