package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SettingToggles

private val CONTROL_TOGGLES = listOf(
    SettingToggles.OldSearchBar, SettingToggles.InstantSeek, SettingToggles.InvertScrollDirection, SettingToggles.AutoRotateVideos,
)

/**
 * Settings' Controls sheet. Default video quality is here as well as in the Viewer's playback menu, so the
 * search box has a picker to open without a video.
 */
@Composable
fun PreferencesSection(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SettingDestinations.Controls.title, style = MaterialTheme.typography.titleMedium)
        CONTROL_TOGGLES.forEach { toggle ->
            SwitchRow(toggle, state.settings, contentPadding = PaddingValues(vertical = 4.dp)) {
                onAction(SettingsAction.SetToggle(toggle, it))
            }
        }
        val quality = SettingChoices.DefaultVideoQuality
        QualityChoices(quality, quality.read(state.settings)) { onAction(SettingsAction.SetChoice(quality, it)) }
    }
}
