package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.media.videoQualityLabel
import com.theoriacodex.domain.model.VideoQuality

@Composable
internal fun MediaPreferencesSection(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    SettingsSection(
        title = "Playback & downloads",
        summary = "Video quality and network preferences",
        expanded = state.sectionExpansion[SettingsSectionKey.MEDIA],
        onToggle = { onAction(SettingsAction.SetSectionExpanded(SettingsSectionKey.MEDIA,
            !state.sectionExpansion[SettingsSectionKey.MEDIA])) },
    ) {
        QualityChoices("Default video quality", state.settings.viewer.videoQuality) {
            onAction(SettingsAction.SetVideoQuality(it))
        }
        QualityChoices("Download quality", state.settings.cache.downloadQuality) {
            onAction(SettingsAction.SetDownloadQuality(it))
        }
        NetworkChoice("Download over metered networks", state.settings.cache.downloadsOverMetered) {
            onAction(SettingsAction.SetDownloadsOverMetered(it))
        }
        NetworkChoice("Download while roaming", state.settings.cache.downloadsOverRoaming) {
            onAction(SettingsAction.SetDownloadsOverRoaming(it))
        }
    }
}

@Composable
private fun QualityChoices(label: String, selected: VideoQuality, onSelect: (VideoQuality) -> Unit) {
    Text(label)
    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
        VideoQuality.entries.forEach { quality ->
            FilterChip(selected = selected == quality, onClick = { onSelect(quality) },
                label = { Text(videoQualityLabel(quality)) })
        }
    }
}

@Composable
private fun NetworkChoice(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}
