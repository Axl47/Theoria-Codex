package com.theoriacodex.app.ui

import com.theoriacodex.app.media.videoQualityLabel
import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.OmniboxKind
import com.theoriacodex.app.series.openExtensions
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsPage
import com.theoriacodex.app.settings.SettingsSheet
import com.theoriacodex.app.settings.seriesUpdateIntervalLabel
import com.theoriacodex.data.repository.ALL_SETTING_DEFINITIONS
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.SeriesUpdateInterval
import com.theoriacodex.data.repository.SettingChoice
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SettingToggle
import com.theoriacodex.data.repository.SettingToggles
import com.theoriacodex.domain.model.VideoQuality

internal sealed interface SettingBinding {
    data object Switch : SettingBinding

    data class Settings(val page: SettingsPage, val sheet: SettingsSheet? = null) : SettingBinding

    data object HiddenTags : SettingBinding

    data object WhatsNew : SettingBinding

    data object Extensions : SettingBinding

    data object InSearch : SettingBinding

    data object InViewer : SettingBinding
}

internal val SETTING_BINDINGS: Map<String, SettingBinding> = buildMap {
    SettingToggles.all.forEach { put(it.id, SettingBinding.Switch) }
    put(SettingChoices.DefaultVideoQuality.id, SettingBinding.InViewer)
    put(SettingChoices.DownloadQuality.id, SettingBinding.Settings(SettingsPage.HOME, SettingsSheet.DOWNLOADS))
    put(SettingChoices.SeriesUpdates.id, SettingBinding.Settings(SettingsPage.HOME, SettingsSheet.SERIES_UPDATES))
    put(SettingDestinations.Home.id, SettingBinding.Settings(SettingsPage.HOME))
    put(SettingDestinations.Stats.id, SettingBinding.Settings(SettingsPage.STATS))
    put(SettingDestinations.ShareStats.id, SettingBinding.Settings(SettingsPage.STATS_SHARE))
    put(SettingDestinations.CompareStats.id, SettingBinding.Settings(SettingsPage.STATS_COMPARE))
    put(SettingDestinations.HiddenTags.id, SettingBinding.HiddenTags)
    put(SettingDestinations.SourceMix.id, SettingBinding.InSearch)
    put(SettingDestinations.Sites.id, SettingBinding.Settings(SettingsPage.SITES))
    put(SettingDestinations.Extensions.id, SettingBinding.Extensions)
    put(SettingDestinations.Downloads.id, SettingBinding.Settings(SettingsPage.HOME, SettingsSheet.DOWNLOADS))
    put(SettingDestinations.Controls.id, SettingBinding.Settings(SettingsPage.HOME, SettingsSheet.CONTROLS))
    put(SettingDestinations.PictureTags.id, SettingBinding.Settings(SettingsPage.PICTURE_TAGS))
    put(SettingDestinations.Storage.id, SettingBinding.Settings(SettingsPage.STORAGE))
    put(SettingDestinations.Backup.id, SettingBinding.Settings(SettingsPage.BACKUP))
    put(SettingDestinations.WhatsNew.id, SettingBinding.WhatsNew)
    put(SettingDestinations.Diagnostics.id, SettingBinding.Settings(SettingsPage.DIAGNOSTICS))
}

internal fun searchSettingEntries(
    settings: AppSettings,
    hidden: Set<String> = emptySet(),
    dispatch: (SettingsAction) -> Unit,
    open: (SettingBinding) -> Unit,
): List<OmniboxEntry> = ALL_SETTING_DEFINITIONS.filter { it.id !in hidden }.mapNotNull { definition ->
    val binding = SETTING_BINDINGS.getValue(definition.id)
    when {
        binding == SettingBinding.InSearch || binding == SettingBinding.InViewer -> null
        definition is SettingToggle -> OmniboxEntry.Toggle(definition.title, definition.read(settings), definition.keywords) { checked ->
            dispatch(SettingsAction.SetToggle(definition, checked))
        }
        else -> OmniboxEntry.Action(
            OmniboxKind.COMMAND, definition.title,
            detail = (definition as? SettingChoice<*>)?.let { settingValueLabel(it.read(settings)) },
            keywords = definition.keywords,
        ) { open(binding) }
    }
}

internal fun settingValueLabel(value: Any?): String? = when (value) {
    is VideoQuality -> videoQualityLabel(value)
    is SeriesUpdateInterval -> seriesUpdateIntervalLabel(value)
    else -> null
}

internal fun TheoriaShell.openSettingBinding(binding: SettingBinding, openHiddenTags: () -> Unit) = when (binding) {
    is SettingBinding.Settings -> openSettings(binding.page, binding.sheet)
    SettingBinding.HiddenTags -> openHiddenTags()
    SettingBinding.WhatsNew -> owners.settings.onAction(SettingsAction.OpenChangelog)
    SettingBinding.Extensions -> openExtensions()
    SettingBinding.Switch, SettingBinding.InSearch, SettingBinding.InViewer -> Unit
}
