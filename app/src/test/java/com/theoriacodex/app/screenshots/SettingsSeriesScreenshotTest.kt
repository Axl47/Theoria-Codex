package com.theoriacodex.app.screenshots

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.theoriacodex.app.settings.SettingsScreen
import com.theoriacodex.app.settings.SettingsSitesEntry
import com.theoriacodex.app.settings.SettingsUiState
import com.theoriacodex.app.ui.theme.TheoriaNightTheme
import com.theoriacodex.data.repository.SettingToggles
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSeriesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `settings with series`() {
        compose.setContent {
            TheoriaNightTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        state = SettingsUiState(), onAction = {}, showSeries = true, onOpenExtensions = {},
                        sites = SettingsSitesEntry("Add a booru by its address") { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithText(SettingToggles.SeriesDeleteAfterReading.title).performScrollTo()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("settings_series.png")
    }
}
