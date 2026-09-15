package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsProperties
import com.theoriacodex.data.repository.CodexSortMode
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.followKey
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FollowedCodexFiltersTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `drawer has one vertical scroller and two author columns with stable swipe settlement`() {
        val follows = (1..40).map { index ->
            FollowedCreator(CreatorProfile(SourceKey.PIXIV, "Author $index", "$index",
                uploadsQuery = "user:$index"), "$index")
        }
        compose.setContent {
            MaterialTheme {
                CodexFilterSheet(
                    filters = CodexCollectionFilters(), sourceOptions = listOf(SourceKey.PIXIV),
                    supportsLanguage = false, supportsFullColor = false,
                    sortMode = CodexSortMode.NEWEST_SAVED, onFiltersChange = {}, onSortChange = {},
                    onReset = {}, onDismiss = {},
                    followedFilters = { FollowedCodexFilters(follows, FeedFabRestoreState(), {}, {}) },
                )
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .assertCountEquals(1)
        compose.onNodeWithText("Source").performScrollTo()
        val sort = compose.onNodeWithText("Sort").fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithText("Source").fetchSemanticsNode().boundsInRoot
        assertTrue(sort.top < source.top)
        compose.onNodeWithTag("followed-author:PIXIV:1").performScrollTo()
        val first = compose.onNodeWithTag("followed-author:PIXIV:1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("followed-author:PIXIV:2").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left >= first.right)
        val scroller = compose.onNodeWithTag("codex-filter-scroll")
        scroller.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val offset = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(offset > 0f)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(offset,
            scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
    }

    @Test fun `author and source controls allow multiple selections without crossing author identities`() {
        val follows = listOf(SourceKey.PIXIV, SourceKey.IWARA).map { source ->
            FollowedCreator(CreatorProfile(source, "Same author", "1", uploadsQuery = "user:1"), source.name)
        }
        val state = mutableStateOf(FeedFabRestoreState())
        compose.setContent {
            MaterialTheme { Column {
                FollowedCodexFilters(follows, state.value, { state.value = it }, {})
            } }
        }
        compose.onNodeWithText(SourceKey.PIXIV.displayName()).performClick()
        compose.onNodeWithTag("followed-author:IWARA:1").assertDoesNotExist()
        compose.onNodeWithTag("followed-author:PIXIV:1").assertIsDisplayed()
        compose.onNodeWithText(SourceKey.IWARA.displayName()).performClick()
        follows.forEach { follow ->
            compose.onNodeWithTag("followed-author:${follow.creator.followKey()}").performClick()
        }
        compose.runOnIdle {
            assertEquals(follows.map { it.creator.followKey() }, state.value.followedAuthors)
            assertEquals(listOf("PIXIV", "IWARA"), state.value.followedSources)
        }
        compose.onNodeWithText("Find author").performTextInput("missing")
        compose.onNodeWithText("No matching authors").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, state.value.followedAuthors.size) }
        compose.onNodeWithText("All authors (2 selected)").performClick()
        compose.onNodeWithText("All").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), state.value.followedAuthors)
            assertEquals(emptyList<String>(), state.value.followedSources)
        }
    }
}
