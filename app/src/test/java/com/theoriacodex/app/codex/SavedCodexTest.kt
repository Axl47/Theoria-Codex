package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.onNodeWithText("4").assertIsDisplayed()
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
