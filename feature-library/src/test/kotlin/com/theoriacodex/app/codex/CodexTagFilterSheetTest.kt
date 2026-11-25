package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexSortMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h915dp")
class CodexTagFilterSheetTest {
    @get:Rule val compose = createComposeRule()
    private val tags = codexTags(
        listOf(
            testPost(sourcePostId = "1", canonicalTags = listOf("fog", "boats")),
            testPost(sourcePostId = "2", canonicalTags = listOf("boats", "snow")),
            testPost(sourcePostId = "3", canonicalTags = listOf("sea")),
        ),
    )
    private var selected by mutableStateOf(emptyList<String>())
    private val filter get() = CodexTagFilter(tags, selected, { selected = it }, sheet = null, onSheetChange = {})

    @Test fun `typing narrows the tags and choosing one filters at once`() {
        compose.setContent { MaterialTheme { CodexTagFilterSheet(filter, onSearchSites = {}, onDismiss = {}) } }
        compose.onNodeWithText("Filter tags").performTextInput("sn")
        compose.onNodeWithText("fog").assertDoesNotExist()
        compose.onNodeWithText("snow").performClick()
        compose.runOnIdle { assertEquals(listOf(tags.first { it.tag == "snow" }.key), selected) }
        compose.onNodeWithText("snow").assertIsSelected()
    }

    @Test fun `the first chip shows the chosen tags and clears them`() {
        compose.setContent {
            MaterialTheme {
                CodexDetailChipRow(CodexDetailChips(CodexSortMode.NEWEST_SAVED, listOf(CodexSortMode.NEWEST_SAVED), {}, filter, null))
            }
        }
        compose.onNodeWithText("Tags").assertExists()
        compose.onNodeWithText("boats").performClick()
        compose.onNodeWithText("fog").performClick()
        compose.onNodeWithText("boats, fog").assertExists()
        compose.onNodeWithContentDescription("Clear tags").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
        compose.onNodeWithText("Tags").assertExists()
    }

    @Test fun `the deck chip opens the swipe deck under its own name`() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                CodexDetailChipRow(CodexDetailChips(CodexSortMode.NEWEST_SAVED, listOf(CodexSortMode.NEWEST_SAVED), {}, filter) { opened = true })
            }
        }
        compose.onNodeWithText("Swipe").performClick()
        compose.runOnIdle { assertEquals(true, opened) }
    }
}
