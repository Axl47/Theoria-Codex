package com.theoriacodex.data.repository

import com.google.gson.annotations.SerializedName
import com.theoriacodex.data.storage.LegacyImportProof

const val DATASTORE_SETTINGS_FILE_NAME = "settings_store_v3.json"
const val DATASTORE_UI_RESTORE_FILE_NAME = "ui_restore_store_v2.json"
const val MAX_PERSISTED_SEARCH_SCROLL_STATES = 100
const val MAX_PERSISTED_RECOMMENDATION_PROFILES = 64
const val MAX_PERSISTED_FAVORITE_TAGS_PER_PROFILE = 500
const val MAX_PERSISTED_BLACKLIST_ENTRIES_PER_PROFILE = 250

internal const val SETTINGS_DATASTORE_SCHEMA_VERSION = 3
internal const val UI_RESTORE_DATASTORE_SCHEMA_VERSION = 2

internal data class SettingsDataStoreFile(
    @field:SerializedName("schemaVersion")
    val schemaVersion: Int = SETTINGS_DATASTORE_SCHEMA_VERSION,
    @field:SerializedName("settings")
    val settings: LegacySettingsStoreRecord = LegacySettingsStoreRecord.fromDomain(AppSettings()),
    @field:SerializedName("legacyImports")
    val legacyImports: List<LegacyImportProof> = emptyList(),
) {
    fun toDomain(): AppSettings = normalizeDataStoreSettings(settings.toDomain())

    fun validate() {
        require(schemaVersion > 0) { "Settings schema version must be positive" }
        legacyImports.forEach { proof ->
            require(proof.isValidFor(schemaVersion)) { "Settings import proof is invalid" }
        }
        runCatching { settings.toDomain() }.getOrElse { failure ->
            throw IllegalArgumentException("Settings payload is invalid", failure)
        }
    }

    companion object {
        fun fromDomain(
            settings: AppSettings,
            legacyImports: List<LegacyImportProof> = emptyList(),
        ): SettingsDataStoreFile {
            return SettingsDataStoreFile(
                settings = LegacySettingsStoreRecord.fromDomain(normalizeDataStoreSettings(settings)),
                legacyImports = legacyImports,
            )
        }
    }
}

internal fun normalizeDataStoreSettings(settings: AppSettings): AppSettings {
    val normalized = RepositoryPolicies.normalizeSettings(settings)
    val active = normalized.recommendationProfiles
        .firstOrNull { profile -> profile.profileId == normalized.activeProfileId }
    val profiles = if (normalized.recommendationProfiles.size <= MAX_PERSISTED_RECOMMENDATION_PROFILES) {
        normalized.recommendationProfiles
    } else {
        val firstProfiles = normalized.recommendationProfiles.take(MAX_PERSISTED_RECOMMENDATION_PROFILES)
        if (active == null || firstProfiles.any { profile -> profile.profileId == active.profileId }) {
            firstProfiles
        } else {
            firstProfiles.dropLast(1) + active
        }
    }
    val retainedProfileIds = profiles.mapTo(mutableSetOf()) { profile -> profile.profileId }
    return RepositoryPolicies.normalizeSettings(
        normalized.copy(
            recommendationProfiles = profiles,
            favoriteTagsByProfile = normalized.favoriteTagsByProfile
                .filterKeys { profileId -> profileId in retainedProfileIds }
                .mapValues { (_, entries) ->
                    entries.takeLast(MAX_PERSISTED_FAVORITE_TAGS_PER_PROFILE)
                },
            forYouBlacklistByProfile = normalized.forYouBlacklistByProfile
                .filterKeys { profileId -> profileId in retainedProfileIds }
                .mapValues { (_, entries) ->
                    entries.takeLast(MAX_PERSISTED_BLACKLIST_ENTRIES_PER_PROFILE)
                },
        )
    )
}

internal data class UiRestoreDataStoreFile(
    @field:SerializedName("schemaVersion")
    val schemaVersion: Int = UI_RESTORE_DATASTORE_SCHEMA_VERSION,
    @field:SerializedName("state")
    val state: LegacyUiRestoreStoreRecord = LegacyUiRestoreStoreRecord(),
    @field:SerializedName("legacyImports")
    val legacyImports: List<LegacyImportProof> = emptyList(),
) {
    fun toMemoryState(): PersistedUiRestoreState = normalizeUiRestoreState(state.toMemoryState())

    fun validate() {
        require(schemaVersion > 0) { "UI restore schema version must be positive" }
        legacyImports.forEach { proof ->
            require(proof.isValidFor(schemaVersion)) { "UI restore import proof is invalid" }
        }
        runCatching { state.toMemoryState() }.getOrElse { failure ->
            throw IllegalArgumentException("UI restore payload is invalid", failure)
        }
    }

    companion object {
        fun fromMemoryState(
            state: PersistedUiRestoreState,
            legacyImports: List<LegacyImportProof> = emptyList(),
        ): UiRestoreDataStoreFile {
            val normalized = normalizeUiRestoreState(state)
            return UiRestoreDataStoreFile(
                state = LegacyUiRestoreStoreRecord.fromMemoryState(normalized),
                legacyImports = legacyImports,
            )
        }
    }
}

internal fun normalizeUiRestoreState(state: PersistedUiRestoreState): PersistedUiRestoreState {
    val boundedScrollStates = LinkedHashMap<String, SearchScrollState>()
    state.scrollStates.forEach { (queryHash, scrollState) ->
        val normalizedHash = queryHash.trim()
        if (normalizedHash.isBlank()) return@forEach
        boundedScrollStates.remove(normalizedHash)
        boundedScrollStates[normalizedHash] = SearchScrollState(
            firstVisibleItemIndex = scrollState.firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemOffsetPx = scrollState.firstVisibleItemOffsetPx.coerceAtLeast(0),
        )
        while (boundedScrollStates.size > MAX_PERSISTED_SEARCH_SCROLL_STATES) {
            boundedScrollStates.remove(boundedScrollStates.keys.first())
        }
    }
    return PersistedUiRestoreState(
        lastTab = state.lastTab?.trim()?.takeIf(String::isNotBlank),
        scrollStates = boundedScrollStates,
        viewerLaunchContext = state.viewerLaunchContext,
    )
}
