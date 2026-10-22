package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.app.source.displayName
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SearchSourcePickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourceMenuPreservesSelectionAndLongPressMultiSearch() {
        var scope by mutableStateOf(
            SearchSourceScope.fromSources(listOf(SourceKey.PIXIV, SourceKey.GELBOORU)),
        )
        val clicked = mutableListOf<QueryMode>()
        val toggled = mutableListOf<SourceKey>()

        composeRule.setContent {
            MaterialTheme {
                SearchSourcePicker(
                    draftSourceScope = scope,
                    options = listOf(
                        QueryMode.Unified,
                        QueryMode.Source(SourceKey.PIXIV),
                        QueryMode.Source(SourceKey.GELBOORU),
                    ),
                    unifiedSourceCount = 2,
                    onModeSelected = { clicked += it },
                    onTemporarySourceToggled = { source ->
                        toggled += source
                        scope = SearchSourceScope.fromSources(scope.explicitSources - source)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Search source: Multi-Search, 2 sources").performClick()

        val unified = composeRule.onNodeWithContentDescription("Unified")
        val pixiv = composeRule.onNodeWithContentDescription("Pixiv")
        val gelbooru = composeRule.onNodeWithContentDescription("Gelbooru")
        val pixivBounds = pixiv.fetchSemanticsNode().boundsInRoot
        val gelbooruBounds = gelbooru.fetchSemanticsNode().boundsInRoot
        assertTrue("Sources are stacked vertically", gelbooruBounds.top >= pixivBounds.bottom)
        pixiv.assertIsSelected()
        gelbooru.assertIsSelected()
        unified.assertIsNotSelected()
        assertCombinedOwner(pixiv, hasLongClick = true)
        assertCombinedOwner(unified, hasLongClick = false)

        pixiv.performTouchInput {
            down(center)
            advanceEventTime(100)
            up()
        }
        unified.assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Search source: Multi-Search, 2 sources").performClick()
        gelbooru.performTouchInput {
            down(center)
            advanceEventTime(1_000)
            up()
        }
        composeRule.runOnIdle {
            assertEquals(listOf(QueryMode.Source(SourceKey.PIXIV)), clicked)
            assertEquals(listOf(SourceKey.GELBOORU), toggled)
        }
        pixiv.assertIsSelected()
        gelbooru.assertIsNotSelected()
        unified.assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Search source: Pixiv").assertExists()
    }

    @Test
    fun unifiedHighlightsIncludedSourcesAndR34SubmenuReturnsToRoot() {
        val clicked = mutableListOf<QueryMode>()
        composeRule.setContent {
            MaterialTheme {
                SearchSourcePicker(
                    draftSourceScope = SearchSourceScope.GlobalUnified,
                    options = listOf(QueryMode.Unified) + listOf(
                        SourceKey.PIXIV, SourceKey.IWARA, SourceKey.RULE34XXX,
                        SourceKey.RULE34PAHEAL, SourceKey.RULE34VIDEO,
                    ).map(QueryMode::Source),
                    unifiedSourceCount = 2,
                    unifiedSources = setOf(SourceKey.PIXIV, SourceKey.RULE34XXX),
                    onModeSelected = { clicked += it },
                    onTemporarySourceToggled = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Search source: Unified, 2 sources").performClick()
        composeRule.onNodeWithContentDescription("Unified").assertIsSelected()
        composeRule.onNodeWithContentDescription("Pixiv").assertIsSelected()
        composeRule.onNodeWithContentDescription("Iwara").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("R34").assertIsSelected().performClick()
        composeRule.onNodeWithContentDescription("Pixiv").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SourceKey.RULE34XXX.displayName()).assertIsSelected()
        composeRule.onNodeWithContentDescription(SourceKey.RULE34PAHEAL.displayName()).assertIsNotSelected()
        composeRule.onNodeWithText("← Back").performClick()
        composeRule.onNodeWithContentDescription("R34").performClick()
        composeRule.onNodeWithContentDescription(SourceKey.RULE34VIDEO.displayName()).performClick()
        composeRule.runOnIdle { assertEquals(listOf(QueryMode.Source(SourceKey.RULE34VIDEO)), clicked) }
    }

    private fun assertCombinedOwner(node: SemanticsNodeInteraction, hasLongClick: Boolean) {
        val config = node.fetchSemanticsNode().config
        assertEquals(Role.Checkbox, config[SemanticsProperties.Role])
        assertTrue(config.contains(SemanticsActions.OnClick))
        if (hasLongClick) {
            assertTrue(config.contains(SemanticsActions.OnLongClick))
        } else {
            assertFalse(config.contains(SemanticsActions.OnLongClick))
        }
    }
}
