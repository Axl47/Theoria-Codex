package com.theoriacodex.app.settings

import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.domain.model.SourceKey

/** Pages inside the Settings tab; held by the owner so Search can open Accounts directly. */
enum class SettingsPage { HOME, ACCOUNTS, HIDDEN_TAGS, STORAGE, STATS }

data class SettingsAccountUiState(
    val pixivStatusLabel: String = "Not connected",
    val pixivConnected: Boolean = false,
    val gelbooruUserIdInput: String = "",
    val gelbooruApiKeyInput: String = "",
    val gelbooruConfigured: Boolean = false,
    val gelbooruStatusLabel: String = "Not configured",
    val rule34XxxUserIdInput: String = "",
    val rule34XxxApiKeyInput: String = "",
    val rule34XxxConfigured: Boolean = false,
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
    val page: SettingsPage = SettingsPage.HOME,
    val accounts: SettingsAccountUiState = SettingsAccountUiState(),
    val profileDeleteTargetId: String? = null,
    val showClearCacheOptions: Boolean = false,
    val changelogLoading: Boolean = false,
    val legacyJsonRecoveries: List<CorruptionRecovery> = emptyList(),
)

sealed interface SettingsAction {
    data class OpenPage(val page: SettingsPage) : SettingsAction
    data class SetActiveProfile(val profileId: String) : SettingsAction
    data class AddProfile(val name: String) : SettingsAction
    data class RenameProfile(val profileId: String, val name: String) : SettingsAction
    data class RequestRemoveProfile(val profileId: String) : SettingsAction
    data object DismissRemoveProfile : SettingsAction
    data object ConfirmRemoveProfile : SettingsAction
    data class RemoveBlacklistEntry(val source: SourceKey, val tags: List<String>) : SettingsAction
    data class SetEnabledSources(val sources: Set<SourceKey>) : SettingsAction
    data class SetSourceWeights(val sources: Set<SourceKey>, val weights: Map<SourceKey, Double>) : SettingsAction
    data class SetInvertScrollDirection(val enabled: Boolean) : SettingsAction
    data class SetOldSearchBar(val enabled: Boolean) : SettingsAction
    data class SetInstantSeek(val enabled: Boolean) : SettingsAction
    data class SetViewerPlaybackMode(val mode: com.theoriacodex.domain.model.ViewerPlaybackMode) : SettingsAction
    data class SetVideoQuality(val quality: com.theoriacodex.domain.model.VideoQuality) : SettingsAction
    data class SetDownloadQuality(val quality: com.theoriacodex.domain.model.VideoQuality) : SettingsAction
    data class SetDownloadsOverMetered(val enabled: Boolean) : SettingsAction
    data class SetDownloadsOverRoaming(val enabled: Boolean) : SettingsAction
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
    data object OpenSourceAccounts : SettingsAction
    data object SettingsEntered : SettingsAction
}

sealed interface SettingsEffect {
    data class OpenExternalUri(val uri: String) : SettingsEffect
    data class ShowMessage(val message: String, val long: Boolean = false) : SettingsEffect
    data object LoadChangelog : SettingsEffect
    data object ThumbnailCacheCleared : SettingsEffect
    data object NavigateToSettings : SettingsEffect
}
