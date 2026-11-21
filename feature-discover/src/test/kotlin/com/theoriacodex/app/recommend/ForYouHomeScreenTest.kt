package com.theoriacodex.app.recommend

import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.components.FeedDurations
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.recommend.state.ForYouUiState
import com.theoriacodex.data.repository.RecommendationProfile
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

    @Test fun `section heading why save dismiss new search and refresh expose the matching actions`() {
        val actions = mutableListOf<ForYouAction>()
        val why = mutableListOf<ForYouWhyTarget>()
        val section = emptySection()
        render(ForYouHomeState(visible = true, sections = listOf(section)), actions, onOpenWhy = { why += it })
        // The first "forest" is the jump chip; the second is the shelf heading, which opens the shelf.
        compose.onAllNodesWithText("forest")[1].performClick()
        compose.onNodeWithContentDescription("Why this shelf").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf(section.whyTarget()), why) }
        compose.onNodeWithText("Save search").assertDoesNotExist()
        fun inMenu(label: String) {
            compose.onNodeWithContentDescription("Actions for forest").performClick()
            compose.onNodeWithText(label).performClick()
        }
        inMenu("Save search")
        inMenu("Dismiss")
        inMenu("Move to Saved")
        compose.onNodeWithContentDescription("For You actions").performClick()
        compose.onNodeWithText("New search").performClick()
        compose.onNodeWithContentDescription("Refresh For You").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ForYouAction.OpenSection(section.id), ForYouAction.PinSection(section.id),
                ForYouAction.DismissSection(section.id), ForYouAction.SaveSection(section.id), ForYouAction.NewSection, ForYouAction.RefreshSections), actions)
        }
    }

    @Test fun `saved sections expose dismissal and dispatch removal from Saved`() {
        val section = emptySection().copy(savedSearchId = "pin")
        val actions = mutableListOf<ForYouAction>()
        render(ForYouHomeState(visible = true, sections = listOf(section)), actions)
        compose.onNodeWithContentDescription("Actions for forest").performClick()
        compose.onNodeWithText("Dismiss").assertIsEnabled()
        compose.onNodeWithText("Remove from Saved").performClick()
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

    @Test fun `interest chips jump to their shelf alongside mix and explore labels`() {
        val first = emptySection()
        val second = first.copy(id = "PIXIV:city", title = "city", discovery = true)
        render(ForYouHomeState(visible = true, sections = listOf(first, second)), mutableListOf())
        compose.onNodeWithText("Your mix").assertExists()
        compose.onNodeWithText("Explore").assertExists()
        compose.onAllNodesWithText("city")[0].performClick()
        compose.onNodeWithContentDescription("Actions for city").assertIsDisplayed()
    }

    @Test fun `source selector sits on the header center line without a redundant row source label`() {
        render(ForYouHomeState(visible = true, sections = listOf(emptySection())), mutableListOf())
        val selector = compose.onNodeWithContentDescription("Select For You source").getUnclippedBoundsInRoot()
        val refresh = compose.onNodeWithContentDescription("Refresh For You").getUnclippedBoundsInRoot()
        assertEquals((refresh.top.value + refresh.bottom.value) / 2, (selector.top.value + selector.bottom.value) / 2, 1f)
        compose.onNodeWithText("Pixiv").assertDoesNotExist()
    }

    @Test fun `profile chip shows only with several profiles and selects another`() {
        val selected = mutableListOf<String>()
        val profiles = listOf(RecommendationProfile("main", "Main"), RecommendationProfile("alt", "Night"))
        render(ForYouHomeState(visible = true), mutableListOf(), ForYouUiState(profiles = profiles, activeProfileId = "main"),
            onSelectProfile = { selected += it })
        compose.onNodeWithText("Main").assert(SemanticsMatcher("clicks to switch profile") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "Switch profile"
        }).performClick()
        compose.onNodeWithText("Night").performClick()
        compose.runOnIdle { assertEquals(listOf("alt"), selected) }
    }

    @Test fun `a single profile draws no profile chip`() {
        render(ForYouHomeState(visible = true), mutableListOf(),
            ForYouUiState(profiles = listOf(RecommendationProfile("main", "Main")), activeProfileId = "main"))
        compose.onNodeWithText("Main").assertDoesNotExist()
    }

    private fun render(
        home: ForYouHomeState,
        actions: MutableList<ForYouAction>,
        base: ForYouUiState = ForYouUiState(),
        onOpenWhy: (ForYouWhyTarget) -> Unit = {},
        onSelectProfile: (String) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                val cards = rememberFeedCardEnvironment(null, emptySet(), emptyMap(), FeedDurations.NONE, {})
                ForYouHomeScreen(base.copy(home = home), cards, { it }, {},
                    onAction = { actions += it }, onShowFilters = {}, filterLabels = emptyList(), onClearFilters = { {} },
                    onOpenPreview = { _, _, _ -> }, onOpenWhy = onOpenWhy, onSelectProfile = onSelectProfile)
            }
        }
    }

    private fun emptySection(): ForYouSection {
        val seed = mapOf(SourceKey.PIXIV to listOf("forest"))
        return ForYouSection("PIXIV:forest", "forest", ForYouFeedResult(seed, "PIXIV:forest", 1,
            UnifiedSearchResult(emptyList(), emptyMap(), emptyList()), null))
    }
}
