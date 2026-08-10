package com.theoriacodex.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.codex.LikesCodexSyncService
import com.theoriacodex.app.codex.PROFILE_CODEX_ID_PREFIX
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.source.inPresentationOrder
import com.theoriacodex.app.statistics.AppUsageTracker
import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.app.sourceauth.CredentialStoreUnavailableException
import com.theoriacodex.app.sourceauth.PixivPkceController
import com.theoriacodex.app.sourceauth.SourceAccountStore
import com.theoriacodex.app.sourceauth.parseGelbooruCredentialInput
import com.theoriacodex.app.sourceauth.parseRule34XxxCredentialInput
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class SettingsAccountSnapshot(
    val pixivStatusLabel: String,
    val pixivConnected: Boolean,
    val gelbooruUserId: String,
    val gelbooruConfigured: Boolean,
    val rule34XxxUserId: String,
    val rule34XxxConfigured: Boolean,
)

internal interface SettingsAccountGateway {
    val recoveryState: StateFlow<CredentialStoreRecoveryState>

    suspend fun loadSnapshot(): SettingsAccountSnapshot
    suspend fun startPixivAuthorization(): String
    suspend fun disconnectPixiv()
    suspend fun currentGelbooruApiKey(): String?
    suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials)
    suspend fun clearGelbooruCredentials()
    suspend fun currentRule34XxxApiKey(): String?
    suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials)
    suspend fun clearRule34XxxCredentials()
    suspend fun resetAfterReconnectRequired(): Boolean
}

internal class DefaultSettingsAccountGateway(
    private val accounts: SourceAccountStore,
    private val pixivAuthApi: PixivAuthApi,
    private val pixivAuthController: PixivPkceController,
    private val clock: () -> Long = System::currentTimeMillis,
) : SettingsAccountGateway {
    override val recoveryState: StateFlow<CredentialStoreRecoveryState> = accounts.recoveryState

    override suspend fun loadSnapshot(): SettingsAccountSnapshot {
        val pixivTokens = accounts.getPixivTokens()
        val pixivStatus = when {
            pixivTokens == null -> false to "Not connected"
            pixivTokens.expiresAtEpochMs > clock() -> true to "Connected"
            else -> refreshExpiredPixivSession(pixivTokens.refreshToken)
        }
        val gelbooru = accounts.getGelbooruCredentials()
        val rule34Xxx = accounts.getRule34XxxCredentials()
        val gelbooruPresentation = credentialAccountPresentation(
            credential = gelbooru,
            userId = GelbooruCredentials::userId,
        )
        val rule34XxxPresentation = credentialAccountPresentation(
            credential = rule34Xxx,
            userId = Rule34XxxCredentials::userId,
        )
        return SettingsAccountSnapshot(
            pixivStatusLabel = pixivStatus.second,
            pixivConnected = pixivStatus.first,
            gelbooruUserId = gelbooruPresentation.userIdInput,
            gelbooruConfigured = gelbooruPresentation.statusLabel == "Configured",
            rule34XxxUserId = rule34XxxPresentation.userIdInput,
            rule34XxxConfigured = rule34XxxPresentation.statusLabel == "Configured",
        )
    }

    private suspend fun refreshExpiredPixivSession(refreshToken: String): Pair<Boolean, String> {
        val refreshResult = withTimeoutOrNull(PIXIV_TOKEN_REFRESH_TIMEOUT_MS) {
            runCatchingPreservingCancellation { pixivAuthApi.refresh(refreshToken) }
        } ?: return false to "Connected (refresh timed out, retry on next request)"
        if (refreshResult.isSuccess) {
            val saved = runCatchingPreservingCancellation {
                accounts.savePixivTokens(requireNotNull(refreshResult.getOrNull()))
            }
            return if (saved.isSuccess) {
                true to "Connected"
            } else {
                false to recoveryMessage(accounts.recoveryState.value)
            }
        }
        val failure = refreshResult.exceptionOrNull()
        return if (
            failure is SourceAdapterException &&
            (failure.reason == SourceFailureReason.AUTH_EXPIRED ||
                failure.reason == SourceFailureReason.AUTH_REQUIRED)
        ) {
            val cleared = runCatchingPreservingCancellation { accounts.clearPixivTokens() }
            if (cleared.isSuccess) {
                false to "Not connected (session expired)"
            } else {
                false to recoveryMessage(accounts.recoveryState.value)
            }
        } else {
            false to "Connected (refresh failed, retry on next request)"
        }
    }

    override suspend fun startPixivAuthorization(): String = pixivAuthController.startAuthorizationUri().toString()
    override suspend fun disconnectPixiv() = accounts.clearPixivTokens()
    override suspend fun currentGelbooruApiKey(): String? = accounts.getGelbooruCredentials()?.apiKey
    override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) {
        accounts.saveGelbooruCredentials(credentials)
    }
    override suspend fun clearGelbooruCredentials() = accounts.clearGelbooruCredentials()
    override suspend fun currentRule34XxxApiKey(): String? = accounts.getRule34XxxCredentials()?.apiKey
    override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) {
        accounts.saveRule34XxxCredentials(credentials)
    }
    override suspend fun clearRule34XxxCredentials() = accounts.clearRule34XxxCredentials()
    override suspend fun resetAfterReconnectRequired(): Boolean = accounts.resetAfterReconnectRequired()
}

internal interface SettingsProfileMutations {
    suspend fun removeProfileData(profileId: String)
}

internal class DefaultSettingsProfileMutations(
    private val likesCodexSync: LikesCodexSyncService,
    private val codexRepository: CodexRepository,
) : SettingsProfileMutations {
    override suspend fun removeProfileData(profileId: String) {
        likesCodexSync.clearProfile(profileId)
        likesCodexSync.removeProfileCodex(profileId)
        codexRepository.observeCodices().first().filter { codex ->
            codex.codexId.startsWith("${PROFILE_CODEX_ID_PREFIX}_${profileId}_")
        }.forEach { codex -> codexRepository.deleteCodex(codex.codexId) }
    }
}

internal data class SettingsOwnerDependencies(
    val settingsRepository: SettingsRepository,
    val cacheRepository: CacheRepository,
    val uiRestoreRepository: UiRestoreRepository,
    val likesRepository: LikesRepository,
    val codexRepository: CodexRepository,
    val statisticsRepository: StatisticsRepository,
    val appUsageTracker: AppUsageTracker,
    val profileMutations: SettingsProfileMutations,
    val accounts: SettingsAccountGateway,
    val availableSources: StateFlow<Set<SourceKey>>,
    val legacyJsonRecoveries: StateFlow<List<CorruptionRecovery>> = MutableStateFlow(emptyList()),
    val showDeveloperScenarios: Boolean = false,
)

/** Activity-retained owner for Settings rendering, mutations, recovery, and durable expansion state. */
internal class SettingsViewModel(
    private val dependencies: SettingsOwnerDependencies,
    coroutineScope: CoroutineScope? = null,
) : ViewModel(), RouteStateOwner<SettingsUiState, SettingsAction, SettingsEffect> {
    private val ownerScope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(
        SettingsUiState(
            availableSources = dependencies.availableSources.value.inPresentationOrder(),
            showDeveloperScenarios = dependencies.showDeveloperScenarios,
        )
    )
    override val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private val effectChannel = Channel<SettingsEffect>(capacity = Channel.BUFFERED)
    override val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()
    private var likesJob: Job? = null
    private val accountRefreshRunning = AtomicBoolean(false)
    private var sectionExpansionRevision = 0L

    init {
        val restoreRevision = sectionExpansionRevision
        ownerScope.launch {
            val restored = try {
                dependencies.uiRestoreRepository.getSettingsSectionExpansion()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyMap()
            }
            if (sectionExpansionRevision == restoreRevision) {
                updateState {
                    copy(sectionExpansion = SettingsSectionExpansionState.fromPersistenceMap(restored))
                }
            }
        }
        ownerScope.launch {
            dependencies.availableSources.collect { sources ->
                updateState { copy(availableSources = sources.inPresentationOrder()) }
            }
        }
        ownerScope.launch {
            dependencies.settingsRepository.observeSettings().collect { settings ->
                val activeProfile = settings.recommendationProfiles
                    .firstOrNull { profile -> profile.profileId == settings.activeProfileId }
                    ?: settings.recommendationProfiles.first()
                updateState {
                    copy(
                        settings = settings,
                        activeProfile = activeProfile,
                        activeProfileBlacklist = settings.forYouBlacklistByProfile[activeProfile.profileId]
                            .orEmpty()
                            .sortedWith(
                                compareBy<com.theoriacodex.data.repository.ForYouBlacklistEntry> { it.source.name }
                                    .thenBy { it.tags.joinToString("+") },
                            ),
                    )
                }
                observeLikes(activeProfile.profileId)
            }
        }
        ownerScope.launch {
            dependencies.cacheRepository.observeSnapshot().collect { snapshot ->
                updateState { copy(cacheSnapshot = snapshot) }
            }
        }
        ownerScope.launch {
            SettingsStatisticsSource(
                statisticsRepository = dependencies.statisticsRepository,
                codexRepository = dependencies.codexRepository,
                appUsageTracker = dependencies.appUsageTracker,
            ).observe(
                dependencies.settingsRepository.observeSettings().map { settings -> settings.activeProfileId }
            ).collect { statistics ->
                updateState { copy(statistics = statistics) }
            }
        }
        ownerScope.launch {
            dependencies.legacyJsonRecoveries.collect { recoveries ->
                updateState { copy(legacyJsonRecoveries = recoveries) }
            }
        }
        ownerScope.launch {
            dependencies.accounts.recoveryState.collect { recovery ->
                if (recovery == CredentialStoreRecoveryState.Ready) {
                    refreshAccounts()
                } else {
                    applyRecoveryState(recovery)
                }
            }
        }
    }

    override fun onAction(action: SettingsAction) {
        if (handleProfileAction(action)) return
        if (handlePreferenceAction(action)) return
        if (handleStorageAndUpdateAction(action)) return
        handleAccountAction(action)
    }

    private fun handleProfileAction(action: SettingsAction): Boolean {
        when (action) {
            is SettingsAction.SetSectionExpanded -> setSectionExpanded(action.section, action.expanded)
            is SettingsAction.SetActiveProfile -> launchMutation {
                dependencies.settingsRepository.setActiveProfile(action.profileId)
            }
            is SettingsAction.AddProfile -> addProfile(action.name)
            is SettingsAction.RequestRemoveProfile -> updateState { copy(profileDeleteTargetId = action.profileId) }
            SettingsAction.DismissRemoveProfile -> updateState { copy(profileDeleteTargetId = null) }
            SettingsAction.ConfirmRemoveProfile -> removeRequestedProfile()
            is SettingsAction.RemoveBlacklistEntry -> launchMutation {
                dependencies.settingsRepository.removeForYouBlacklistEntry(
                    profileId = state.value.activeProfile.profileId,
                    source = action.source,
                    tags = action.tags,
                )
            }
            else -> return false
        }
        return true
    }

    private fun handlePreferenceAction(action: SettingsAction): Boolean {
        when (action) {
            is SettingsAction.SetEnabledSources -> launchMutation {
                dependencies.settingsRepository.setEnabledSources(
                    action.sources.intersect(dependencies.availableSources.value),
                )
            }
            is SettingsAction.SetSourceWeights -> launchMutation {
                dependencies.settingsRepository.setSourceWeights(action.weights)
            }
            is SettingsAction.SetCacheFullImageOnSave -> launchMutation {
                dependencies.settingsRepository.setCacheFullImageOnSave(action.enabled)
            }
            is SettingsAction.SetResolveUnknownAnimatedDurations -> launchMutation {
                dependencies.settingsRepository.setResolveUnknownAnimatedDurations(action.enabled)
            }
            is SettingsAction.SetScenarioPreset -> launchMutation {
                dependencies.settingsRepository.setScenarioPreset(action.preset)
            }
            else -> return false
        }
        return true
    }

    private fun handleStorageAndUpdateAction(action: SettingsAction): Boolean {
        when (action) {
            SettingsAction.ToggleClearCacheOptions -> updateState {
                copy(showClearCacheOptions = !showClearCacheOptions)
            }
            SettingsAction.ClearThumbnailCache -> launchMutation {
                dependencies.cacheRepository.clearThumbnailCache()
                updateState { copy(showClearCacheOptions = false) }
                effectChannel.send(SettingsEffect.ThumbnailCacheCleared)
            }
            SettingsAction.ClearFullImageCache -> launchMutation {
                dependencies.cacheRepository.clearFullImageCache()
                updateState { copy(showClearCacheOptions = false) }
            }
            SettingsAction.OpenChangelog -> {
                if (!state.value.changelogLoading) {
                    updateState { copy(changelogLoading = true) }
                    effectChannel.trySend(SettingsEffect.LoadChangelog)
                }
            }
            SettingsAction.ChangelogRequestFinished -> updateState { copy(changelogLoading = false) }
            else -> return false
        }
        return true
    }

    private fun handleAccountAction(action: SettingsAction) {
        when (action) {
            SettingsAction.ConnectPixiv -> connectPixiv()
            SettingsAction.DisconnectPixiv -> runCredentialMutation(dependencies.accounts::disconnectPixiv)
            is SettingsAction.SetGelbooruUserId -> updateAccounts {
                copy(gelbooruUserIdInput = action.value.trim())
            }
            is SettingsAction.SetGelbooruApiKey -> updateGelbooruApiKey(action.value)
            SettingsAction.SaveGelbooruCredentials -> saveGelbooruCredentials()
            SettingsAction.ClearGelbooruCredentials -> runCredentialMutation(
                dependencies.accounts::clearGelbooruCredentials,
            )
            is SettingsAction.SetRule34XxxUserId -> updateAccounts {
                copy(rule34XxxUserIdInput = action.value.trim())
            }
            is SettingsAction.SetRule34XxxApiKey -> updateRule34XxxApiKey(action.value)
            SettingsAction.SaveRule34XxxCredentials -> saveRule34XxxCredentials()
            SettingsAction.ClearRule34XxxCredentials -> runCredentialMutation(
                dependencies.accounts::clearRule34XxxCredentials,
            )
            SettingsAction.RefreshAccounts -> refreshAccounts()
            is SettingsAction.PixivCallbackCompleted -> handlePixivCallback(action.errorMessage)
            SettingsAction.DismissCredentialRecovery -> updateAccounts { copy(showRecoveryDialog = false) }
            SettingsAction.ResetCredentialStore -> resetCredentialStore()
            SettingsAction.SettingsEntered -> {
                updateAccounts { copy(gelbooruApiKeyInput = "", rule34XxxApiKeyInput = "") }
                refreshAccounts()
            }
            else -> error("Settings action was routed to the wrong owner handler")
        }
    }

    private fun setSectionExpanded(section: SettingsSectionKey, expanded: Boolean) {
        sectionExpansionRevision += 1
        val updated = state.value.sectionExpansion.updated(section, expanded)
        updateState { copy(sectionExpansion = updated) }
        ownerScope.launch {
            dependencies.uiRestoreRepository.setSettingsSectionExpansion(updated.toPersistenceMap())
        }
    }

    private fun addProfile(requestedName: String) {
        val name = requestedName.trim()
        if (name.isBlank()) return
        launchMutation { dependencies.settingsRepository.addRecommendationProfile(name) }
    }

    private fun removeRequestedProfile() {
        val profileId = state.value.profileDeleteTargetId ?: return
        updateState { copy(profileDeleteTargetId = null) }
        launchMutation {
            val canRemove = state.value.settings.recommendationProfiles.size > 1 &&
                state.value.settings.recommendationProfiles.any { it.profileId == profileId }
            if (!canRemove) return@launchMutation
            dependencies.profileMutations.removeProfileData(profileId)
            dependencies.settingsRepository.removeRecommendationProfile(profileId)
        }
    }

    private fun observeLikes(profileId: String) {
        likesJob?.cancel()
        likesJob = ownerScope.launch {
            dependencies.likesRepository.observeLikes(profileId).collect { likes ->
                updateState { copy(activeProfileLikesCount = likes.size) }
            }
        }
    }

    private fun connectPixiv() {
        if (blockCredentialMutationIfNeeded()) return
        ownerScope.launch {
            val authorization = runCatchingPreservingCancellation {
                dependencies.accounts.startPixivAuthorization()
            }
            authorization.onSuccess { uri ->
                updateAccounts {
                    copy(pixivStatusLabel = "Awaiting authorization callback...", pixivConnected = false)
                }
                effectChannel.send(SettingsEffect.OpenExternalUri(uri))
            }.onFailure {
                updateAccounts { copy(pixivStatusLabel = "Could not start Pixiv authorization — try again") }
            }
        }
    }

    private fun updateGelbooruApiKey(input: String) {
        val parsed = parseGelbooruCredentialInput(input)
        updateAccounts {
            if (parsed == null) {
                copy(gelbooruApiKeyInput = input.trim())
            } else {
                copy(gelbooruApiKeyInput = parsed.apiKey, gelbooruUserIdInput = parsed.userId)
            }
        }
    }

    private fun updateRule34XxxApiKey(input: String) {
        val parsed = parseRule34XxxCredentialInput(input)
        updateAccounts {
            if (parsed == null) {
                copy(rule34XxxApiKeyInput = input.trim())
            } else {
                copy(rule34XxxApiKeyInput = parsed.apiKey, rule34XxxUserIdInput = parsed.userId)
            }
        }
    }

    private fun saveGelbooruCredentials() {
        val inputs = state.value.accounts
        runCredentialMutationResult {
            val resolved = resolveReplaceOnlyCredential(
                userIdInput = inputs.gelbooruUserIdInput,
                replacementApiKeyInput = inputs.gelbooruApiKeyInput,
                configuredApiKey = inputs.gelbooruApiKeyInput.takeIf(String::isNotBlank)
                    ?: dependencies.accounts.currentGelbooruApiKey(),
            )
            if (resolved == null) {
                updateAccounts { copy(gelbooruStatusLabel = "Missing user ID or API key") }
                return@runCredentialMutationResult false
            }
            dependencies.accounts.saveGelbooruCredentials(GelbooruCredentials(resolved.userId, resolved.apiKey))
            true
        }
    }

    private fun saveRule34XxxCredentials() {
        val inputs = state.value.accounts
        runCredentialMutationResult {
            val resolved = resolveReplaceOnlyCredential(
                userIdInput = inputs.rule34XxxUserIdInput,
                replacementApiKeyInput = inputs.rule34XxxApiKeyInput,
                configuredApiKey = inputs.rule34XxxApiKeyInput.takeIf(String::isNotBlank)
                    ?: dependencies.accounts.currentRule34XxxApiKey(),
            )
            if (resolved == null) {
                updateAccounts { copy(rule34XxxStatusLabel = "Missing user ID or API key") }
                return@runCredentialMutationResult false
            }
            dependencies.accounts.saveRule34XxxCredentials(Rule34XxxCredentials(resolved.userId, resolved.apiKey))
            true
        }
    }

    private fun runCredentialMutation(mutation: suspend () -> Unit) {
        runCredentialMutationResult {
            mutation()
            true
        }
    }

    private fun runCredentialMutationResult(mutation: suspend () -> Boolean) {
        if (blockCredentialMutationIfNeeded()) return
        ownerScope.launch {
            val result = runCatchingPreservingCancellation { mutation() }
            val failure = result.exceptionOrNull()
            if (failure != null && failure !is CredentialStoreUnavailableException) throw failure
            if (result.getOrNull() == true || failure != null) {
                refreshAccountsNow()
            }
            if (failure != null && dependencies.accounts.recoveryState.value == CredentialStoreRecoveryState.Ready) {
                effectChannel.send(SettingsEffect.ShowMessage("Could not update source credentials — try again"))
            }
        }
    }

    private fun refreshAccounts() {
        if (!accountRefreshRunning.compareAndSet(false, true)) return
        ownerScope.launch {
            try {
                refreshAccountsNow()
            } finally {
                accountRefreshRunning.set(false)
            }
        }
    }

    private suspend fun refreshAccountsNow() {
        val recovery = dependencies.accounts.recoveryState.value
        if (recovery != CredentialStoreRecoveryState.Ready) {
            applyRecoveryState(recovery)
            return
        }
        val snapshot = try {
            dependencies.accounts.loadSnapshot()
        } catch (error: CredentialStoreUnavailableException) {
            applyRecoveryState(dependencies.accounts.recoveryState.value)
            return
        }
        val loadedRecovery = dependencies.accounts.recoveryState.value
        if (loadedRecovery != CredentialStoreRecoveryState.Ready) {
            applyRecoveryState(loadedRecovery)
            return
        }
        updateAccounts {
            copy(
                pixivStatusLabel = snapshot.pixivStatusLabel,
                pixivConnected = snapshot.pixivConnected,
                gelbooruUserIdInput = snapshot.gelbooruUserId,
                gelbooruApiKeyInput = "",
                gelbooruStatusLabel = if (snapshot.gelbooruConfigured) "Configured" else "Not configured",
                rule34XxxUserIdInput = snapshot.rule34XxxUserId,
                rule34XxxApiKeyInput = "",
                rule34XxxStatusLabel = if (snapshot.rule34XxxConfigured) "Configured" else "Not configured",
                mutationsEnabled = true,
                showRecoveryDialog = false,
            )
        }
    }

    private fun applyRecoveryState(recovery: CredentialStoreRecoveryState) {
        if (recovery == CredentialStoreRecoveryState.Ready) return
        val message = recoveryMessage(recovery)
        val clearUserIds = recovery == CredentialStoreRecoveryState.ReconnectRequired ||
            recovery is CredentialStoreRecoveryState.UnsupportedVersion
        updateAccounts {
            val gelbooruPresentation = credentialRecoveryPresentation(
                currentUserIdInput = gelbooruUserIdInput,
                statusLabel = message,
                clearUserId = clearUserIds,
            )
            val rule34XxxPresentation = credentialRecoveryPresentation(
                currentUserIdInput = rule34XxxUserIdInput,
                statusLabel = message,
                clearUserId = clearUserIds,
            )
            copy(
                pixivStatusLabel = message,
                pixivConnected = false,
                gelbooruUserIdInput = gelbooruPresentation.userIdInput,
                gelbooruApiKeyInput = gelbooruPresentation.apiKeyInput,
                gelbooruStatusLabel = gelbooruPresentation.statusLabel,
                rule34XxxUserIdInput = rule34XxxPresentation.userIdInput,
                rule34XxxApiKeyInput = rule34XxxPresentation.apiKeyInput,
                rule34XxxStatusLabel = rule34XxxPresentation.statusLabel,
                mutationsEnabled = false,
                showRecoveryDialog = recovery == CredentialStoreRecoveryState.ReconnectRequired,
            )
        }
    }

    private fun blockCredentialMutationIfNeeded(): Boolean {
        val recovery = dependencies.accounts.recoveryState.value
        if (recovery == CredentialStoreRecoveryState.Ready) return false
        applyRecoveryState(recovery)
        if (recovery != CredentialStoreRecoveryState.ReconnectRequired) {
            effectChannel.trySend(SettingsEffect.ShowMessage(recoveryMessage(recovery)))
        }
        return true
    }

    private fun handlePixivCallback(errorMessage: String?) {
        if (errorMessage == null) {
            updateAccounts { copy(pixivStatusLabel = "Connected") }
            refreshAccounts()
        } else {
            val recovery = dependencies.accounts.recoveryState.value
            if (recovery == CredentialStoreRecoveryState.Ready) {
                updateAccounts { copy(pixivStatusLabel = "Connection failed: $errorMessage") }
            } else {
                applyRecoveryState(recovery)
            }
        }
    }

    private fun resetCredentialStore() {
        updateAccounts { copy(showRecoveryDialog = false) }
        ownerScope.launch {
            val reset = dependencies.accounts.resetAfterReconnectRequired()
            refreshAccountsNow()
            if (reset) {
                effectChannel.send(SettingsEffect.NavigateToSettings)
                effectChannel.send(SettingsEffect.ShowMessage("Reconnect each source account in Settings", long = true))
            } else {
                updateAccounts { copy(showRecoveryDialog = true) }
                effectChannel.send(SettingsEffect.ShowMessage("Could not reset source credentials"))
            }
        }
    }

    private fun launchMutation(mutation: suspend () -> Unit) {
        ownerScope.launch { mutation() }
    }

    private inline fun updateState(transform: SettingsUiState.() -> SettingsUiState) {
        mutableState.value = mutableState.value.transform()
    }

    private inline fun updateAccounts(transform: SettingsAccountUiState.() -> SettingsAccountUiState) {
        updateState { copy(accounts = accounts.transform()) }
    }

    companion object {
        fun factory(container: TheoriaAppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    dependencies = SettingsOwnerDependencies(
                        settingsRepository = container.data.settingsRepository,
                        cacheRepository = container.data.cacheRepository,
                        uiRestoreRepository = container.data.uiRestoreRepository,
                        likesRepository = container.data.likesRepository,
                        codexRepository = container.data.codexRepository,
                        statisticsRepository = container.data.statisticsRepository,
                        appUsageTracker = container.features.appUsageTracker,
                        profileMutations = DefaultSettingsProfileMutations(
                            likesCodexSync = container.workflows.likesCodexSync,
                            codexRepository = container.data.codexRepository,
                        ),
                        accounts = DefaultSettingsAccountGateway(
                            accounts = container.sources.accounts,
                            pixivAuthApi = container.sources.pixivAuthApi,
                            pixivAuthController = container.sources.pixivAuthController,
                        ),
                        availableSources = container.sources.availableSources,
                        legacyJsonRecoveries = container.data.legacyJsonRecoveries,
                    )
                )
            }
        }
    }
}

internal fun recoveryMessage(state: CredentialStoreRecoveryState): String = when (state) {
    CredentialStoreRecoveryState.Loading -> "Loading source credentials…"
    CredentialStoreRecoveryState.Migrating -> "Finishing secure credential upgrade…"
    CredentialStoreRecoveryState.Ready -> "Source credentials are ready"
    CredentialStoreRecoveryState.TemporarilyUnavailable ->
        "Source credentials are temporarily unavailable — try again"
    CredentialStoreRecoveryState.ReconnectRequired -> "Source credentials need to be reconnected"
    is CredentialStoreRecoveryState.UnsupportedVersion -> "Stored credentials require a newer app version"
}

private const val PIXIV_TOKEN_REFRESH_TIMEOUT_MS = 6_000L
