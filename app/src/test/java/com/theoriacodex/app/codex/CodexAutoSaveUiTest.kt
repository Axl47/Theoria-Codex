package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h915dp")
class CodexAutoSaveUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `tag list appears only while adding and new requirements get their own group`() {
        val rules = mutableStateOf(listOf(CodexAutomaticTag(SourceKey.GELBOORU, "landscape")))
        compose.setContent {
            MaterialTheme {
                CodexAutoSaveSheet(
                    codexName = "Scenery",
                    automaticTags = rules.value,
                    tagOptionsBySource = mapOf(
                        SourceKey.GELBOORU to listOf(
                            CodexSearchTagOption("landscape", 3),
                            CodexSearchTagOption("sunset", 2),
                            CodexSearchTagOption("forest", 1),
                        ),
                    ),
                    loading = false,
                    failed = false,
                    onRetry = {},
                    onSetAutomaticTag = { tag, enabled ->
                        rules.value = if (enabled) rules.value + tag else rules.value - tag
                    },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Filter tags").assertDoesNotExist()

        compose.onNodeWithText("Add another requirement").performClick()
        compose.onNodeWithText("Filter tags").assertIsDisplayed()
        compose.onNodeWithText("sunset").performClick()
        // The picker stays on the new group, so the next tag is an alternative within it.
        compose.onNodeWithText("forest").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(1, 1),
                rules.value.filter { it.tag != "landscape" }.map(CodexAutomaticTag::groupIndex),
            )
        }

        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Filter tags").assertDoesNotExist()

        compose.onNodeWithContentDescription("Remove sunset from Auto-save").performClick()
        compose.runOnIdle { assertEquals(listOf("landscape", "forest"), rules.value.map { it.tag }) }
    }
}
