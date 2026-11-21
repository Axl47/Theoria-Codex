package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.SiteProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DATASTORE_SETTINGS_FILE_NAME = "settings_store_v3.json"
const val DATASTORE_UI_RESTORE_FILE_NAME = "ui_restore_store_v2.json"
const val MAX_PERSISTED_SEARCH_SCROLL_STATES = 100
const val MAX_PERSISTED_RECOMMENDATION_PROFILES = 64
const val MAX_PERSISTED_FAVORITE_TAGS_PER_PROFILE = 500
const val MAX_PERSISTED_BLACKLIST_ENTRIES_PER_PROFILE = 250

internal const val SETTINGS_DATASTORE_SCHEMA_VERSION = 3
internal const val UI_RESTORE_DATASTORE_SCHEMA_VERSION = 2

/** `settings_store_v3.json`, also read by developer profile copies. */
@Serializable
internal data class SettingsDataStoreFile(
    @SerialName("schemaVersion")
    val schemaVersion: Int = SETTINGS_DATASTORE_SCHEMA_VERSION,
    @SerialName("settings")
    val settings: SettingsStoreRecord = SettingsStoreRecord.fromDomain(AppSettings()),
) {
    fun toDomain(): AppSettings = normalizeDataStoreSettings(settings.toDomain())

    fun validate() {
        runCatching { settings.toDomain() }.getOrElse { failure ->
            throw IllegalArgumentException("Settings payload is invalid", failure)
        }
    }

    companion object {
        fun fromDomain(settings: AppSettings): SettingsDataStoreFile {
            return SettingsDataStoreFile(
                settings = SettingsStoreRecord.fromDomain(normalizeDataStoreSettings(settings)),
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
            followedCreators = normalized.followedCreators.distinctBy { it.creator.followKey() }.take(MAX_FOLLOWED_CREATORS),
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

@Serializable
internal data class UiRestoreDataStoreFile(
    @SerialName("schemaVersion")
    val schemaVersion: Int = UI_RESTORE_DATASTORE_SCHEMA_VERSION,
    @SerialName("state")
    val state: UiRestoreStoreRecord = UiRestoreStoreRecord(),
) {
    fun toMemoryState(): PersistedUiRestoreState = normalizeUiRestoreState(state.toMemoryState())

    fun validate() {
        runCatching { state.toMemoryState() }.getOrElse { failure ->
            throw IllegalArgumentException("UI restore payload is invalid", failure)
        }
    }

    companion object {
        fun fromMemoryState(state: PersistedUiRestoreState): UiRestoreDataStoreFile {
            val normalized = normalizeUiRestoreState(state)
            return UiRestoreDataStoreFile(
                state = UiRestoreStoreRecord.fromMemoryState(normalized),
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
    val boundedFabStates = LinkedHashMap<String, FeedFabRestoreState>()
    state.feedFabRestoreStates.forEach { (contextKey, restoreState) ->
        val normalizedKey = contextKey.trim()
        if (normalizedKey.isBlank()) return@forEach
        boundedFabStates.remove(normalizedKey)
        boundedFabStates[normalizedKey] = restoreState.copy(
            durationMinBucket = restoreState.durationMinBucket.coerceIn(0, 25),
            durationMaxBucket = restoreState.durationMaxBucket.coerceIn(0, 25),
            sortMode = restoreState.sortMode?.trim()?.takeIf(String::isNotBlank),
            source = restoreState.source?.trim()?.takeIf(String::isNotBlank),
            excludedSavedSearches = restoreState.excludedSavedSearches.filter(String::isNotBlank).distinct().take(MAX_SAVED_SEARCHES),
            followedSources = restoreState.followedSources.distinct().take(SourceKey.builtIns.size + SiteProfile.MAX_CUSTOM_SITES),
            followedAuthors = restoreState.followedAuthors.distinct().take(MAX_FOLLOWED_CREATORS),
            language = restoreState.language?.trim()?.takeIf(String::isNotBlank),
        )
        while (boundedFabStates.size > MAX_PERSISTED_FEED_FAB_STATES) {
            boundedFabStates.remove(boundedFabStates.keys.first())
        }
    }
    return PersistedUiRestoreState(
        lastTab = state.lastTab?.trim()?.takeIf(String::isNotBlank),
        librarySection = state.librarySection?.trim()?.takeIf(String::isNotBlank),
        scrollStates = boundedScrollStates,
        feedFabRestoreStates = boundedFabStates,
        viewerLaunchContext = state.viewerLaunchContext,
        savedSearchNewPosts = state.savedSearchNewPosts.copy(
            marks = state.savedSearchNewPosts.marks.entries.take(MAX_SAVED_SEARCHES).associate { it.key to it.value },
        ),
    )
}

private const val MAX_PERSISTED_FEED_FAB_STATES = 128
