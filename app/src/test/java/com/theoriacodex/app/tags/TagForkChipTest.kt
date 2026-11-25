package com.theoriacodex.app.tags

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagForkChipTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `where tags fork, a tap forks and holding offers the Search moves`() {
        val forked = mutableListOf<String>()
        val included = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                PostTagActionSection(
                    post = post(),
                    draftQuery = emptySearchQuery(),
                    onAddIncludeTerm = { term -> included += term.value; true },
                    onAddExcludeTerm = { true },
                    onRemoveIncludeTerm = {},
                    onRemoveExcludeTerm = {},
                    onFavoriteTagLongPress = null,
                    sheetExtras = PostTagSheetExtras(tagsInitiallyCollapsed = false, onFork = { term: SearchTerm -> forked += term.value }),
                )
            }
        }

        compose.onNodeWithText("cat").performClick()
        assertEquals(listOf("cat") to emptyList<String>(), forked to included)

        compose.onNodeWithText("cat").performTouchInput { longClick() }
        compose.onNodeWithText("Include cat in Search").performClick()
        assertEquals(listOf("cat") to listOf("cat"), forked to included)
    }

    @Test
    fun `holding a forking chip lists its trail moves first (ADR 0217)`() {
        val moved = mutableListOf<String>()
        val extras = PostTagSheetExtras(
            tagsInitiallyCollapsed = false,
            onFork = {},
            trailActions = { term -> listOf(TagChipAction("Add to trail") { moved += term.value }) },
        )
        compose.setContent {
            MaterialTheme {
                PostTagActionSection(post = post(), draftQuery = emptySearchQuery(), onFavoriteTagLongPress = null, sheetExtras = extras)
            }
        }

        compose.onNodeWithText("cat").performTouchInput { longClick() }
        compose.onNodeWithText("Add to trail").performClick()
        assertEquals(listOf("cat"), moved)
    }

    @Test
    fun `a forking chip for a tag the level searches shows as required, and the others as not in search`() {
        val level = Query(QueryMode.Unified, listOf("cat"), listOf("dog"), SortMode.NEWEST, null, null)
        compose.setContent {
            MaterialTheme {
                PostTagActionSection(
                    post = post(listOf("cat", "dog", "fox")),
                    draftQuery = emptySearchQuery(),
                    onFavoriteTagLongPress = null,
                    sheetExtras = PostTagSheetExtras(tagsInitiallyCollapsed = false, onFork = {}, levelQuery = level),
                )
            }
        }

        fun state(tag: String) = compose.onNodeWithText(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals(listOf("Required", "Excluded", "Not in search"), listOf(state("cat"), state("dog"), state("fox")))
    }

    private fun post(tags: List<String> = listOf("cat")) = Post(
        id = PostId(SourceKey.GELBOORU, "1"),
        preview = ImageRef("https://example.test/preview.jpg", null, "image/jpeg"),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = tags,
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )
}
