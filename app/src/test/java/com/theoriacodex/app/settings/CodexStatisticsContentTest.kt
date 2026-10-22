package com.theoriacodex.app.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.statistics.CodexStatisticsSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CodexStatisticsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `selector starts with most watched and changes the displayed collection`() {
        compose.setContent {
            MaterialTheme {
                Column {
                    IndividualCodexStatistics(listOf(row("Most watched", 7L), row("Other", 3L)))
                }
            }
        }
        compose.onNodeWithText("Most watched ▾").assertIsDisplayed().performClick()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText("Other ▾").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("Top 5 Images Watched (time)").assertIsDisplayed()
    }

    private fun row(name: String, entries: Long) = CodexStatisticsSummary(
        name, name, entries, 100L, 200L, 0L, emptyList(), emptyList(), emptyList(),
    )
}
