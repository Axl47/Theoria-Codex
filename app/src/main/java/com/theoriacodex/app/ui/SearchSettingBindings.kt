package com.theoriacodex.app.ui

import com.theoriacodex.app.media.videoQualityLabel
import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.OmniboxKind
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

/** What picking a setting definition in the search box does. */
internal sealed interface SettingBinding {
    /** A switch, flipped in place in the results. */
    data object Switch : SettingBinding

    /** Opens Settings at [page], with [sheet] over its home. */
    data class Settings(val page: SettingsPage, val sheet: SettingsSheet? = null) : SettingBinding

    data object HiddenTags : SettingBinding

    data object WhatsNew : SettingBinding

    /** Search offers this one itself, beside the sheet it opens. */
    data object InSearch : SettingBinding

    /** Set from the Viewer's playback menu, which needs a video open; the search box has no entry for it. */
    data object InViewer : SettingBinding
}

/** Every setting definition's id bound to what opens or changes it; a test checks none is missing. */
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
    put(SettingDestinations.Downloads.id, SettingBinding.Settings(SettingsPage.HOME, SettingsSheet.DOWNLOADS))
    put(SettingDestinations.Controls.id, SettingBinding.Settings(SettingsPage.HOME, SettingsSheet.CONTROLS))
    put(SettingDestinations.Storage.id, SettingBinding.Settings(SettingsPage.STORAGE))
    put(SettingDestinations.Backup.id, SettingBinding.Settings(SettingsPage.BACKUP))
    put(SettingDestinations.WhatsNew.id, SettingBinding.WhatsNew)
    put(SettingDestinations.Diagnostics.id, SettingBinding.Settings(SettingsPage.DIAGNOSTICS))
}

/**
 * The search box's entry for every setting definition as it stands in [settings]: toggles switch in place
 * through [dispatch], choices show their current value, and picking a choice or destination calls [open]
 * with its binding. [hidden] leaves out definitions this build cannot open (Diagnostics without a report, series checks without extensions).
 */
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

/** A choice's current value as the search box shows it; null for a value type it cannot label yet. */
internal fun settingValueLabel(value: Any?): String? = when (value) {
    is VideoQuality -> videoQualityLabel(value)
    is SeriesUpdateInterval -> seriesUpdateIntervalLabel(value)
    else -> null
}

/** Opens what [binding] names from the search box. */
internal fun TheoriaShell.openSettingBinding(binding: SettingBinding, openHiddenTags: () -> Unit) = when (binding) {
    is SettingBinding.Settings -> openSettings(binding.page, binding.sheet)
    SettingBinding.HiddenTags -> openHiddenTags()
    SettingBinding.WhatsNew -> owners.settings.onAction(SettingsAction.OpenChangelog)
    SettingBinding.Switch, SettingBinding.InSearch, SettingBinding.InViewer -> Unit
}
