package com.theoriacodex.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TabSwipeCommitTest {
    @get:Rule val compose = createComposeRule()

    private fun slowDrag(fraction: Float): Int {
        lateinit var pager: PagerState
        compose.setContent {
            pager = rememberPagerState(pageCount = { 3 })
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("tabs"), flingBehavior = topLevelPagerFling(pager)) {
                Box(Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("tabs").performTouchInput {
            val start = Offset(width * 0.9f, height / 2f)
            down(start)
            moveTo(start - Offset(width * fraction, 0f))
            // Holding still removes the release velocity, so only distance decides.
            advanceEventTime(300)
            moveBy(Offset.Zero)
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
        return pager.currentPage
    }

    @Test fun `a slow drag past the commit point changes tab`() = assertEquals(1, slowDrag(0.28f))

    @Test fun `a slow drag short of the commit point stays`() = assertEquals(0, slowDrag(0.05f))
}
