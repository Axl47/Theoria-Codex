package com.theoriacodex.app.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performClick
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UnifiedSourceSettingsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun singleSourceShowsActivationHelpWithoutWeightControls() {
        var expanded by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                UnifiedSourceSettings(
                    setOf(SourceKey.PIXIV), SourceRuntimeSettings(),
                    expanded = expanded, onToggle = { expanded = !expanded },
                ) {
                    error("Single-source settings must not write weights")
                }
            }
        }
        composeRule.onNodeWithText("Activate Unified by holding down sources in Search.").assertExists()
        composeRule.onNodeWithContentDescription("Pixiv weight").assertDoesNotExist()
        composeRule.onNodeWithText("Select multiple sources to adjust their weights.").assertDoesNotExist()
        composeRule.onNodeWithText("Unified Mode").performClick()
        composeRule.onNodeWithText("Activate Unified by holding down sources in Search.").assertDoesNotExist()
        composeRule.onNodeWithText("Unified Mode").performClick()
        composeRule.onNodeWithText("Activate Unified by holding down sources in Search.").assertExists()
    }

    @Test
    fun selectedSourcesHaveSteppedWeightControls() {
        val updates = mutableListOf<Map<SourceKey, Double>>()
        val sources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU)
        composeRule.setContent {
            MaterialTheme {
                UnifiedSourceSettings(sources, SourceRuntimeSettings(), onWeightsChanged = updates::add)
            }
        }
        val slider = composeRule.onNodeWithContentDescription("Pixiv weight")
        assertEquals(19, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        composeRule.onNodeWithContentDescription("Gelbooru weight").assertExists()
        composeRule.onNodeWithContentDescription("Iwara weight").assertDoesNotExist()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        assertEquals(0.25f, composeRule.onNodeWithContentDescription("Gelbooru weight")
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
        assertEquals(0.75f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
        composeRule.runOnIdle {
            assertTrue(updates.isNotEmpty())
            assertEquals(sources, updates.last().keys)
            assertEquals(0.75, updates.last().getValue(SourceKey.PIXIV), 0.00001)
        }
    }
}
