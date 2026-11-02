package com.theoriacodex.app.recommend

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
        // The first "forest" is the jump chip; the second is the shelf heading.
        compose.onAllNodesWithText("forest")[1].performClick()
        compose.onNodeWithText("Pin").assertDoesNotExist()
        fun inMenu(label: String) {
            compose.onNodeWithContentDescription("Actions for forest").performClick()
            compose.onNodeWithText(label).performClick()
        }
        inMenu("Pin")
        inMenu("Dismiss")
        inMenu("Save to Codex")
        compose.onNodeWithContentDescription("For You actions").performClick()
        compose.onNodeWithText("New search").performClick()
        compose.onNodeWithContentDescription("Refresh For You").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ForYouAction.OpenSection(section.id), ForYouAction.PinSection(section.id),
                ForYouAction.DismissSection(section.id), ForYouAction.SaveSection(section.id), ForYouAction.NewSection, ForYouAction.RefreshSections), actions)
        }
    }

    @Test fun `pinned sections retain unpin and can be dismissed without deleting the saved search`() {
        val section = emptySection().copy(savedSearchId = "pin")
        val actions = mutableListOf<ForYouAction>()
        render(ForYouHomeState(visible = true, sections = listOf(section)), actions)
        compose.onNodeWithContentDescription("Actions for forest").performClick()
        compose.onNodeWithText("Dismiss").assertIsEnabled()
        compose.onNodeWithText("Unpin").performClick()
        compose.runOnIdle { assertEquals(listOf(ForYouAction.PinSection(section.id)), actions) }
    }

    @Test fun `reorder uses shared move actions and commits the selected row order`() {
        val actions = mutableListOf<ForYouAction>()
        val first = emptySection()
        val second = first.copy(id = "PIXIV:city", title = "city")
        render(ForYouHomeState(visible = true, sections = listOf(first, second)), actions)
        compose.onNodeWithContentDescription("For You actions").performClick()
        compose.onNodeWithText("Reorder").performClick()
        compose.onNodeWithContentDescription("Reorder forest").performClick()
        compose.onNodeWithText("Move down").performClick()
        compose.onNodeWithContentDescription("Done reordering searches").performClick()
        compose.runOnIdle { assertEquals(listOf(ForYouAction.ReorderSections(listOf(second.id, first.id))), actions) }
    }

    @Test fun `interest chips jump to their shelf and mark pinned and explore shelves`() {
        val first = emptySection()
        val second = first.copy(id = "PIXIV:city", title = "city", discovery = true)
        render(ForYouHomeState(visible = true, sections = listOf(first, second)), mutableListOf())
        compose.onNodeWithText("Your mix").assertExists()
        compose.onNodeWithText("Explore").assertExists()
        compose.onAllNodesWithText("city")[0].performClick()
        compose.onNodeWithContentDescription("Actions for city").assertExists()
    }

    @Test fun `source selector sits on the header center line without a redundant row source label`() {
        render(ForYouHomeState(visible = true, sections = listOf(emptySection())), mutableListOf())
        val selector = compose.onNodeWithContentDescription("Select For You source").getUnclippedBoundsInRoot()
        val refresh = compose.onNodeWithContentDescription("Refresh For You").getUnclippedBoundsInRoot()
        assertEquals((refresh.top.value + refresh.bottom.value) / 2, (selector.top.value + selector.bottom.value) / 2, 1f)
        compose.onNodeWithText("Pixiv").assertDoesNotExist()
    }

    private fun render(home: ForYouHomeState, actions: MutableList<ForYouAction>) {
        compose.setContent {
            MaterialTheme {
                ForYouHomeScreen(ForYouUiState(home = home), emptySet(), null, { it },
                    noMediaDurationStateForPost, { _, _ -> }, { _, _ -> }, {}, {},
                    onAction = { actions += it }, onShowFilters = {}, filterLabels = emptyList(), onClearFilters = { {} },
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
