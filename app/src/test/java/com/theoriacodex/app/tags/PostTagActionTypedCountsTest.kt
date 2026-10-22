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
@Config(sdk = [35], application = Application::class)
class PostTagActionTypedCountsTest {
    @get:Rule val compose = createComposeRule()

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
