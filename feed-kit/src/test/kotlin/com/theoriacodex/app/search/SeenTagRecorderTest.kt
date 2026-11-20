package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeenTagRecorderTest {
    @Test
    fun `search results return before seen taxonomy is recorded with its namespaces`() = runTest {
        val post = Post(
            id = PostId(SourceKey.NHENTAI, "taxonomy"),
            preview = ImageRef("https://example.test/taxonomy.jpg", null, "image/jpeg"),
            full = null,
            pageUrl = null,
            width = 100,
            height = 100,
            canonicalTags = listOf("shared"),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            taxonomy = listOf(
                PostTaxonomyTerm("shared", SearchFacet.TAG, "female"),
                PostTaxonomyTerm("shared", SearchFacet.TAG, "male"),
            ),
        )
        val adapter = TestAdapter(SourceKey.NHENTAI).apply { fixedPosts = listOf(post) }
        val store = RecordingTagStore()
        val coordinator = testSearchCoordinator(TestRegistry(listOf(adapter)), tagSuggestionStore = store,
            computationDispatcher = StandardTestDispatcher(testScheduler), seenTagScope = backgroundScope)
        coordinator.initializeRoute()

        coordinator.executeInitial(query(SourceKey.NHENTAI, ""), SearchSourceScope.Single(SourceKey.NHENTAI))
        assertTrue(store.faceted.isEmpty())
        runCurrent()

        assertEquals(listOf("female", "male"), store.faceted.mapNotNull(FacetedTagSuggestion::sourceNamespace))
    }
}
