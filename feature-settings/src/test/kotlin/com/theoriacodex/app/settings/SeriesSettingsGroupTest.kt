package com.theoriacodex.app.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SettingToggles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SeriesSettingsGroupTest {
    @get:Rule val compose = createComposeRule()

    private val seriesRows = listOf(
        SettingDestinations.Extensions.title,
        SettingChoices.SeriesUpdates.title,
        SettingToggles.SeriesDeleteAfterReading.title,
    )

    @Test
    fun `series settings sit in their own group after sources (ADR 0212)`() {
        showSettings(series = true)
        val order = listOf("Sources", "Open links in Theoria", "Series") + seriesRows + "Viewing"
        val tops = order.map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(order, order.zip(tops).sortedBy { it.second }.map { it.first })
    }

    @Test
    fun `without series the group and its rows are absent (ADR 0212)`() {
        showSettings(series = false)
        (seriesRows + "Series").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    private fun showSettings(series: Boolean) {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(), onAction = {}, showSeries = series,
                    onOpenExtensions = if (series) ({}) else null,
                )
            }
        }
    }
}
