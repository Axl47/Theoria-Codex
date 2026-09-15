package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        follows.forEach { follow ->
            compose.onNodeWithText(follow.creator.source.displayName()).performClick()
        }
        follows.forEach { follow ->
            compose.onNodeWithText("Same author · ${follow.creator.source.displayName()}").performClick()
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
