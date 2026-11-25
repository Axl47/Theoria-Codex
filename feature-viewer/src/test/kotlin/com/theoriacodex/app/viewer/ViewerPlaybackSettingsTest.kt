package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.theoriacodex.app.viewer.state.ViewerPlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerPlaybackSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `menu stays open after selection and expires five seconds after the latest interaction`() {
        compose.mainClock.autoAdvance = false
        val expanded = mutableStateOf(true)
        val mode = mutableStateOf(ViewerPlaybackMode.LOOP)
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsMenu(
                    playbackRateEnabled = true, playbackMode = mode.value, autoAdvancePaused = false, onPlaybackModeSelected = { mode.value = it },
                    quality = com.theoriacodex.domain.model.VideoQuality.AUTO, qualityHeight = null, videoVariants = emptyList(),
                    onQualitySelected = { _, _ -> }, expanded = expanded.value, onExpandedChange = { expanded.value = it },
                    playbackRate = ViewerPlaybackRate.Normal, onPlaybackRateSelected = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText(ViewerPlaybackMode.ONCE.label).performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle { assertTrue(expanded.value); assertEquals(ViewerPlaybackMode.ONCE, mode.value) }
        compose.onNodeWithText("Loop").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle { assertTrue(expanded.value) }
        compose.mainClock.advanceTimeBy(1_100)
        compose.runOnIdle { assertFalse(expanded.value) }
    }

    @Test fun `play mode selection and all five slider stops remain accessible`() {
        val mode = mutableStateOf(ViewerPlaybackMode.LOOP)
        val rate = mutableStateOf(ViewerPlaybackRate.Normal)
        compose.setContent {
            MaterialTheme {
                Column {
                    PlaybackModeChoices(mode.value, false, { mode.value = it }, {})
                    PlaybackRateSlider(rate.value) { rate.value = it }
                }
            }
        }
        compose.onNodeWithText("Loop").assertIsSelected()
        ViewerPlaybackMode.entries.forEach { option ->
            compose.onNodeWithText(option.label).performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText(option.label).assertIsSelected()
            ViewerPlaybackMode.entries.filter { it != option }.forEach { other ->
                compose.onNodeWithText(other.label).assertIsNotSelected()
            }
        }
        ViewerPlaybackRate.entries.forEachIndexed { index, option ->
            compose.onNodeWithContentDescription("Playback speed")
                .performSemanticsAction(SemanticsActions.SetProgress) { set -> set(index.toFloat()) }
            compose.runOnIdle { assertEquals(option, rate.value) }
            compose.onNodeWithText("Speed: ${option.menuLabel}").assertExists()
        }
    }

    @Test fun `the settings button carries a pause badge only while auto-advance is held (ADR 0217)`() {
        val paused = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsMenu(
                    playbackRateEnabled = true, playbackMode = ViewerPlaybackMode.NEXT, autoAdvancePaused = paused.value, onPlaybackModeSelected = {},
                    quality = com.theoriacodex.domain.model.VideoQuality.AUTO, qualityHeight = null, videoVariants = emptyList(),
                    onQualitySelected = { _, _ -> }, expanded = false, onExpandedChange = {},
                    playbackRate = ViewerPlaybackRate.Normal, onPlaybackRateSelected = {},
                )
            }
        }
        compose.onNodeWithTag(VIEWER_AUTO_ADVANCE_PAUSED_TAG, useUnmergedTree = true).assertExists()
        compose.runOnIdle { paused.value = false }
        compose.onNodeWithTag(VIEWER_AUTO_ADVANCE_PAUSED_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}
