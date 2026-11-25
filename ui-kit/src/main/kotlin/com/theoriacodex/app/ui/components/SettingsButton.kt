package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.series.ExtensionAlerts

@Composable
fun SettingsButton(alerts: ExtensionAlerts, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BadgedBox(badge = { if (alerts.count > 0) CountBadge(alerts.count, Modifier.offset(x = -BADGE_INSET, y = BADGE_DROP)) }) {
        IconButton(onClick = onClick, modifier = modifier.semantics { if (alerts.count > 0) stateDescription = alerts.lines.joinToString(", ") }) {
            Icon(Icons.Default.Settings, contentDescription = SETTINGS_LABEL)
        }
    }
}

const val SETTINGS_LABEL = "Settings"
private val BADGE_INSET = 16.dp
private val BADGE_DROP = 14.dp
