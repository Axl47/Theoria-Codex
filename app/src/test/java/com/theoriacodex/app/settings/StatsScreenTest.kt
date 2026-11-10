package com.theoriacodex.app.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasScrollAction
import com.theoriacodex.app.statistics.CodexPostStatistic
import com.theoriacodex.app.statistics.CodexStatisticsSummary
import com.theoriacodex.app.statistics.StatisticsSummary
import com.theoriacodex.app.statistics.StatsShareCodex
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.app.statistics.StatsSharePost
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
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
        compose.onNodeWithText("Settings / Stats").assertExists()
    }

    @Test
    fun `leaving a Codex out of a share removes it from what they will see`() {
        val shared = mutableListOf<StatsShareFile>()
        show(StatisticsSummary(codices = listOf(codex("a", "Landscapes", 3_600_000L), codex("b", "Portraits", 60_000L))),
            StatsActions(share = { shared += it }))
        compose.onNodeWithText("Share my stats").performClick()
        compose.onNodeWithText("Landscapes").performScrollTo().performClick()
        compose.onNodeWithText("Share file").performScrollTo().performClick()
        compose.waitUntil(5_000) { shared.isNotEmpty() }

        assertEquals(listOf("Portraits"), shared.single().codices.orEmpty().map { it?.name })
    }

    @Test
    fun `a compared post opens only when its site is set up here`() {
        val opened = mutableListOf<PostId>()
        val theirs = StatsShareFile(name = "Sam", codices = listOf(StatsShareCodex(name = "Favorites", posts = listOf(
            StatsSharePost(key = "GELBOORU:7", label = "Ready post", opens = 4L),
            StatsSharePost(key = "PIXIV:9", label = "Missing site post", opens = 2L),
        ))))
        show(StatisticsSummary(), StatsActions(openPost = { opened += it }), theirs, setOf(SourceKey.GELBOORU))
        compose.onNodeWithText("You vs Sam", substring = true).assertExists()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Theirs"))
        compose.onNodeWithText("Theirs").performClick()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Missing site post"))

        compose.onNodeWithText("Missing site post").performClick()
        compose.onNodeWithText("This site isn't set up here").assertExists()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Ready post"))
        compose.onNodeWithText("Ready post").performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }

        assertEquals(listOf(PostId(SourceKey.GELBOORU, "7")), opened)
    }

    private fun codex(id: String, name: String, watchingMs: Long) =
        CodexStatisticsSummary(id, name, 1L, 0L, watchingMs, 0L, emptyList(), emptyList(), emptyList())

    private fun show(
        statistics: StatisticsSummary,
        actions: StatsActions = StatsActions(),
        comparison: StatsShareFile? = null,
        sources: Set<SourceKey> = emptySet(),
    ) {
        compose.setContent { MaterialTheme { StatsScreen(statistics, actions, comparison, sources, {}, onBack = {}) } }
    }
}
