package com.theoriacodex.app.creator

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FollowedCreatorsCheckButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a check that ends at once still spins before the button returns`() {
        var refreshing by mutableStateOf(false)
        compose.setContent { MaterialTheme { CheckButton(refreshing) { refreshing = true } } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Check for new posts").assertIsDisplayed()

        refreshing = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        refreshing = false
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("Checking for new posts").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Check for new posts").assertIsDisplayed()
    }
}
