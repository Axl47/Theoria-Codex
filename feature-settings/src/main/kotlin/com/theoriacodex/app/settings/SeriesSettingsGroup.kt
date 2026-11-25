package com.theoriacodex.app.settings

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.theoriacodex.app.ui.components.CountBadge
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.data.repository.SeriesUpdateInterval
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SettingToggles
import com.theoriacodex.domain.series.ExtensionAlerts

@Composable
internal fun SeriesSettingsGroup(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onChooseInterval: () -> Unit,
) {
    SettingsGroup("Series") {
        SeriesUpdatesRow(state.settings.seriesUpdateInterval, onChooseInterval)
        SettingsRowDivider()
        val deleteAfterReading = SettingToggles.SeriesDeleteAfterReading
        SwitchRow(deleteAfterReading, state.settings, icon = Icons.Default.AutoDelete) { onAction(SettingsAction.SetToggle(deleteAfterReading, it)) }
    }
}

@Composable
internal fun ExtensionsGroup(alerts: ExtensionAlerts, onOpen: () -> Unit) {
    SettingsGroup(null) {
        SettingsRow(
            SettingDestinations.Extensions.title,
            alerts.lines.joinToString("\n"),
            Icons.Default.Extension,
            onClick = onOpen,
            trailing = if (alerts.count > 0) {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CountBadge(alerts.count)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                null
            },
            summaryMaxLines = Int.MAX_VALUE,
        )
    }
}

@Composable
private fun SeriesUpdatesRow(interval: SeriesUpdateInterval, onChooseInterval: () -> Unit) {
    val silenced = notificationSettingsOpener() != null && interval.hours != null
    SettingsRow(
        SettingChoices.SeriesUpdates.title, if (silenced) NOTIFICATIONS_OFF else seriesUpdateIntervalLabel(interval), Icons.Default.Notifications,
        onClick = onChooseInterval,
    )
}

@Composable
internal fun notificationSettingsOpener(): (() -> Unit)? {
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    if (notificationsOn) return null
    return { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
}
