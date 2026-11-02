package com.theoriacodex.app.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasScrollAction
import com.theoriacodex.app.statistics.CodexPostStatistic
import com.theoriacodex.app.statistics.CodexStatisticsSummary
import com.theoriacodex.app.statistics.StatisticsSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StatsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `new history explains trends and uncategorized time shows as Other`() {
        show(StatisticsSummary(totalForegroundMs = 120_000L, browsingMs = 60_000L))
        compose.onNodeWithText("Other").assertExists()
        compose.onNodeWithText("Your trends start today. Activity appears here day by day.").assertExists()
    }

    @Test
    fun `a Codex opens its top posts and returns to the overview`() {
        val post = CodexPostStatistic("PIXIV:1", "Evening harbor", 60L, 12)
        show(StatisticsSummary(codices = listOf(
            CodexStatisticsSummary("c", "Favorites", 3L, 1_000L, 2_000L, 60L, listOf(post), emptyList(), emptyList()),
        )))
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Favorites"))
        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithText("Evening harbor").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Stats").assertExists()
    }

    private fun show(statistics: StatisticsSummary) {
        compose.setContent { MaterialTheme { StatsScreen(statistics, onBack = {}) } }
    }
}
