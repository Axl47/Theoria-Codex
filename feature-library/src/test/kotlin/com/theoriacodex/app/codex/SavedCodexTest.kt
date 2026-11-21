package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.domain.model.Codex
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SavedCodexTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the Codex grid leaves Likes to Library's Saved section`() {
        val likesId = likesCodexIdForProfile("profile-main")
        val codices = listOf(Codex("ordinary", "Landscapes", 0), Codex(likesId, "Likes", 0))
        compose.setContent {
            MaterialTheme {
                CodexListScreen(codices, mapOf("ordinary" to 2, likesId to 4), emptyMap(),
                    { kotlinx.coroutines.flow.flowOf(CodexActionOptions()) },
                    {}, {}, {}, {}, { _, _, _ -> }, {}, {}, { _, _ -> }, { _, _, _ -> }, {},
                    likesId, { _, _ -> })
            }
        }
        compose.onNodeWithText("Landscapes").assertIsDisplayed()
        compose.onNodeWithText("Likes").assertDoesNotExist()
    }
}
