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
import androidx.compose.ui.test.performSemanticsAction
import com.theoriacodex.app.viewer.state.ViewerPlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerPlaybackSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `play mode selection and all five slider stops remain accessible`() {
        val mode = mutableStateOf(ViewerPlaybackMode.LOOP)
        val rate = mutableStateOf(ViewerPlaybackRate.Normal)
        compose.setContent {
            MaterialTheme {
                Column {
                    PlaybackModeChoices(mode.value, { mode.value = it }, {})
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
}
