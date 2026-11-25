package com.theoriacodex.app.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SettingToggles
import com.theoriacodex.domain.series.ExtensionAlerts
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
        (seriesRows + "Series" + SettingDestinations.Extensions.title).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun `extensions sit above everything else and list each alert on its own line (ADR 0221)`() {
        var alerts by mutableStateOf(ExtensionAlerts(missing = 3, updates = 2))
        showSettings(series = true) { alerts }
        val order = listOf(SettingDestinations.Extensions.title, "Recommendation profile", "Stats")
        val tops = order.map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(order, order.zip(tops).sortedBy { it.second }.map { it.first })
        compose.onNodeWithText("3 series need their extension\n2 updates").assertExists()
        compose.onNodeWithText("5").assertExists()

        alerts = ExtensionAlerts(updates = 1)
        compose.onNodeWithText("1 update").assertExists()
        compose.onNodeWithText("1").assertExists()

        alerts = ExtensionAlerts()
        compose.onNodeWithText("1 update").assertDoesNotExist()
        compose.onNodeWithText("1").assertDoesNotExist()
    }

    private fun showSettings(series: Boolean, alerts: () -> ExtensionAlerts = { ExtensionAlerts() }) {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(), onAction = {}, showSeries = series,
                    onOpenExtensions = if (series) ({}) else null,
                    extensionAlerts = alerts(),
                )
            }
        }
    }
}
