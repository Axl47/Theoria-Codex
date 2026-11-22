package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.videoQualityLabel
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.data.repository.SettingChoice
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SeriesUpdateInterval
import com.theoriacodex.data.repository.SettingToggles
import com.theoriacodex.domain.model.VideoQuality

/** Settings' Downloads sheet. Default video quality is set from the Viewer's playback menu instead. */
@Composable
fun DownloadPreferencesSection(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    Text(SettingDestinations.Downloads.title, style = MaterialTheme.typography.titleMedium)
    val choice = SettingChoices.DownloadQuality
    QualityChoices(choice, choice.read(state.settings)) { onAction(SettingsAction.SetChoice(choice, it)) }
    listOf(SettingToggles.DownloadsOverMetered, SettingToggles.DownloadsOverRoaming).forEach { toggle ->
        SwitchRow(toggle, state.settings, contentPadding = PaddingValues(vertical = 4.dp)) {
            onAction(SettingsAction.SetToggle(toggle, it))
        }
    }
}

@Composable
private fun QualityChoices(choice: SettingChoice<VideoQuality>, selected: VideoQuality, onSelect: (VideoQuality) -> Unit) {
    Text(choice.title)
    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
        choice.options.forEach { quality ->
            FilterChip(selected = selected == quality, onClick = { onSelect(quality) },
                label = { Text(videoQualityLabel(quality)) })
        }
    }
}

/** Settings' sheet for how often followed series are checked for new chapters. */
@Composable
fun SeriesUpdatesSection(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    val choice = SettingChoices.SeriesUpdates
    Text(choice.title, style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
        choice.options.forEach { interval ->
            FilterChip(selected = choice.read(state.settings) == interval, onClick = { onAction(SettingsAction.SetChoice(choice, interval)) },
                label = { Text(seriesUpdateIntervalLabel(interval)) })
        }
    }
}

fun seriesUpdateIntervalLabel(interval: SeriesUpdateInterval): String = interval.hours?.let { "$it hours" } ?: "Off"
