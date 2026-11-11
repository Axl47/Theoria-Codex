package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PreferencesSection(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Controls", style = MaterialTheme.typography.titleMedium)
        PreferenceToggle("Old Search Bar", "Keep the search bar visible while scrolling.",
            state.settings.oldSearchBar) { onAction(SettingsAction.SetOldSearchBar(it)) }
        PreferenceToggle("Instant Seek", "Seek while dragging instead of showing thumbnail previews.",
            state.settings.viewer.instantSeek) { onAction(SettingsAction.SetInstantSeek(it)) }
        PreferenceToggle("Invert Scroll Direction", "Reverse swipes within multi-media posts.",
            state.settings.viewer.invertMultiImageScrollDirection) { onAction(SettingsAction.SetInvertScrollDirection(it)) }
        PreferenceToggle("Auto-Rotate Videos", "Rotate to fit the video you open.",
            state.settings.viewer.autoRotateVideos) { onAction(SettingsAction.SetAutoRotateVideos(it)) }
    }
}

@Composable
internal fun PreferenceToggle(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = onChange)
    }
}
