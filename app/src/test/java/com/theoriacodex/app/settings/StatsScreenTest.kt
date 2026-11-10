package com.theoriacodex.app.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsOff
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
import com.theoriacodex.app.statistics.ProfileStatistics
import com.theoriacodex.app.statistics.StatisticsSummary
import com.theoriacodex.app.statistics.StatsShareCodex
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.app.statistics.StatsShareOverall
import com.theoriacodex.app.statistics.StatsSharePost
import com.theoriacodex.data.repository.CodexPostStatistics
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
    fun `a post can be inspected without changing what is shared and unchecked separately`() {
        val shared = mutableListOf<StatsShareFile>()
        val opened = mutableListOf<PostId>()
        val posts = mapOf("GELBOORU:1" to CodexPostStatistics("First", opens = 5L), "GELBOORU:2" to CodexPostStatistics("Second", opens = 3L))
        val codices = listOf(codex("a", "Landscapes", 3_600_000L, posts), codex("b", "Portraits", 60_000L))
        compose.setContent {
            MaterialTheme {
                StatsSharePreview(listOf(ProfileStatistics("p", "Main", codices)), StatsShareOverall(), "p", StatsActions(share = { shared += it }), { opened += it }, onBack = {})
            }
        }
        compose.onNodeWithText("Landscapes").performScrollTo().performClick()

        compose.onNodeWithText("First").performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf(PostId(SourceKey.GELBOORU, "1")), opened)

        compose.onNodeWithContentDescription("Include First").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Include Portraits").performScrollTo().performClick()
        compose.onNodeWithText("Share file").performScrollTo().performClick()
        compose.waitUntil(5_000) { shared.isNotEmpty() }

        val codex = shared.single().codices.orEmpty().single()
        assertEquals("Landscapes", codex?.name)
        assertEquals(listOf("GELBOORU:2"), codex?.posts.orEmpty().map { it?.key })
    }

    @Test
    fun `another profile joins the file only when included, and Download saves the same selection`() {
        val downloaded = mutableListOf<StatsShareFile>()
        val profiles = listOf(
            ProfileStatistics("main", "Main", listOf(codex("a", "Landscapes", 1_000L), codex("system:saved", "Saved", 500L))),
            ProfileStatistics("alt", "Alt", listOf(codex("b", "Private", 2_000L), codex("system:saved", "Saved", 500L))),
        )
        compose.setContent {
            MaterialTheme { StatsSharePreview(profiles, StatsShareOverall(), "main", StatsActions(download = { downloaded += it }), {}, onBack = {}) }
        }
        compose.onNodeWithText("Alt").performClick()
        compose.onNodeWithContentDescription("Include profile Alt").performScrollTo().assertIsOff()
        compose.onNodeWithText("Download").performScrollTo().performClick()
        compose.waitUntil(5_000) { downloaded.size == 1 }
        assertEquals(setOf("Landscapes", "Saved"), downloaded.last().codices.orEmpty().map { it?.name }.toSet())
        assertEquals(StatsShareOverall(), downloaded.last().overall)

        compose.onNodeWithContentDescription("Include profile Alt").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Download").performScrollTo().performClick()
        compose.waitUntil(5_000) { downloaded.size == 2 }
        assertEquals(listOf("Landscapes", "Private", "Saved"), downloaded.last().codices.orEmpty().map { it?.name }.sortedBy { it })

        compose.onNodeWithContentDescription("Include overall stats").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Download").performScrollTo().performClick()
        compose.waitUntil(5_000) { downloaded.size == 3 }
        assertEquals(null, downloaded.last().overall)
    }

    @Test
    fun `a compared post opens and looks up its thumbnail only when its site is set up here`() {
        val opened = mutableListOf<PostId>()
        val looked = mutableListOf<PostId>()
        val theirs = StatsShareFile(name = "Sam", codices = listOf(StatsShareCodex(name = "Favorites", posts = listOf(
            StatsSharePost(key = "GELBOORU:7", label = "Ready post", opens = 4L),
            StatsSharePost(key = "PIXIV:9", label = "Missing site post", opens = 2L),
        ))))
        compose.setContent {
            MaterialTheme { StatsComparisonScreen(
                emptyList(), StatsShareOverall(), theirs, setOf(SourceKey.GELBOORU), { looked += it; null }, { opened += it }, onBack = {},
            ) }
        }
        compose.onNodeWithText("You vs Sam", substring = true).assertExists()
        compose.onNodeWithText("Sam didn't share overall stats.").assertExists()
        compose.onNodeWithText("Theirs").performScrollTo().performClick()

        compose.onNodeWithText("Missing site post").performScrollTo().performClick()
        compose.onNodeWithText("This site isn't set up here").assertExists()
        compose.onNodeWithText("Ready post").performScrollTo().performClick()
        compose.waitUntil(5_000) { opened.isNotEmpty() }

        assertEquals(listOf(PostId(SourceKey.GELBOORU, "7")), opened)
        assertEquals(listOf(PostId(SourceKey.GELBOORU, "7")), looked)
    }

    private fun codex(id: String, name: String, watchingMs: Long, posts: Map<String, CodexPostStatistics> = emptyMap()) =
        CodexStatisticsSummary(id, name, 1L, 0L, watchingMs, 0L, emptyList(), emptyList(), emptyList(), posts)

    private fun show(statistics: StatisticsSummary) {
        compose.setContent { MaterialTheme { StatsScreen(statistics, onOpenPost = {}, onBack = {}) } }
    }
}
