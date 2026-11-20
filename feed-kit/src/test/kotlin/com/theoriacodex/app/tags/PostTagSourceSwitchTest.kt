package com.theoriacodex.app.tags

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostTagSourceSwitchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a tag that would drop typed terms waits for an answer, and joining a group goes through the shared action`() {
        val added = mutableListOf<SearchTerm>()
        val alternatives = mutableListOf<Pair<Int, SearchTerm>>()
        val artist = SearchTerm("mika", SearchFacet.ARTIST)
        val general = SearchTerm("sea")
        val draft = emptySearchQuery(QueryMode.Source(SourceKey.PIXIV)).withIncludeTermsAsRequired(listOf(artist, general))
        val post = testPost().copy(
            id = PostId(SourceKey.GELBOORU, "1"),
            taxonomy = listOf(PostTaxonomyTerm("aoi", SearchFacet.ARTIST), PostTaxonomyTerm("snow")),
        )
        compose.setContent {
            MaterialTheme {
                PostTagContextProvider(
                    state = null,
                    searchActions = PostTagSearchActions(addAlternative = { _, index, term -> alternatives += index to term; true }),
                ) {
                    PostTagActionSection(
                        post, draftQuery = draft,
                        onAddIncludeTerm = { added += it; true }, onAddExcludeTerm = { true },
                        onRemoveIncludeTerm = {}, onRemoveExcludeTerm = {},
                    )
                }
            }
        }

        compose.tagChip("aoi").performClick()
        compose.onNodeWithText("Keep").performClick()
        compose.runOnIdle { assertEquals(emptyList<SearchTerm>(), added) }

        compose.tagChip("aoi").performClick()
        compose.onNodeWithText("Switch source").performClick()
        compose.runOnIdle { assertEquals(listOf(SearchTerm("aoi", SearchFacet.ARTIST)), added) }

        // The second group is "sea", which a general tag from any source may join.
        compose.onAllNodes(androidx.compose.ui.test.hasText("+ or"))[1].performClick()
        compose.tagChip("snow").performClick()
        compose.runOnIdle { assertEquals(listOf(1 to SearchTerm("snow")), alternatives) }
    }

    @Test fun `every path from one state to another asks before a switch would drop terms`() {
        val artist = SearchTerm("aoi", SearchFacet.ARTIST)
        val other = SearchTerm("rin", SearchFacet.CHARACTER)
        val post = testPost().copy(
            id = PostId(SourceKey.GELBOORU, "1"),
            taxonomy = listOf(PostTaxonomyTerm("aoi", SearchFacet.ARTIST)),
        )
        val base = emptySearchQuery(QueryMode.Source(SourceKey.PIXIV))
        val draft = mutableStateOf(base.withIncludeTermsAsRequired(listOf(artist, other)))
        val excluded = mutableListOf<SearchTerm>()
        val included = mutableListOf<SearchTerm>()
        compose.setContent {
            MaterialTheme {
                PostTagActionSection(
                    post, draftQuery = draft.value,
                    onAddIncludeTerm = { included += it; true }, onAddExcludeTerm = { excluded += it; true },
                    onRemoveIncludeTerm = {}, onRemoveExcludeTerm = {},
                )
            }
        }

        compose.tagChip("aoi").performClick()
        compose.runOnIdle { assertEquals(emptyList<SearchTerm>(), excluded) }
        compose.onNodeWithText("Switch source").performClick()
        compose.runOnIdle {
            assertEquals(listOf(artist), excluded)
            draft.value = base.withIncludeTermsAsRequired(listOf(other)).copy(excludeTerms = listOf(artist))
        }

        compose.tagChip("aoi").performCustomAction("Include Artist · aoi in Search")
        compose.runOnIdle { assertEquals(emptyList<SearchTerm>(), included) }
        compose.onNodeWithText("Switch source").performClick()
        compose.runOnIdle { assertEquals(listOf(artist), included) }
    }
}
