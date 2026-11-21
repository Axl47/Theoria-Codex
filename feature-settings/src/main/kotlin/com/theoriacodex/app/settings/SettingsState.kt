package com.theoriacodex.app.settings

import androidx.compose.runtime.Composable
import com.theoriacodex.app.diagnostics.DiagnosticSection
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.data.repository.SettingChoice
import com.theoriacodex.data.repository.SettingToggle
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

/** Pages inside Settings; held by the owner so other screens can open Settings at a page. */
enum class SettingsPage { HOME, SITES, STORAGE, STATS, STATS_SHARE, STATS_COMPARE, DIAGNOSTICS }

/**
 * What Sites shows of the accounts Settings owns: a built-in source's status line (null when it has no
 * accounts) and its controls.
 */
class BuiltInAccounts(val summary: (SourceKey) -> String?, val controls: @Composable (SourceKey) -> Unit)

/**
 * Settings → Sites, supplied by the app shell so Settings stays independent of the sites owner.
 * [summary] is the home row's value; [page] renders the page and calls its `onBack` to return home.
 */
/** What the Stats page asks of the app shell: leaving Settings for the Viewer and exchanging files. */
class StatsActions(
    val openPost: suspend (PostId) -> Unit = {},
    val share: suspend (StatsShareFile) -> Unit = {},
    val readFile: suspend (document: String) -> StatsShareFile? = { null },
    /** A post for showing its thumbnail: the saved copy, or one looked up on its site without saving it. */
    val previewPost: suspend (PostId) -> Post? = { null },
    val download: suspend (StatsShareFile) -> Unit = {},
)

class SettingsDiagnosticsEntry(
    val build: suspend (Set<DiagnosticSection>) -> String,
    val share: suspend (String) -> Unit,
    val download: suspend (String) -> Unit,
    val clear: suspend () -> Unit,
)

class SettingsSitesEntry(val summary: String, val page: @Composable (onBack: () -> Unit, accounts: BuiltInAccounts) -> Unit)

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
    val recoveredStores: List<CorruptionRecovery> = emptyList(),
    /** Someone else's stats file while it is being compared; deliberately never written to storage. */
    val statsComparison: StatsShareFile? = null,
)

sealed interface SettingsAction {
    data class OpenPage(val page: SettingsPage) : SettingsAction
    data class SetStatsComparison(val file: StatsShareFile?) : SettingsAction
    data class SetActiveProfile(val profileId: String) : SettingsAction
    data class AddProfile(val name: String) : SettingsAction
    data class RenameProfile(val profileId: String, val name: String) : SettingsAction
    data class RequestRemoveProfile(val profileId: String) : SettingsAction
    data object DismissRemoveProfile : SettingsAction
    data object ConfirmRemoveProfile : SettingsAction
    data class RemoveBlacklistEntry(val source: SourceKey, val tags: List<String>) : SettingsAction
    data class SetEnabledSources(val sources: Set<SourceKey>) : SettingsAction
    data class SetSourceWeights(val sources: Set<SourceKey>, val weights: Map<SourceKey, Double>) : SettingsAction
    /** Writes one of [com.theoriacodex.data.repository.SettingToggles]; every switch in Settings and the search box sends this. */
    data class SetToggle(val toggle: SettingToggle, val enabled: Boolean) : SettingsAction

    /** Writes one of [com.theoriacodex.data.repository.SettingChoices]. */
    data class SetChoice<T>(val choice: SettingChoice<T>, val value: T) : SettingsAction {
        fun applyTo(settings: AppSettings): AppSettings = choice.write(settings, value)
    }
    data class SetCommentOrder(val order: com.theoriacodex.domain.model.CommentOrder) : SettingsAction
    data class SetViewerPlaybackMode(val mode: com.theoriacodex.domain.model.ViewerPlaybackMode) : SettingsAction
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

    /** Opens Settings → Sites from elsewhere in the app. */
    data object OpenCustomSites : SettingsAction
    data object SettingsEntered : SettingsAction
}

sealed interface SettingsEffect {
    data class OpenExternalUri(val uri: String) : SettingsEffect
    data class ShowMessage(val message: String, val long: Boolean = false) : SettingsEffect
    data object LoadChangelog : SettingsEffect
    data object ThumbnailCacheCleared : SettingsEffect
    data object NavigateToSettings : SettingsEffect
}
