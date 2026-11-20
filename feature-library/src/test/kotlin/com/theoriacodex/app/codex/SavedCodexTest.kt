package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.domain.model.Codex
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SavedCodexTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `holding each library tile dispatches that Saved collection's actions`() {
        val likesId = "likes"
        val held = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CodexListScreen(listOf(Codex(likesId, "Likes", 0)), mapOf(likesId to 1), emptyMap(),
                    { kotlinx.coroutines.flow.flowOf(CodexActionOptions()) },
                    {}, {}, {}, {}, { _, _, _ -> }, {}, {}, { _, _ -> }, { _, _, _ -> }, {},
                    likesId, { _, _ -> }, onOpenSavedCollectionActions = { held += it })
            }
        }
        listOf("Likes", "Followed", "Searches").forEach { name ->
            compose.onNodeWithText(name).performScrollTo().performTouchInput { longClick() }
        }
        compose.runOnIdle { assertEquals(listOf(likesId, FOLLOWED_CODEX_ID, SAVED_SEARCHES_CODEX_ID), held) }
    }

    @Test fun `Saved replaces Likes at its ordered position and opens its container`() {
        val likesId = likesCodexIdForProfile("profile-main")
        val codices = listOf(Codex("ordinary", "Landscapes", 0), Codex(likesId, "Likes", 0))
        assertEquals(listOf("ordinary", SAVED_CODEX_ID), savedCodexLibraryEntries(codices, likesId).map { it.codexId })
        var opened: String? = null
        compose.setContent {
            MaterialTheme {
                CodexListScreen(codices, mapOf(likesId to 4, FOLLOWED_CODEX_ID to 2, SAVED_SEARCHES_CODEX_ID to 3),
                    emptyMap(), { kotlinx.coroutines.flow.flowOf(CodexActionOptions()) },
                    { opened = it }, {}, {}, {}, { _, _, _ -> }, {}, {}, { _, _ -> }, { _, _, _ -> }, {},
                    likesId, { _, _ -> })
            }
        }
        compose.onNodeWithText("4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("Likes").performClick()
        compose.runOnIdle { assertEquals(likesId, opened) }
        compose.onNodeWithText("Followed").performClick()
        compose.runOnIdle { assertEquals(FOLLOWED_CODEX_ID, opened) }
        compose.onNodeWithText("Searches").performClick()
        compose.runOnIdle { assertEquals(SAVED_SEARCHES_CODEX_ID, opened) }
        compose.onNodeWithText("Saved").performClick()
        compose.runOnIdle { assertEquals(SAVED_CODEX_ID, opened) }
    }
}
