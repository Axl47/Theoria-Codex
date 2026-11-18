package com.theoriacodex.data.repository

import androidx.datastore.core.DataStore
import com.theoriacodex.data.storage.AsynchronousStore
import com.theoriacodex.data.storage.DurableStorePhase
import com.theoriacodex.data.storage.DurableStoreStatus
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.openJsonDataStore
import com.theoriacodex.data.storage.updateDataInBackground
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class DataStoreSettingsRepository(
    baseDirectory: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
    private val profileWriteGuard: ProfileLibraryWriteGuard = ProfileLibraryWriteGuard(),
) : SettingsRepository, AsynchronousStore {
    private val storeFile = baseDirectory.resolve(DATASTORE_SETTINGS_FILE_NAME)
    private val mutableStorageStatus = MutableStateFlow(DurableStoreStatus())
    private val defaultValue = SettingsDataStoreFile()
    private val dataStore: DataStore<SettingsDataStoreFile> = openJsonDataStore(
        file = storeFile,
        logicalStore = "Settings",
        schemaVersion = SETTINGS_DATASTORE_SCHEMA_VERSION,
        defaultValue = defaultValue,
        serializer = SettingsDataStoreFile.serializer(),
        scope = scope,
        validate = SettingsDataStoreFile::validate,
        recoveries = recoveryRegistry,
    )
    private val records = dataStore.data
        .onEach { mutableStorageStatus.recordReady() }
        .catch { failure ->
            recordFailure(failure)
            throw failure
        }

    override val storageStatus: StateFlow<DurableStoreStatus> = mutableStorageStatus.asStateFlow()

    override suspend fun awaitReady() {
        records.first()
    }

    override fun observeSettings(): Flow<AppSettings> {
        return records.map(SettingsDataStoreFile::toDomain).distinctUntilChanged()
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        mutateSettings { current ->
            RepositoryPolicies.Result(state = transform(current), value = Unit)
        }
    }

    override suspend fun setEnabledSources(enabledSources: Set<SourceKey>) {
        updateSettings { current ->
            current.copy(runtime = SourceWeightSettings.selectSources(current.runtime, enabledSources))
        }
    }

    override suspend fun setSourceWeights(sourceWeights: Map<SourceKey, Double>) {
        updateSettings { current ->
            current.copy(runtime = current.runtime.copy(sourceWeights = sourceWeights))
        }
    }

    override suspend fun setCacheFullImageOnSave(enabled: Boolean) {
        updateSettings { current ->
            current.copy(cache = current.cache.copy(cacheFullImageOnSave = enabled))
        }
    }

    override suspend fun setResolveUnknownAnimatedDurations(enabled: Boolean) {
        updateSettings { current ->
            current.copy(
                contentFilters = current.contentFilters.copy(resolveUnknownAnimatedDurations = enabled),
            )
        }
    }

    override suspend fun setInvertMultiImageScrollDirection(enabled: Boolean) {
        updateSettings { current ->
            current.copy(
                viewer = current.viewer.copy(invertMultiImageScrollDirection = enabled),
            )
        }
    }

    override suspend fun setAutomaticTextTranslationEnabled(enabled: Boolean) {
        updateSettings { current ->
            current.copy(
                viewer = current.viewer.copy(automaticTextTranslationEnabled = enabled),
            )
        }
    }

    override suspend fun setOcrLanguageEnabled(language: ViewerOcrLanguage, enabled: Boolean) {
        updateSettings { current ->
            val updated = current.viewer.enabledOcrLanguages.toMutableSet()
            if (enabled) updated += language else updated -= language
            current.copy(viewer = current.viewer.copy(enabledOcrLanguages = updated))
        }
    }

    override suspend fun setScenarioPreset(preset: ScenarioPreset) {
        updateSettings { current -> current.copy(scenarioPreset = preset) }
    }

    override suspend fun setActiveProfile(profileId: String) {
        updateSettings { current ->
            profileWriteGuard.requireProfileWritable(profileId)
            current.copy(activeProfileId = profileId)
        }
    }

    override suspend fun addRecommendationProfile(name: String): RecommendationProfile {
        val profileId = UUID.randomUUID().toString()
        return mutateSettings { current ->
            RepositoryPolicies.addRecommendationProfile(current, name, profileId)
        }
    }

    override suspend fun renameRecommendationProfile(profileId: String, name: String): Boolean {
        return mutateSettings { current ->
            profileWriteGuard.requireProfileWritable(profileId)
            RepositoryPolicies.renameRecommendationProfile(current, profileId, name)
        }
    }

    override suspend fun removeRecommendationProfile(profileId: String): Boolean {
        return mutateSettings { current ->
            RepositoryPolicies.removeRecommendationProfile(current, profileId)
        }
    }

    override suspend fun addForYouBlacklistEntry(
        profileId: String,
        source: SourceKey,
        tags: List<String>,
    ): Boolean {
        return mutateSettings { current ->
            profileWriteGuard.requireProfileWritable(profileId)
            RepositoryPolicies.addBlacklistEntry(current, profileId, source, tags)
        }
    }

    override suspend fun removeForYouBlacklistEntry(
        profileId: String,
        source: SourceKey,
        tags: List<String>,
    ): Boolean {
        return mutateSettings { current ->
            profileWriteGuard.requireProfileWritable(profileId)
            RepositoryPolicies.removeBlacklistEntry(current, profileId, source, tags)
        }
    }

    override suspend fun addFavoriteTag(profileId: String, source: SourceKey, tags: List<String>, name: String?): Boolean {
        return mutateSettings { current ->
            profileWriteGuard.requireProfileWritable(profileId)
            RepositoryPolicies.addFavoriteTag(current, profileId, source, tags, name)
        }
    }

    override suspend fun removeFavoriteTag(profileId: String, source: SourceKey, tags: List<String>): Boolean {
        return mutateSettings { current ->
            profileWriteGuard.requireProfileWritable(profileId)
            RepositoryPolicies.removeFavoriteTag(current, profileId, source, tags)
        }
    }

    override suspend fun renameFavoriteTag(profileId: String, source: SourceKey, tags: List<String>, name: String?): Boolean {
        return mutateSettings { current ->
            profileWriteGuard.requireProfileWritable(profileId)
            RepositoryPolicies.renameFavoriteTag(current, profileId, source, tags, name)
        }
    }

    override suspend fun setProviderHealthSnapshots(snapshots: List<ProviderHealthSnapshot>) {
        updateSettings { current -> RepositoryPolicies.mergeProviderHealth(current, snapshots) }
    }

    private suspend fun <T> mutateSettings(
        policy: (AppSettings) -> RepositoryPolicies.Result<AppSettings, T>,
    ): T {
        var result: MutationValue<T>? = null
        dataStore.updateDataInBackground { stored ->
            val current = stored.toDomain()
            val policyResult = policy(current)
            val normalized = RepositoryPolicies.normalizeSettings(policyResult.state).requireSettingsCapacity()
            result = MutationValue(policyResult.value)
            if (normalized == current) {
                stored
            } else {
                SettingsDataStoreFile.fromDomain(normalized)
            }
        }
        return requireNotNull(result).value
    }

    private fun recordFailure(failure: Throwable) = mutableStorageStatus.recordFailure(failure)
}

class DataStoreUiRestoreRepository(
    baseDirectory: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
) : UiRestoreRepository, AsynchronousStore {
    private val storeFile = baseDirectory.resolve(DATASTORE_UI_RESTORE_FILE_NAME)
    private val mutableStorageStatus = MutableStateFlow(DurableStoreStatus())
    private val defaultValue = UiRestoreDataStoreFile()
    private val dataStore: DataStore<UiRestoreDataStoreFile> = openJsonDataStore(
        file = storeFile,
        logicalStore = "Last screen",
        schemaVersion = UI_RESTORE_DATASTORE_SCHEMA_VERSION,
        defaultValue = defaultValue,
        serializer = UiRestoreDataStoreFile.serializer(),
        scope = scope,
        validate = UiRestoreDataStoreFile::validate,
        recoveries = recoveryRegistry,
    )
    private val records = dataStore.data
        .onEach { mutableStorageStatus.recordReady() }
        .catch { failure ->
            recordFailure(failure)
            throw failure
        }

    override val storageStatus: StateFlow<DurableStoreStatus> = mutableStorageStatus.asStateFlow()

    override suspend fun awaitReady() {
        records.first()
    }

    override suspend fun setLastTab(route: String) {
        mutateState { current -> current.copy(lastTab = route) }
    }

    override suspend fun getLastTab(): String? {
        return records.first().toMemoryState().lastTab
    }

    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        val normalizedHash = queryHash.trim()
        if (normalizedHash.isBlank()) return
        mutateState { current ->
            val updated = LinkedHashMap(current.scrollStates)
            updated.remove(normalizedHash)
            updated[normalizedHash] = state
            current.copy(scrollStates = updated)
        }
    }

    override suspend fun getSearchScrollState(queryHash: String): SearchScrollState? {
        return records.first().toMemoryState().scrollStates[queryHash.trim()]
    }

    override suspend fun setFeedFabRestoreState(contextKey: String, state: FeedFabRestoreState) {
        val normalizedKey = contextKey.trim()
        if (normalizedKey.isBlank()) return
        mutateState { current ->
            val updated = LinkedHashMap(current.feedFabRestoreStates)
            updated.remove(normalizedKey)
            updated[normalizedKey] = state
            current.copy(feedFabRestoreStates = updated)
        }
    }

    override suspend fun getFeedFabRestoreStates(): Map<String, FeedFabRestoreState> {
        return records.first().toMemoryState().feedFabRestoreStates
    }

    override fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?> {
        return records
            .map { stored -> stored.toMemoryState().viewerLaunchContext }
            .distinctUntilChanged()
    }

    override suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        mutateState { current -> current.copy(viewerLaunchContext = context) }
    }

    private suspend fun mutateState(
        transform: (PersistedUiRestoreState) -> PersistedUiRestoreState,
    ): PersistedUiRestoreState {
        val updated = dataStore.updateDataInBackground { stored ->
            val current = stored.toMemoryState()
            val next = normalizeUiRestoreState(transform(current))
            if (next == current) {
                stored
            } else {
                UiRestoreDataStoreFile.fromMemoryState(next)
            }
        }
        return updated.toMemoryState()
    }

    private fun recordFailure(failure: Throwable) = mutableStorageStatus.recordFailure(failure)
}

private fun MutableStateFlow<DurableStoreStatus>.recordReady() = update { current ->
    current.copy(phase = DurableStorePhase.READY, failureReason = null)
}

private fun MutableStateFlow<DurableStoreStatus>.recordFailure(failure: Throwable) {
    if (failure is CancellationException) throw failure
    update { current ->
        current.copy(phase = DurableStorePhase.FAILED, failureReason = failure.message ?: failure::class.simpleName)
    }
}

private data class MutationValue<T>(val value: T)
