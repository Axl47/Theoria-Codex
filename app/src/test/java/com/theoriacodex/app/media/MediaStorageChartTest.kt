package com.theoriacodex.app.media

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class MediaStorageChartTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `donut legend shows each category exact byte count and percentage and updates after clearing`() {
        val breakdown = mutableStateOf(mediaStorageBreakdown(500, 200, 150, 100, 50))
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            compose.setContent { TheoriaNightTheme { MediaStorageChart(breakdown.value) } }
            compose.onNodeWithTag("media-storage-chart").assertExists()
            compose.onNodeWithTag("media-storage-total").assertExists()
            listOf("Offline copies", "Image cache", "Video cache", "GIF cache", "Animation cache").forEach {
                compose.onNodeWithText(it).assertExists()
            }
            listOf("50%", "20%", "15%", "10%", "5%").forEach {
                compose.onNodeWithText(it, substring = false).assertExists()
            }
            listOf("500", "200", "150", "100", "50").forEach {
                compose.onNodeWithText("($it bytes)", substring = true).assertExists()
            }
            compose.runOnIdle { breakdown.value = mediaStorageBreakdown(0, 0, 0, 0, 0) }
            compose.onNodeWithText("No managed media stored yet.").assertExists()
            compose.onAllNodesWithText("0%", substring = false).assertCountEquals(5)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
