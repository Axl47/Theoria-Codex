package com.theoriacodex.app.recommend

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForYouHomeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `section title pin dismiss and shuffle expose the matching actions`() {
        val actions = mutableListOf<ForYouAction>()
        val section = emptySection()
        render(ForYouHomeState(visible = true, sections = listOf(section)), actions)
        compose.onNodeWithText("forest").performClick()
        compose.onNodeWithText("Pin").performClick()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithContentDescription("Shuffle interests").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ForYouAction.OpenSection(section.id), ForYouAction.PinSection(section.id),
                ForYouAction.DismissSection(section.id), ForYouAction.RefreshSections), actions)
        }
    }

    @Test fun `pinned sections offer unpin and protect the saved search from dismissal`() {
        val section = emptySection().copy(savedSearchId = "pin")
        val actions = mutableListOf<ForYouAction>()
        render(ForYouHomeState(visible = true, sections = listOf(section)), actions)
        compose.onNodeWithText("Dismiss").assertIsNotEnabled()
        compose.onNodeWithText("Unpin").performClick()
        compose.runOnIdle { assertEquals(listOf(ForYouAction.PinSection(section.id)), actions) }
    }

    private fun render(home: ForYouHomeState, actions: MutableList<ForYouAction>) {
        compose.setContent {
            MaterialTheme {
                ForYouHomeScreen(ForYouUiState(home = home), emptySet(), null, { it },
                    noMediaDurationStateForPost, { _, _ -> }, { _, _ -> }, {}, {},
                    onAction = { actions += it }, onShowFilters = {}, filtersActive = false,
                    onOpenPreview = { _, _, _ -> })
            }
        }
    }

    private fun emptySection(): ForYouSection {
        val seed = mapOf(SourceKey.PIXIV to listOf("forest"))
        return ForYouSection("PIXIV:forest", "forest", ForYouFeedResult(seed, "PIXIV:forest", 1,
            UnifiedSearchResult(emptyList(), emptyMap(), emptyList()), null))
    }
}
