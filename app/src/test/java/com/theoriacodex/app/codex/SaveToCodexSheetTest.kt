package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Codex
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SaveToCodexSheetTest {
    @get:Rule val compose = createComposeRule()

    @Config(qualifiers = "w411dp-h1000dp")
    @Test fun `tag relevance is default and toggle restores normal order while search stays active`() {
        compose.setContent {
            MaterialTheme {
                SaveToCodexSheet(
                    profiles = listOf(RecommendationProfile("main", "Main")), initialProfileId = "main",
                    codicesByProfile = mapOf("main" to listOf(
                        Codex("a", "Art first", 0), Codex("b", "Art relevant", 0), Codex("c", "Art tied", 0),
                    )),
                    codexItemCounts = emptyMap(), codexCoverCandidates = emptyMap(),
                    relevanceScores = mapOf("b" to 5L, "c" to 5L),
                    onCreateCodex = { _, _ -> }, onSelectCodex = {}, onDismiss = {},
                )
            }
        }
        fun assertBefore(first: String, second: String) {
            val firstY = compose.onNodeWithText(first).fetchSemanticsNode().boundsInRoot.top
            val secondY = compose.onNodeWithText(second).fetchSemanticsNode().boundsInRoot.top
            org.junit.Assert.assertTrue("$first should precede $second", firstY < secondY)
        }
        assertBefore("Art relevant", "Art tied")
        assertBefore("Art tied", "Art first")
        compose.onNodeWithText("Search Codices").performTextReplacement("Art")
        compose.onNodeWithContentDescription("Sort: tag relevance. Switch to normal order").performClick()
        assertBefore("Art first", "Art relevant")
        compose.onNodeWithContentDescription("Sort: normal order. Switch to tag relevance").performClick()
        assertBefore("Art relevant", "Art first")
    }

    @Test fun `Saved drills down to Likes and preserves excluded targets across profiles`() {
        val main = "profile-main"
        val other = "alt"
        val mainLikes = likesCodexIdForProfile(main)
        val otherLikes = likesCodexIdForProfile(other)
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                SaveToCodexSheet(
                    profiles = listOf(RecommendationProfile(main, "Main"), RecommendationProfile(other, "Alt")),
                    initialProfileId = main,
                    codicesByProfile = mapOf(main to listOf(Codex(mainLikes, "Likes", 0), Codex("a", "Landscapes", 0)),
                        other to listOf(Codex(otherLikes, "Likes (Alt)", 0))),
                    codexItemCounts = emptyMap(), codexCoverCandidates = emptyMap(),
                    excludedCodexIds = setOf(otherLikes), onCreateCodex = { _, _ -> },
                    onSelectCodex = { selected = it }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Likes").assertDoesNotExist()
        compose.onNodeWithText("Saved").performClick()
        compose.runOnIdle { assertEquals(null, selected) }
        compose.onNodeWithText("Likes").assertIsDisplayed()
        compose.onNodeWithText("Landscapes").assertDoesNotExist()
        compose.onNodeWithText("All Codices").performClick()
        compose.onNodeWithText("Landscapes").assertIsDisplayed()
        compose.onNodeWithText("Saved").performClick()
        compose.onNodeWithText("Likes").performClick()
        compose.runOnIdle { assertEquals(mainLikes, selected) }
        compose.onNodeWithText("Main", substring = false).performClick()
        compose.onNodeWithText("Saved").assertDoesNotExist()
        compose.onNodeWithText("Likes (Alt)").assertDoesNotExist()
    }

    @Test fun `search filters eligible names and keeps profile switching and selection working`() {
        var selectedId: String? = null
        compose.setContent {
            MaterialTheme {
                SaveToCodexSheet(
                    profiles = listOf(RecommendationProfile("main", "Main"), RecommendationProfile("alt", "Alt")),
                    initialProfileId = "main",
                    codicesByProfile = mapOf(
                        "main" to listOf(Codex("a", "Landscapes", 0), Codex("b", "Portraits", 0),
                            Codex("excluded", "Landscape source", 0)),
                        "alt" to listOf(Codex("c", "Landscape sketches", 0)),
                    ),
                    codexItemCounts = emptyMap(),
                    codexCoverCandidates = emptyMap(),
                    excludedCodexIds = setOf("excluded"),
                    onCreateCodex = { _, _ -> },
                    onSelectCodex = { selectedId = it },
                    onDismiss = {},
                )
            }
        }
        val search = compose.onNodeWithText("Search Codices")
        search.performTextReplacement("  LANDscape  ")
        compose.onNodeWithText("Landscapes").assertIsDisplayed()
        compose.onNodeWithText("Portraits").assertDoesNotExist()
        compose.onNodeWithText("Landscape source").assertDoesNotExist()
        search.performTextReplacement("missing")
        compose.onNodeWithText("No matching Codices.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear Codex search").performClick()
        compose.onNodeWithText("Portraits").performScrollTo().assertIsDisplayed()
        search.performTextReplacement("landscape")
        compose.onNodeWithText("Main", substring = false).performClick()
        compose.onNodeWithText("Landscape sketches").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("c", selectedId) }
    }
}
