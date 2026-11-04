package com.theoriacodex.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import android.app.Application
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TabReselectTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `only the reselected tab scrolls to its top`() {
        val reselects = MutableSharedFlow<TopLevelDestination>(extraBufferCapacity = 1)
        var searchScrolls = 0
        var codexScrolls = 0
        compose.setContent {
            CompositionLocalProvider(LocalTabReselects provides reselects) {
                ScrollToTopOnTabReselect(TopLevelDestination.Search) { searchScrolls++ }
                ScrollToTopOnTabReselect(TopLevelDestination.Codex) { codexScrolls++ }
            }
        }
        compose.runOnIdle { reselects.tryEmit(TopLevelDestination.Codex) }
        compose.runOnIdle {
            assertEquals(0, searchScrolls)
            assertEquals(1, codexScrolls)
        }
    }
}
