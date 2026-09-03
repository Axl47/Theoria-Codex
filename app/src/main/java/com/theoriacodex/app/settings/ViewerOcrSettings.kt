package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.viewer.ocr.OcrLanguageModelState
import com.theoriacodex.data.repository.ViewerOcrLanguage

@Composable
internal fun ViewerOcrSettingsSection(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    val viewerSettings = state.settings.viewer
    SettingsSection(
        title = "Viewer OCR & Translation",
        summary = viewerOcrSettingsSummary(
            enabled = viewerSettings.automaticTextTranslationEnabled,
            enabledLanguageCount = viewerSettings.enabledOcrLanguages.count { language ->
                state.ocrLanguageModels[language] == OcrLanguageModelState.Ready
            },
        ),
        expanded = state.sectionExpansion[SettingsSectionKey.VIEWER_OCR_TRANSLATION],
        onToggle = {
            onAction(
                SettingsAction.SetSectionExpanded(
                    SettingsSectionKey.VIEWER_OCR_TRANSLATION,
                    !state.sectionExpansion[SettingsSectionKey.VIEWER_OCR_TRANSLATION],
                ),
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Automatically detect and translate text")
                Text(
                    text = "Processes only the current static Viewer image",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewerSettings.automaticTextTranslationEnabled,
                onCheckedChange = {
                    onAction(SettingsAction.SetAutomaticTextTranslationEnabled(it))
                },
            )
        }

        ViewerOcrLanguage.entries.forEach { language ->
            ViewerOcrLanguageRow(
                language = language,
                modelState = state.ocrLanguageModels[language] ?: OcrLanguageModelState.Checking,
                enabled = language in viewerSettings.enabledOcrLanguages,
                onAction = onAction,
            )
        }

        Text(
            text = "OCR runs on this device. Detected phrases not already cached are sent with " +
                "their source language to translate.axor.dev in one background batch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ViewerOcrLanguageRow(
    language: ViewerOcrLanguage,
    modelState: OcrLanguageModelState,
    enabled: Boolean,
    onAction: (SettingsAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(language.displayLabel())
            Text(
                text = ocrLanguageModelStatusLabel(modelState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (modelState) {
            OcrLanguageModelState.Checking -> CircularProgressIndicator()
            OcrLanguageModelState.NotDownloaded -> Row(verticalAlignment = Alignment.CenterVertically) {
                DisableUnavailableLanguageSwitch(language, enabled, onAction)
                TextButton(onClick = { onAction(SettingsAction.DownloadOcrLanguage(language)) }) {
                    Text("Download OCR")
                }
            }
            is OcrLanguageModelState.Downloading -> {
                val progress = modelState.progressPercent
                if (progress == null) {
                    CircularProgressIndicator()
                } else {
                    CircularProgressIndicator(progress = { progress / 100f })
                }
            }
            OcrLanguageModelState.Ready -> Switch(
                checked = enabled,
                onCheckedChange = {
                    onAction(SettingsAction.SetOcrLanguageEnabled(language, it))
                },
            )
            is OcrLanguageModelState.Failed -> TextButton(
                onClick = { onAction(SettingsAction.DownloadOcrLanguage(language)) },
            ) {
                Text("Retry")
            }
            is OcrLanguageModelState.Unavailable ->
                DisableUnavailableLanguageSwitch(language, enabled, onAction)
        }
    }
}

@Composable
private fun DisableUnavailableLanguageSwitch(
    language: ViewerOcrLanguage,
    enabled: Boolean,
    onAction: (SettingsAction) -> Unit,
) {
    if (!enabled) return
    Switch(
        checked = true,
        onCheckedChange = { onAction(SettingsAction.SetOcrLanguageEnabled(language, false)) },
    )
}

internal fun viewerOcrSettingsSummary(enabled: Boolean, enabledLanguageCount: Int): String {
    val languageLabel = if (enabledLanguageCount == 1) "language" else "languages"
    return if (!enabled) "Off" else "On · $enabledLanguageCount $languageLabel enabled"
}

private fun ViewerOcrLanguage.displayLabel(): String = when (this) {
    ViewerOcrLanguage.JAPANESE -> "Japanese"
    ViewerOcrLanguage.CHINESE -> "Chinese"
    ViewerOcrLanguage.KOREAN -> "Korean"
}

internal fun ocrLanguageModelStatusLabel(state: OcrLanguageModelState): String = when (state) {
    OcrLanguageModelState.Checking -> "Checking availability"
    OcrLanguageModelState.NotDownloaded -> "OCR model needed"
    is OcrLanguageModelState.Downloading ->
        state.progressPercent?.let { "Downloading · $it%" } ?: "Downloading"
    OcrLanguageModelState.Ready -> "OCR available on device"
    is OcrLanguageModelState.Failed -> state.message
    is OcrLanguageModelState.Unavailable -> state.message
}
