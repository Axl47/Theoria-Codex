package com.theoriacodex.app.settings

import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.domain.model.SourceKey

enum class SettingsSectionKey {
    RECOMMENDATION_PROFILES,
    UNIFIED_MODE,
    FOR_YOU_BLACKLIST,
    SOURCE_ACCOUNTS,
    UPDATES,
    STORAGE_AND_CACHING,
    DEVELOPER_SCENARIOS,
}

data class SettingsSectionExpansionState(
    val expandedBySection: Map<SettingsSectionKey, Boolean> = SettingsSectionKey.entries
        .associateWith { false },
) {
    operator fun get(section: SettingsSectionKey): Boolean = expandedBySection[section] ?: false

    fun updated(section: SettingsSectionKey, expanded: Boolean): SettingsSectionExpansionState {
        return copy(expandedBySection = expandedBySection + (section to expanded))
    }

    fun toPersistenceMap(): Map<String, Boolean> = SettingsSectionKey.entries.associate { section ->
        section.name to this[section]
    }

    companion object {
        fun fromPersistenceMap(persisted: Map<String, Boolean>): SettingsSectionExpansionState {
            return SettingsSectionExpansionState(
                expandedBySection = SettingsSectionKey.entries.associateWith { section ->
                    persisted[section.name] ?: false
                },
            )
        }
    }
}

data class SettingsAccountUiState(
    val pixivStatusLabel: String = "Not connected",
    val pixivConnected: Boolean = false,
    val gelbooruUserIdInput: String = "",
    val gelbooruApiKeyInput: String = "",
    val gelbooruStatusLabel: String = "Not configured",
    val rule34XxxUserIdInput: String = "",
    val rule34XxxApiKeyInput: String = "",
    val rule34XxxStatusLabel: String = "Not configured",
    val mutationsEnabled: Boolean = false,
    val showRecoveryDialog: Boolean = false,
) {
    val pixivConnectEnabled: Boolean
        get() = mutationsEnabled && !pixivConnected &&
            !pixivStatusLabel.startsWith("Awaiting authorization callback")
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val activeProfile: RecommendationProfile = settings.recommendationProfiles.first(),
    val activeProfileLikesCount: Int = 0,
    val activeProfileBlacklist: List<ForYouBlacklistEntry> = emptyList(),
    val availableSources: List<SourceKey> = emptyList(),
    val cacheSnapshot: CacheSnapshot = CacheSnapshot(thumbnailCount = 0, fullImageCount = 0),
    val showDeveloperScenarios: Boolean = false,
    val sectionExpansion: SettingsSectionExpansionState = SettingsSectionExpansionState(),
    val accounts: SettingsAccountUiState = SettingsAccountUiState(),
    val profileDeleteTargetId: String? = null,
    val showClearCacheOptions: Boolean = false,
    val changelogLoading: Boolean = false,
    val legacyJsonRecoveries: List<CorruptionRecovery> = emptyList(),
)

sealed interface SettingsAction {
    data class SetSectionExpanded(val section: SettingsSectionKey, val expanded: Boolean) : SettingsAction
    data class SetActiveProfile(val profileId: String) : SettingsAction
    data class AddProfile(val name: String) : SettingsAction
    data class RequestRemoveProfile(val profileId: String) : SettingsAction
    data object DismissRemoveProfile : SettingsAction
    data object ConfirmRemoveProfile : SettingsAction
    data object ClearActiveProfileLikes : SettingsAction
    data class RemoveBlacklistEntry(val source: SourceKey, val tags: List<String>) : SettingsAction
    data class SetEnabledSources(val sources: Set<SourceKey>) : SettingsAction
    data class SetSourceWeights(val weights: Map<SourceKey, Double>) : SettingsAction
    data class SetCacheFullImageOnSave(val enabled: Boolean) : SettingsAction
    data class SetResolveUnknownAnimatedDurations(val enabled: Boolean) : SettingsAction
    data class SetScenarioPreset(val preset: ScenarioPreset) : SettingsAction
    data object ToggleClearCacheOptions : SettingsAction
    data object ClearThumbnailCache : SettingsAction
    data object ClearFullImageCache : SettingsAction
    data object OpenChangelog : SettingsAction
    data object ChangelogRequestFinished : SettingsAction
    data object ConnectPixiv : SettingsAction
    data object DisconnectPixiv : SettingsAction
    data class SetGelbooruUserId(val value: String) : SettingsAction
    data class SetGelbooruApiKey(val value: String) : SettingsAction
    data object SaveGelbooruCredentials : SettingsAction
    data object ClearGelbooruCredentials : SettingsAction
    data class SetRule34XxxUserId(val value: String) : SettingsAction
    data class SetRule34XxxApiKey(val value: String) : SettingsAction
    data object SaveRule34XxxCredentials : SettingsAction
    data object ClearRule34XxxCredentials : SettingsAction
    data object RefreshAccounts : SettingsAction
    data class PixivCallbackCompleted(val errorMessage: String? = null) : SettingsAction
    data object DismissCredentialRecovery : SettingsAction
    data object ResetCredentialStore : SettingsAction
    data object SettingsEntered : SettingsAction
}

sealed interface SettingsEffect {
    data class OpenExternalUri(val uri: String) : SettingsEffect
    data class ShowMessage(val message: String, val long: Boolean = false) : SettingsEffect
    data object LoadChangelog : SettingsEffect
    data object ThumbnailCacheCleared : SettingsEffect
    data object NavigateToSettings : SettingsEffect
}
