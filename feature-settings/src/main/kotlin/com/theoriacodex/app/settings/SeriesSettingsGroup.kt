package com.theoriacodex.app.settings

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.data.repository.SeriesUpdateInterval
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SettingToggles

const val SERIES_SETTINGS_GROUP = "Series"

@Composable
internal fun SeriesSettingsGroup(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onOpenExtensions: (() -> Unit)?,
    onChooseInterval: () -> Unit,
) {
    SettingsGroup(SERIES_SETTINGS_GROUP) {
        if (onOpenExtensions != null) {
            SettingsRow(SettingDestinations.Extensions.title, icon = Icons.Default.Extension, onClick = onOpenExtensions)
            SettingsRowDivider()
        }
        SeriesUpdatesRow(state.settings.seriesUpdateInterval, onChooseInterval)
        SettingsRowDivider()
        val deleteAfterReading = SettingToggles.SeriesDeleteAfterReading
        SwitchRow(deleteAfterReading, state.settings) { onAction(SettingsAction.SetToggle(deleteAfterReading, it)) }
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
