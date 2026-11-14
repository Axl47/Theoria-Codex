package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Codex
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SaveToCodexSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `already saved indicators follow profile and membership changes`() {
        val likes = likesCodexIdForProfile("main")
        val containing = mutableStateOf(setOf("a", likes))
        compose.setContent {
            MaterialTheme {
                SaveToCodexSheet(
                    profiles = listOf(RecommendationProfile("main", "Main"), RecommendationProfile("alt", "Alt")),
                    initialProfileId = "main",
                    codicesByProfile = mapOf(
                        "main" to listOf(Codex("a", "Art", 0), Codex(likes, "Likes", 0)),
                        "alt" to listOf(Codex("b", "Other", 0)),
                    ),
                    codexItemCounts = emptyMap(), codexCoverCandidates = emptyMap(),
                    containingCodexIds = containing.value,
                    onCreateCodex = { _, _ -> }, onSelectCodex = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Art").assertIsDisplayed()
        compose.onNodeWithContentDescription("Already in Codex").assertIsDisplayed()
        compose.runOnIdle { containing.value = setOf(likes) }
        compose.onNodeWithContentDescription("Already in Codex").assertDoesNotExist()
        compose.runOnIdle { containing.value = setOf("a") }
        compose.onNodeWithText("Main", substring = false).performClick()
        compose.onNodeWithText("Other").assertIsDisplayed()
        compose.onNodeWithContentDescription("Already in Codex").assertDoesNotExist()
    }

    @Config(qualifiers = "w411dp-h1000dp")
    @Test fun `tag relevance is default and toggle restores library order while search stays active`() {
        compose.setContent {
            MaterialTheme {
                SaveToCodexSheet(
                    profiles = listOf(RecommendationProfile("main", "Main")), initialProfileId = "main",
                    codicesByProfile = mapOf("main" to listOf(
                        Codex("a", "Art first", 0), Codex("b", "Art relevant", 0), Codex("c", "Art tied", 0),
                    )),
                    codexItemCounts = emptyMap(), codexCoverCandidates = emptyMap(),
                    relevanceScores = mapOf("b" to 0.5, "c" to 0.5),
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
        compose.onNodeWithContentDescription("Sort: tag relevance. Switch to library order").performClick()
        assertBefore("Art first", "Art relevant")
        compose.onNodeWithContentDescription("Sort: library order. Switch to tag relevance").performClick()
        assertBefore("Art relevant", "Art first")
    }

    @Config(qualifiers = "w411dp-h1000dp")
    @Test fun `library order applies the library sort and keeps it as the relevance tie break`() {
        compose.setContent {
            MaterialTheme {
                SaveToCodexSheet(
                    profiles = listOf(RecommendationProfile("main", "Main")), initialProfileId = "main",
                    codicesByProfile = mapOf("main" to listOf(
                        Codex("z", "Zoo", 0), Codex("a", "Art", 0), Codex("m", "Moss", 0),
                    )),
                    codexItemCounts = emptyMap(), codexCoverCandidates = emptyMap(),
                    relevanceScores = mapOf("m" to 0.5),
                    librarySort = CodexLibrarySort.NAME_ASCENDING,
                    onCreateCodex = { _, _ -> }, onSelectCodex = {}, onDismiss = {},
                )
            }
        }
        fun top(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(top("Moss") < top("Art") && top("Art") < top("Zoo"))
        compose.onNodeWithContentDescription("Sort: tag relevance. Switch to library order").performClick()
        org.junit.Assert.assertTrue(top("Art") < top("Moss") && top("Moss") < top("Zoo"))
    }

    @Test fun `Likes is never a save target, in any profile`() {
        val main = "profile-main"
        val other = "alt"
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                SaveToCodexSheet(
                    profiles = listOf(RecommendationProfile(main, "Main"), RecommendationProfile(other, "Alt")),
                    initialProfileId = main,
                    codicesByProfile = mapOf(
                        main to listOf(Codex(likesCodexIdForProfile(main), "Likes", 0), Codex("a", "Landscapes", 0)),
                        other to listOf(Codex(likesCodexIdForProfile(other), "Likes (Alt)", 0)),
                    ),
                    codexItemCounts = emptyMap(), codexCoverCandidates = emptyMap(), onCreateCodex = { _, _ -> },
                    onSelectCodex = { selected = it }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Saved").assertDoesNotExist()
        compose.onNodeWithText("Likes").assertDoesNotExist()
        compose.onNodeWithText("Landscapes").performClick()
        compose.runOnIdle { assertEquals("a", selected) }
        compose.onNodeWithText("Main", substring = false).performClick()
        compose.onNodeWithText("Likes (Alt)").assertDoesNotExist()
        compose.onNodeWithText("No Codices in this profile yet.").assertIsDisplayed()
    }

    @Config(qualifiers = "w800dp-h400dp-land")
    @Test fun `landscape puts the controls beside a two-column list`() {
        compose.setContent {
            MaterialTheme {
                SaveToCodexSheet(
                    profiles = listOf(RecommendationProfile("main", "Main")), initialProfileId = "main",
                    codicesByProfile = mapOf("main" to listOf(Codex("a", "Art", 0), Codex("b", "Moss", 0))),
                    codexItemCounts = emptyMap(), codexCoverCandidates = emptyMap(), relevanceScores = emptyMap(),
                    onCreateCodex = { _, _ -> }, onSelectCodex = {}, onDismiss = {},
                )
            }
        }
        fun bounds(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        assertEquals(bounds("Art").top, bounds("Moss").top)
        org.junit.Assert.assertTrue(bounds("Create Codex").right < bounds("Art").left)
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
