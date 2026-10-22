package com.theoriacodex.app.settings

import android.app.Application
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
@Config(application = Application::class, sdk = [35])
class UnifiedSourceSettingsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun singleSourceShowsActivationHelpWithoutWeightControls() {
        composeRule.setContent {
            MaterialTheme {
                UnifiedSourceSettings(setOf(SourceKey.PIXIV), SourceRuntimeSettings()) {
                    error("Single-source settings must not write weights")
                }
            }
        }
        composeRule.onNodeWithText("Activate Unified by holding down sources in Search.").assertExists()
        composeRule.onNodeWithContentDescription("Pixiv weight").assertDoesNotExist()
    }

    @Test
    fun selectedSourcesHaveSteppedWeightControls() {
        val updates = mutableListOf<Map<SourceKey, Double>>()
        val sources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU)
        composeRule.setContent {
            MaterialTheme {
                UnifiedSourceSettings(sources, SourceRuntimeSettings(), updates::add)
            }
        }
        val slider = composeRule.onNodeWithContentDescription("Pixiv weight")
        assertEquals(19, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        composeRule.onNodeWithContentDescription("Gelbooru weight").assertExists()
        composeRule.onNodeWithContentDescription("Iwara weight").assertDoesNotExist()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        composeRule.runOnIdle {
            assertTrue(updates.isNotEmpty())
            assertEquals(sources, updates.last().keys)
            assertEquals(0.75, updates.last().getValue(SourceKey.PIXIV), 0.00001)
        }
    }
}
