package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AsynchronousStore
import com.theoriacodex.data.storage.DurableStoreStatus
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.JsonDataStore
import com.theoriacodex.data.storage.openDecodedJsonDataStore
import com.theoriacodex.data.storage.StateCell
import com.theoriacodex.data.storage.openJsonDataStore
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DataStoreSettingsRepository private constructor(
    store: JsonDataStore<AppSettings>,
    profileWriteGuard: ProfileLibraryWriteGuard,
) : SettingsRepository by CellSettingsRepository(store, profileWriteGuard), AsynchronousStore by store {
    constructor(
        baseDirectory: File,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
        profileWriteGuard: ProfileLibraryWriteGuard = ProfileLibraryWriteGuard(),
    ) : this(
        openDecodedJsonDataStore(
            file = baseDirectory.resolve(DATASTORE_SETTINGS_FILE_NAME),
            logicalStore = "Settings",
            schemaVersion = SETTINGS_DATASTORE_SCHEMA_VERSION,
            defaultValue = SettingsDataStoreFile(),
            serializer = SettingsDataStoreFile.serializer(),
            scope = scope,
            validate = SettingsDataStoreFile::validate,
            recoveries = recoveryRegistry,
            decode = SettingsDataStoreFile::toDomain,
            encode = SettingsDataStoreFile::fromDomain,
        ),
        profileWriteGuard,
    )
}

/**
 * Settings over a [StateCell], shared by the DataStore store and the in-memory default. Every change is normalized,
 * admitted against the settings capacity, and bounded like the settings file ([normalizeDataStoreSettings]).
 */
internal class CellSettingsRepository(
    private val cell: StateCell<AppSettings>,
    private val profileWriteGuard: ProfileLibraryWriteGuard = ProfileLibraryWriteGuard(),
) : SettingsRepository {
    override fun observeSettings(): Flow<AppSettings> = cell.data.distinctUntilChanged()

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
    ): T = cell.updateReturning { current ->
        val result = policy(current)
        val admitted = RepositoryPolicies.normalizeSettings(result.state).requireSettingsCapacity()
        normalizeDataStoreSettings(admitted) to result.value
    }
}

class DataStoreUiRestoreRepository(
    baseDirectory: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
) : UiRestoreRepository, AsynchronousStore {
    private val storeFile = baseDirectory.resolve(DATASTORE_UI_RESTORE_FILE_NAME)
    private val dataStore: JsonDataStore<UiRestoreDataStoreFile> = openJsonDataStore(
        file = storeFile,
        logicalStore = "Last screen",
        schemaVersion = UI_RESTORE_DATASTORE_SCHEMA_VERSION,
        defaultValue = UiRestoreDataStoreFile(),
        serializer = UiRestoreDataStoreFile.serializer(),
        scope = scope,
        validate = UiRestoreDataStoreFile::validate,
        recoveries = recoveryRegistry,
    )

    override val storageStatus: StateFlow<DurableStoreStatus> = dataStore.storageStatus

    override suspend fun awaitReady() = dataStore.awaitReady()

    override suspend fun setLastTab(route: String) {
        mutateState { current -> current.copy(lastTab = route) }
    }

    override suspend fun getLastTab(): String? {
        return dataStore.data.first().toMemoryState().lastTab
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
        return dataStore.data.first().toMemoryState().scrollStates[queryHash.trim()]
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
        return dataStore.data.first().toMemoryState().feedFabRestoreStates
    }

    override fun observeViewerLaunchContext(): Flow<ViewerLaunchContext?> {
        return dataStore.data
            .map { stored -> stored.toMemoryState().viewerLaunchContext }
            .distinctUntilChanged()
    }

    override suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        mutateState { current -> current.copy(viewerLaunchContext = context) }
    }

    private suspend fun mutateState(
        transform: (PersistedUiRestoreState) -> PersistedUiRestoreState,
    ): PersistedUiRestoreState {
        val updated = dataStore.update { stored ->
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
}
