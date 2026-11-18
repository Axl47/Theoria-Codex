package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.codex.LiveCodexFailures
import com.theoriacodex.app.search.SearchFailureSurface
import com.theoriacodex.app.search.state.SearchErrorUiState
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DismissibleFailureTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `dismiss hides the failure until a different one arrives`() {
        var message by mutableStateOf("Malformed JSON")
        compose.setContent {
            DismissibleFailure(message) { dismiss ->
                FeedErrorTile(message = message, onRetry = {}, onDismiss = dismiss)
            }
        }

        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Malformed JSON").assertDoesNotExist()

        message = "Timed out"
        compose.onNodeWithText("Timed out").assertIsDisplayed()
    }

    @Test
    fun `a page failure from an added site needing a key can be dismissed`() {
        val site = requireNotNull(SourceKey.parse("custom_booru_example"))
        val statuses = listOf(SourceRunStatus(site, SourceRunState.FAILED, failureReason = SourceFailureReason.AUTH_REQUIRED))
        val error = SearchErrorUiState("Key rejected", SearchRequestKind.PAGE, retryable = true)
        compose.setContent {
            DismissibleFailure(error) { dismiss -> SearchFailureSurface(error, statuses, onRetry = {}, onDismiss = dismiss) }
        }

        compose.onNodeWithText("Source account required").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Source account required").assertDoesNotExist()
    }

    @Test
    fun `live Codex branch failures can be dismissed`() {
        compose.setContent {
            LiveCodexFailures(mapOf("a" to "Malformed JSON"), mapOf("a" to "cats"), "Saved", "Searches", true) {}
        }

        compose.onNodeWithText("cats: Malformed JSON").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("cats: Malformed JSON").assertDoesNotExist()
        compose.onNodeWithText("Retry failed searches").assertDoesNotExist()
    }
}
