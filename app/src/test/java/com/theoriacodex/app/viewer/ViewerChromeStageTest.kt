package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerChromeStageTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf(false)
    private var stage = -1

    private fun show(initiallyVisible: Boolean) {
        visible.value = initiallyVisible
        compose.mainClock.autoAdvance = false
        compose.setContent { stage = rememberViewerChromeStage(visible.value) }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun setVisible(value: Boolean) = compose.runOnUiThread {
        visible.value = value
        Snapshot.sendApplyNotifications()
    }

    @Test fun `chrome that starts visible is complete in its first frame`() {
        show(initiallyVisible = true)
        assertEquals(VIEWER_CHROME_STAGE_STRIPS, stage)
    }

    @Test fun `showing chrome spreads its parts across frames and hiding is immediate`() {
        show(initiallyVisible = false)
        assertEquals(0, stage)
        setVisible(true)
        val seen = List(6) { compose.mainClock.advanceTimeByFrame(); stage }.distinct()
        assertEquals(listOf(VIEWER_CHROME_STAGE_TOP_BAR, VIEWER_CHROME_STAGE_TIMELINE, VIEWER_CHROME_STAGE_STRIPS), seen.filter { it > 0 })
        setVisible(false)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0, stage)
    }
}
