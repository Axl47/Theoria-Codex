package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.theoriacodex.app.tags.tagChip
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h915dp")
class CodexTagSearchSheetTest {
    @get:Rule val compose = createComposeRule()
    private val artist = SearchTerm("mika", SearchFacet.ARTIST)
    private val sea = SearchTerm("sea")
    private val snow = SearchTerm("snow")

    private fun open(onSearch: (SourceKey, Query) -> Unit) {
        val pixiv = CodexSearchTerms(
            listOf(CodexSearchTermOption(sea, 12_500), CodexSearchTermOption(artist, 3), CodexSearchTermOption(snow, 2)),
            listOf(CodexDrawPost("48213", listOf(artist, sea, snow))),
        )
        val gelbooru = CodexSearchTerms(listOf(CodexSearchTermOption(SearchTerm("rain"), 1)), emptyList())
        compose.setContent {
            MaterialTheme {
                CodexTagSearchSheet(
                    codexName = "Prints",
                    sources = listOf(CodexSearchSourceOption(SourceKey.PIXIV, 4), CodexSearchSourceOption(SourceKey.GELBOORU, 1)),
                    terms = mapOf(SourceKey.PIXIV to pixiv, SourceKey.GELBOORU to gelbooru),
                    onDismiss = {},
                    onSearch = onSearch,
                )
            }
        }
    }

    @Test fun `tags are typed, counted, and build an or query that runs as typed`() {
        var searched: Query? = null
        open { _, query -> searched = query }
        compose.onNodeWithText("Artists").assertExists()
        compose.onNodeWithText("12k").assertExists()
        compose.tagChip("mika").performClick()
        compose.tagChip("sea").performClick()
        compose.onAllNodes(androidx.compose.ui.test.hasText("+ or"))[1].performClick()
        compose.tagChip("snow").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(SearchTermGroup.single(artist), SearchTermGroup(listOf(sea, snow))),
                searched?.effectiveIncludeTermGroups,
            )
        }
    }

    @Test fun `draw fills the query from one saved post and changing source starts over`() {
        var searched: Query? = null
        open { _, query -> searched = query }
        compose.onNodeWithContentDescription("Draw fewer tags").performClick()
        compose.onNodeWithText("Draw").performClick()
        compose.onNodeWithText("Drawn from saved post 48213").assertExists()
        compose.onNodeWithText("Search").performClick()
        compose.runOnIdle {
            val drawn = requireNotNull(searched).includeTerms
            assertEquals(2, drawn.size)
            assertTrue(listOf(artist, sea, snow).containsAll(drawn))
        }

        compose.onNodeWithContentDescription("Gelbooru, 1 post").performClick()
        compose.onNodeWithText("Tap tags, or draw them from a saved post.").assertExists()
        compose.tagChip("rain").performClick()
    }

    @Test fun `the filter narrows tags and says when none match`() {
        open { _, _ -> }
        compose.onNodeWithText("Filter tags").performTextInput("sn")
        compose.tagChip("snow").assertExists()
        compose.onNodeWithText("sea").assertDoesNotExist()
        compose.onNodeWithText("Filter tags").performTextInput("zz")
        compose.onNodeWithText("No tags match \"snzz\"").assertExists()
    }
}
