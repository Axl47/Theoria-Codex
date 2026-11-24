package com.theoriacodex.app.viewer

import android.app.Application
import android.view.View
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerKeepScreenOnTest {
    @get:Rule val compose = createComposeRule()
    private val slideshow = mutableStateOf(false)
    private val activity = mutableIntStateOf(0)
    private lateinit var view: View

    private fun show(reading: Boolean, slideshow: Boolean = false) {
        this.slideshow.value = slideshow
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = LocalView.current
            ViewerKeepScreenOnEffect(this.slideshow.value, reading, activity.intValue)
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun idleFor(millis: Long) = compose.mainClock.advanceTimeBy(millis)

    private fun interact() = compose.runOnUiThread {
        activity.intValue += 1
        Snapshot.sendApplyNotifications()
    }

    @Test fun `an open chapter lets the screen sleep after a long idle and wakes it on interaction`() {
        show(reading = true)
        assertTrue(view.keepScreenOn)
        idleFor(VIEWER_READING_IDLE_MS - 60_000L)
        interact()
        idleFor(VIEWER_READING_IDLE_MS - 60_000L)
        assertTrue(view.keepScreenOn)
        idleFor(120_000L)
        assertFalse(view.keepScreenOn)
        interact()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        assertTrue(view.keepScreenOn)
    }

    @Test fun `a slideshow keeps the screen on however long it runs`() {
        show(reading = true, slideshow = true)
        idleFor(VIEWER_READING_IDLE_MS * 3)
        assertTrue(view.keepScreenOn)
    }
}
