package com.theoriacodex.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theoriacodex.data.storage.AsynchronousStore
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.data.storage.DurableStorePhase
import com.theoriacodex.data.storage.DurableStoreStatus
import com.theoriacodex.data.storage.GsonDataStoreSerializer
import com.theoriacodex.data.storage.LegacyImportProof
import com.theoriacodex.data.storage.UnsupportedStoreSchemaException
import com.theoriacodex.data.storage.preserveCorruptFile
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    gson: Gson = Gson(),
) : SettingsRepository, AsynchronousStore {
    private val storeFile = baseDirectory.resolve(DATASTORE_SETTINGS_FILE_NAME)
    private val legacyFile = baseDirectory.resolve("settings_store.json")
    private val legacyArchiveFile = baseDirectory.resolve("settings_store.json.migrated-v3")
    private val replayLegacyAfterCorruption = AtomicBoolean(false)
    private val mutableStorageStatus = MutableStateFlow(DurableStoreStatus())
    private val defaultValue = SettingsDataStoreFile()
    private val dataStore: DataStore<SettingsDataStoreFile> = DataStoreFactory.create(
        serializer = GsonDataStoreSerializer(
            storeName = DATASTORE_SETTINGS_FILE_NAME,
            defaultValue = defaultValue,
            type = object : TypeToken<SettingsDataStoreFile>() {}.type,
            gson = gson,
            validate = { stored ->
                if (stored.schemaVersion > SETTINGS_DATASTORE_SCHEMA_VERSION) {
                    throw UnsupportedStoreSchemaException(
                        storeName = DATASTORE_SETTINGS_FILE_NAME,
                        actual = stored.schemaVersion,
                        supported = SETTINGS_DATASTORE_SCHEMA_VERSION,
                    )
                }
                require(stored.schemaVersion == SETTINGS_DATASTORE_SCHEMA_VERSION) {
                    "Unsupported settings schema ${stored.schemaVersion}"
                }
                stored.validate()
            },
        ),
        corruptionHandler = ReplaceFileCorruptionHandler { corruption ->
            replayLegacyAfterCorruption.set(true)
            mutableStorageStatus.update { current ->
                current.copy(
                    corruptionRecovery = CorruptionRecovery(
                        reason = corruption.message ?: "Settings store was corrupt",
                        backupPath = preserveCorruptFile(storeFile),
                    ),
                )
            }
            defaultValue
        },
        migrations = listOf(
            SettingsLegacyDataMigration(
                legacyFile = legacyFile,
                archiveFile = legacyArchiveFile,
                destinationExists = { storeFile.isFile },
                shouldReplayAfterCorruption = replayLegacyAfterCorruption::get,
                gson = gson,
                onImported = ::recordImports,
                onFailure = ::recordFailure,
            )
        ),
        scope = scope,
        produceFile = { storeFile },
    )
    private val records = dataStore.data
        .onEach(::recordReady)
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
            current.copy(runtime = current.runtime.copy(enabledSources = enabledSources))
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

    override suspend fun setScenarioPreset(preset: ScenarioPreset) {
        updateSettings { current -> current.copy(scenarioPreset = preset) }
    }

    @Deprecated("Last-tab state is owned by UiRestoreRepository; retain this writer only for compatibility.")
    override suspend fun setLastTab(route: String) {
        updateSettings { current -> current.copy(lastSelectedTabRoute = route) }
    }

    override suspend fun setActiveProfile(profileId: String) {
        updateSettings { current -> current.copy(activeProfileId = profileId) }
    }

    override suspend fun addRecommendationProfile(name: String): RecommendationProfile {
        val profileId = UUID.randomUUID().toString()
        return mutateSettings { current ->
            RepositoryPolicies.addRecommendationProfile(current, name, profileId)
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
            RepositoryPolicies.addBlacklistEntry(current, profileId, source, tags)
        }
    }

    override suspend fun removeForYouBlacklistEntry(
        profileId: String,
        source: SourceKey,
        tags: List<String>,
    ): Boolean {
        return mutateSettings { current ->
            RepositoryPolicies.removeBlacklistEntry(current, profileId, source, tags)
        }
    }

    override suspend fun addFavoriteTag(profileId: String, source: SourceKey, tag: String): Boolean {
        return mutateSettings { current ->
            RepositoryPolicies.addFavoriteTag(current, profileId, source, tag)
        }
    }

    override suspend fun removeFavoriteTag(profileId: String, source: SourceKey, tag: String): Boolean {
        return mutateSettings { current ->
            RepositoryPolicies.removeFavoriteTag(current, profileId, source, tag)
        }
    }

    override suspend fun setProviderHealthSnapshots(snapshots: List<ProviderHealthSnapshot>) {
        updateSettings { current -> RepositoryPolicies.mergeProviderHealth(current, snapshots) }
    }

    private suspend fun <T> mutateSettings(
        policy: (AppSettings) -> RepositoryPolicies.Result<AppSettings, T>,
    ): T {
        var result: MutationValue<T>? = null
        dataStore.updateData { stored ->
            val current = stored.toDomain()
            val policyResult = policy(current)
            val normalized = RepositoryPolicies.normalizeSettings(policyResult.state)
            result = MutationValue(policyResult.value)
            if (normalized == current) {
                stored
            } else {
                SettingsDataStoreFile.fromDomain(
                    settings = normalized,
                    legacyImports = stored.legacyImports,
                )
            }
        }
        return requireNotNull(result).value
    }

    private fun recordReady(stored: SettingsDataStoreFile) {
        mutableStorageStatus.update { current ->
            current.copy(
                phase = DurableStorePhase.READY,
                imports = stored.legacyImports,
                failureReason = null,
            )
        }
    }

    private fun recordImports(imports: List<LegacyImportProof>) {
        mutableStorageStatus.update { current -> current.copy(imports = imports) }
    }

    private fun recordFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        mutableStorageStatus.update { current ->
            current.copy(
                phase = DurableStorePhase.FAILED,
                failureReason = failure.message ?: failure::class.simpleName,
            )
        }
    }
}

class DataStoreUiRestoreRepository(
    baseDirectory: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    gson: Gson = Gson(),
) : UiRestoreRepository, AsynchronousStore {
    private val storeFile = baseDirectory.resolve(DATASTORE_UI_RESTORE_FILE_NAME)
    private val legacyUiFile = baseDirectory.resolve("ui_restore_store.json")
    private val legacyUiArchiveFile = baseDirectory.resolve("ui_restore_store.json.migrated-v2")
    private val legacySettingsFile = baseDirectory.resolve("settings_store.json")
    private val legacySettingsArchiveFile = baseDirectory.resolve("settings_store.json.migrated-v3")
    private val replayLegacyAfterCorruption = AtomicBoolean(false)
    private val mutableStorageStatus = MutableStateFlow(DurableStoreStatus())
    private val defaultValue = UiRestoreDataStoreFile()
    private val dataStore: DataStore<UiRestoreDataStoreFile> = DataStoreFactory.create(
        serializer = GsonDataStoreSerializer(
            storeName = DATASTORE_UI_RESTORE_FILE_NAME,
            defaultValue = defaultValue,
            type = object : TypeToken<UiRestoreDataStoreFile>() {}.type,
            gson = gson,
            validate = { stored ->
                if (stored.schemaVersion > UI_RESTORE_DATASTORE_SCHEMA_VERSION) {
                    throw UnsupportedStoreSchemaException(
                        storeName = DATASTORE_UI_RESTORE_FILE_NAME,
                        actual = stored.schemaVersion,
                        supported = UI_RESTORE_DATASTORE_SCHEMA_VERSION,
                    )
                }
                require(stored.schemaVersion == UI_RESTORE_DATASTORE_SCHEMA_VERSION) {
                    "Unsupported UI restore schema ${stored.schemaVersion}"
                }
                stored.validate()
            },
        ),
        corruptionHandler = ReplaceFileCorruptionHandler { corruption ->
            replayLegacyAfterCorruption.set(true)
            mutableStorageStatus.update { current ->
                current.copy(
                    corruptionRecovery = CorruptionRecovery(
                        reason = corruption.message ?: "UI restore store was corrupt",
                        backupPath = preserveCorruptFile(storeFile),
                    ),
                )
            }
            defaultValue
        },
        migrations = listOf(
            UiRestoreLegacyDataMigration(
                legacyUiFile = legacyUiFile,
                legacyUiArchiveFile = legacyUiArchiveFile,
                legacySettingsFile = legacySettingsFile,
                legacySettingsArchiveFile = legacySettingsArchiveFile,
                destinationExists = { storeFile.isFile },
                shouldReplayAfterCorruption = replayLegacyAfterCorruption::get,
                gson = gson,
                onImported = ::recordImports,
                onFailure = ::recordFailure,
            ),
            QueryScrollOffsetDataMigration(
                queryFile = baseDirectory.resolve("query_store.json"),
                gson = gson,
                onImported = ::recordImports,
                onFailure = ::recordFailure,
            ),
        ),
        scope = scope,
        produceFile = { storeFile },
    )
    private val records = dataStore.data
        .onEach(::recordReady)
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

    override suspend fun migrateLegacyLastTab(legacyRoute: String?): String? {
        return mutateState { current ->
            if (current.lastTab != null) return@mutateState current
            val migrated = legacyRoute?.trim()?.takeIf(String::isNotBlank)
                ?: return@mutateState current
            current.copy(lastTab = migrated)
        }.lastTab
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

    override suspend fun setSettingsSectionExpansion(expansion: Map<String, Boolean>) {
        mutateState { current -> current.copy(settingsSectionExpansion = expansion) }
    }

    override suspend fun getSettingsSectionExpansion(): Map<String, Boolean> {
        return records.first().toMemoryState().settingsSectionExpansion
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
        val updated = dataStore.updateData { stored ->
            val current = stored.toMemoryState()
            val next = normalizeUiRestoreState(transform(current))
            if (next == current) {
                stored
            } else {
                UiRestoreDataStoreFile.fromMemoryState(
                    state = next,
                    legacyImports = stored.legacyImports,
                )
            }
        }
        return updated.toMemoryState()
    }

    private fun recordReady(stored: UiRestoreDataStoreFile) {
        mutableStorageStatus.update { current ->
            current.copy(
                phase = DurableStorePhase.READY,
                imports = stored.legacyImports,
                failureReason = null,
            )
        }
    }

    private fun recordImports(imports: List<LegacyImportProof>) {
        mutableStorageStatus.update { current -> current.copy(imports = imports) }
    }

    private fun recordFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        mutableStorageStatus.update { current ->
            current.copy(
                phase = DurableStorePhase.FAILED,
                failureReason = failure.message ?: failure::class.simpleName,
            )
        }
    }
}

private data class MutationValue<T>(val value: T)
