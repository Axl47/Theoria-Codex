package com.theoriacodex.app.tags

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostTagActionTypedCountsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `supplied counts display immediately by type and only missing terms are fetched`() {
        val artist = SearchTerm("shared", SearchFacet.ARTIST, "artist")
        val character = SearchTerm("shared", SearchFacet.CHARACTER, "character")
        val post = testPost().copy(taxonomy = listOf(
            PostTaxonomyTerm("shared", sourceNamespace = "tag", count = 0),
            PostTaxonomyTerm("shared", SearchFacet.ARTIST, "artist", count = 12),
            PostTaxonomyTerm("shared", SearchFacet.CHARACTER, "character"),
        ))
        compose.setContent {
            MaterialTheme {
                PostTagContextProvider(null, fetchSearchTermCounts = { _, requested ->
                    assertEquals(listOf(character), requested)
                    mapOf(character to 34)
                }) {
                    PostTagActionSection(
                        post = post,
                        draftQuery = emptySearchQuery(),
                        tagVideoCountProvider = { _, _ -> 999 },
                        fetchTagVideoCounts = { _, _ -> error("Supplied general count must avoid lookup") },
                        onAddIncludeTerm = { true },
                        onAddExcludeTerm = { true },
                        onRemoveIncludeTerm = {},
                        onRemoveExcludeTerm = {},
                    )
                }
            }
        }
        listOf("0", "12", "34").forEach { count ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(count))
            compose.onNodeWithText(count).assertIsDisplayed()
        }
        assertEquals(mapOf(SearchTerm("shared", sourceNamespace = "tag") to 0, artist to 12), postActionSuppliedCounts(post))
    }

    @Test fun `hold menu displays distinct general artist and character counts for the same name`() {
        val artist = SearchTerm("shared", SearchFacet.ARTIST, "artist")
        val character = SearchTerm("shared", SearchFacet.CHARACTER, "character")
        val terms = listOf(SearchTerm("shared"), artist, character)
        val post = testPost().copy(taxonomy = terms.map { PostTaxonomyTerm(it.value, it.facet, it.sourceNamespace) })
        compose.setContent {
            MaterialTheme {
                PostTagContextProvider(null, fetchSearchTermCounts = { _, requested ->
                    assertEquals(listOf(artist, character), requested)
                    mapOf(artist to 12, character to 34)
                }) {
                    PostTagActionSection(
                        post = post,
                        draftQuery = emptySearchQuery(),
                        tagVideoCountProvider = { _, _ -> 7 },
                        onAddIncludeTerm = { true },
                        onAddExcludeTerm = { true },
                        onRemoveIncludeTerm = {},
                        onRemoveExcludeTerm = {},
                    )
                }
            }
        }
        listOf("7", "12", "34").forEach { count ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(count))
            compose.onNodeWithText(count).assertIsDisplayed()
        }
    }
}
