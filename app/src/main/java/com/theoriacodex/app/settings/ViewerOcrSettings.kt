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
            enabledLanguageCount = viewerSettings.enabledOcrLanguages.size,
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
                Text("Automatically detect text in Viewer")
                Text(
                    text = "Scans only the current static image in the background",
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
            text = "OCR models are shared device modules. Translation languages are managed " +
                "by the device's on-device translation service when supported.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onAction(SettingsAction.OpenTranslationSettings) }) {
            Text("Manage translation languages")
        }
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
            OcrLanguageModelState.NotDownloaded -> TextButton(
                onClick = { onAction(SettingsAction.DownloadOcrLanguage(language)) },
            ) {
                Text("Download")
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
            is OcrLanguageModelState.Unavailable -> Unit
        }
    }
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
    OcrLanguageModelState.NotDownloaded -> "Not downloaded"
    is OcrLanguageModelState.Downloading ->
        state.progressPercent?.let { "Downloading · $it%" } ?: "Downloading"
    OcrLanguageModelState.Ready -> "Available on device"
    is OcrLanguageModelState.Failed -> state.message
    is OcrLanguageModelState.Unavailable -> state.message
}
