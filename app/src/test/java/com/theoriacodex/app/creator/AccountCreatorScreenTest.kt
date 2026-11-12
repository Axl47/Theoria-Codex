package com.theoriacodex.app.creator

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountCreatorScreenTest {
    @get:Rule val compose = createComposeRule()

    private val creator = CreatorProfile(SourceKey.GELBOORU, "Account", "42", uploadsQuery = "user:Account")
    private val feeds = listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES)

    private fun show(state: CreatorUiState, actions: MutableList<CreatorAction> = mutableListOf()) {
        compose.setContent {
            MaterialTheme {
                CreatorProfileScreen(
                    state = state,
                    likedPostIds = emptySet(),
                    savedPostIds = emptySet(),
                    onToggleLike = {},
                    onAction = { actions += it },
                    onRequestSaveToCodex = {},
                    onSaveToDevice = {},
                    onOpenUrl = {},
                )
            }
        }
    }

    private fun accountState(details: AccountDetails?) = CreatorUiState(
        creator = creator,
        feeds = feeds,
        details = details,
        queryHash = "creator:GELBOORU:user:Account",
        results = listOf(testPost(source = SourceKey.GELBOORU, sourcePostId = "1")),
    )

    @Test
    fun `an account shows its facts, one Follow button and tabs that select a feed`() {
        val actions = mutableListOf<CreatorAction>()
        show(accountState(AccountDetails(uploadCount = 312, favoriteCount = 1_400, bio = "Hello there")), actions)

        compose.onNodeWithText("Gelbooru · 312 uploads · 1.4k favorites").assertIsDisplayed()
        compose.onNodeWithText("Hello there").assertIsDisplayed()
        compose.onNodeWithText("Uploads").assertIsDisplayed()
        compose.onNodeWithContentDescription("Follow").assertIsDisplayed()
        compose.onNodeWithText("Favorites").performClick()

        compose.runOnIdle { assertEquals(listOf<CreatorAction>(CreatorAction.SelectFeed(AccountFeed.FAVORITES)), actions) }
    }

    @Test
    fun `an account whose favorites are none shows no tabs`() {
        show(accountState(AccountDetails(uploadCount = 3, favoriteCount = 0)))

        compose.onNodeWithText("Gelbooru · 3 uploads").assertIsDisplayed()
        compose.onNodeWithText("Uploads").assertDoesNotExist()
        compose.onNodeWithText("Favorites").assertDoesNotExist()
    }

    @Test
    fun `a creator that is not an account keeps the plain page`() {
        show(accountState(null).copy(feeds = emptyList()))

        compose.onNodeWithText("Gelbooru").assertIsDisplayed()
        compose.onNodeWithText("Uploads").assertDoesNotExist()
        compose.onNodeWithContentDescription("Follow").assertIsDisplayed()
    }
}
