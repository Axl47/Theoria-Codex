package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey

internal const val SHARED_SOURCE_CATALOG_VERSION = 2

/**
 * The legacy settings JSON schema shared by the atomic-file repository and the one-time
 * DataStore importer. Keeping this conversion in one place prevents migration behavior from
 * drifting away from the repository that originally wrote the file.
 */
internal data class LegacySettingsStoreRecord(
    val sourceCatalogVersion: Int? = null,
    val enabledSources: List<String> = SourceKey.entries.map { it.name },
    val sourceWeights: Map<String, Double> = SourceRuntimeSettings().sourceWeights.mapKeys { it.key.name },
    val cacheFullImageOnSave: Boolean = false,
    val resolveUnknownAnimatedDurations: Boolean = true,
    val invertMultiImageScrollDirection: Boolean = false,
    val scenarioPreset: String = ScenarioPreset.NORMAL.name,
    val lastSelectedTabRoute: String = "search",
    val recommendationProfiles: List<LegacyRecommendationProfileRecord>? = null,
    val activeProfileId: String? = null,
    val activeProfile: String? = null,
    val forYouBlacklistByProfile: Map<String, List<LegacyForYouBlacklistEntryRecord>>? = null,
    val favoriteTagsByProfile: Map<String, List<LegacyFavoriteTagEntryRecord>>? = null,
    val providerHealth: List<LegacyProviderHealthSnapshotRecord>? = null,
) {
    fun toDomain(): AppSettings {
        val storedEnabledSources = enabledSources
            .mapNotNull { runCatching { SourceKey.valueOf(it) }.getOrNull() }
            .toSet()
        val migratedEnabledSources = if (requiresSourceCatalogMigration()) {
            storedEnabledSources + SourceKey.HITOMI
        } else {
            storedEnabledSources
        }
        val runtime = SourceRuntimeSettings(
            enabledSources = migratedEnabledSources,
            sourceWeights = sourceWeights.mapNotNull { (key, value) ->
                runCatching { SourceKey.valueOf(key) }.getOrNull()?.let { source -> source to value }
            }.toMap(),
        )
        val profiles = recommendationProfiles
            ?.mapNotNull { it.toDomainOrNull() }
            .orEmpty()
            .ifEmpty { defaultRecommendationProfiles() }
        val resolvedActiveProfileId = activeProfileId
            ?.trim()
            ?.takeIf { value -> profiles.any { profile -> profile.profileId == value } }
            ?: parseLegacyProfileId(
                profileId = activeProfile,
                legacyProfile = activeProfile,
            ).takeIf { value -> profiles.any { profile -> profile.profileId == value } }
            ?: profiles.first().profileId
        return RepositoryPolicies.normalizeSettings(
            AppSettings(
                runtime = runtime,
                cache = CacheSettings(cacheFullImageOnSave = cacheFullImageOnSave),
                contentFilters = ContentFilterSettings(
                    resolveUnknownAnimatedDurations = resolveUnknownAnimatedDurations,
                ),
                viewer = ViewerSettings(
                    invertMultiImageScrollDirection = invertMultiImageScrollDirection,
                ),
                scenarioPreset = runCatching { ScenarioPreset.valueOf(scenarioPreset) }
                    .getOrDefault(ScenarioPreset.NORMAL),
                lastSelectedTabRoute = lastSelectedTabRoute,
                recommendationProfiles = profiles,
                activeProfileId = resolvedActiveProfileId,
                forYouBlacklistByProfile = forYouBlacklistByProfile
                    .orEmpty()
                    .mapValues { (_, entries) -> entries.mapNotNull { entry -> entry.toDomainOrNull() } },
                favoriteTagsByProfile = favoriteTagsByProfile
                    .orEmpty()
                    .mapValues { (_, entries) -> entries.mapNotNull { entry -> entry.toDomainOrNull() } },
                providerHealth = providerHealth
                    .orEmpty()
                    .mapNotNull { record ->
                        record.toDomainOrNull()?.let { snapshot -> snapshot.source to snapshot }
                    }
                    .toMap(),
            )
        )
    }

    fun requiresSourceCatalogMigration(): Boolean {
        return sourceSchemaVersion() < SHARED_SOURCE_CATALOG_VERSION
    }

    fun sourceSchemaVersion(): Int = sourceCatalogVersion ?: 1

    fun importCounts(): Map<String, Int> {
        return linkedMapOf(
            "settings" to 1,
            "profiles" to recommendationProfiles.orEmpty().size,
            "blacklistEntries" to forYouBlacklistByProfile.orEmpty().values.sumOf(List<*>::size),
            "favoriteTags" to favoriteTagsByProfile.orEmpty().values.sumOf(List<*>::size),
            "providerHealth" to providerHealth.orEmpty().size,
        )
    }

    companion object {
        fun fromDomain(settings: AppSettings): LegacySettingsStoreRecord {
            return LegacySettingsStoreRecord(
                sourceCatalogVersion = SHARED_SOURCE_CATALOG_VERSION,
                enabledSources = settings.runtime.enabledSources.map { it.name },
                sourceWeights = settings.runtime.sourceWeights.mapKeys { it.key.name },
                cacheFullImageOnSave = settings.cache.cacheFullImageOnSave,
                resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
                invertMultiImageScrollDirection = settings.viewer.invertMultiImageScrollDirection,
                scenarioPreset = settings.scenarioPreset.name,
                lastSelectedTabRoute = settings.lastSelectedTabRoute,
                recommendationProfiles = settings.recommendationProfiles.map(LegacyRecommendationProfileRecord::fromDomain),
                activeProfileId = settings.activeProfileId,
                activeProfile = null,
                forYouBlacklistByProfile = settings.forYouBlacklistByProfile.mapValues { (_, entries) ->
                    entries.map(LegacyForYouBlacklistEntryRecord::fromDomain)
                },
                favoriteTagsByProfile = settings.favoriteTagsByProfile.mapValues { (_, entries) ->
                    entries.map(LegacyFavoriteTagEntryRecord::fromDomain)
                },
                providerHealth = settings.providerHealth.values
                    .sortedBy { snapshot -> snapshot.source.name }
                    .map(LegacyProviderHealthSnapshotRecord::fromDomain),
            )
        }
    }
}

internal data class LegacyProviderHealthSnapshotRecord(
    val source: String? = null,
    val status: String? = null,
    val checkedAtEpochMs: Long? = null,
    val latencyMs: Long? = null,
    val failureReason: String? = null,
    val message: String? = null,
) {
    fun toDomainOrNull(): ProviderHealthSnapshot? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
            ?: return null
        val resolvedStatus = status
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> runCatching { ProviderHealthSnapshotStatus.valueOf(value) }.getOrNull() }
            ?: ProviderHealthSnapshotStatus.UNKNOWN
        return ProviderHealthSnapshot(
            source = resolvedSource,
            status = resolvedStatus,
            checkedAtEpochMs = checkedAtEpochMs ?: 0L,
            latencyMs = latencyMs,
            failureReason = failureReason?.takeIf(String::isNotBlank),
            message = message?.takeIf(String::isNotBlank),
        )
    }

    companion object {
        fun fromDomain(snapshot: ProviderHealthSnapshot): LegacyProviderHealthSnapshotRecord {
            return LegacyProviderHealthSnapshotRecord(
                source = snapshot.source.name,
                status = snapshot.status.name,
                checkedAtEpochMs = snapshot.checkedAtEpochMs,
                latencyMs = snapshot.latencyMs,
                failureReason = snapshot.failureReason,
                message = snapshot.message,
            )
        }
    }
}

internal data class LegacyFavoriteTagEntryRecord(
    val source: String? = null,
    val tag: String? = null,
) {
    fun toDomainOrNull(): FavoriteTagEntry? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
            ?: return null
        val normalizedTag = RepositoryPolicies.normalizeFavoriteTag(resolvedSource, tag.orEmpty())
        if (normalizedTag.isBlank()) return null
        return FavoriteTagEntry(source = resolvedSource, tag = normalizedTag)
    }

    companion object {
        fun fromDomain(entry: FavoriteTagEntry): LegacyFavoriteTagEntryRecord {
            return LegacyFavoriteTagEntryRecord(
                source = entry.source.name,
                tag = RepositoryPolicies.normalizeFavoriteTag(entry.source, entry.tag),
            )
        }
    }
}

internal data class LegacyForYouBlacklistEntryRecord(
    val source: String? = null,
    val tags: List<String>? = null,
) {
    fun toDomainOrNull(): ForYouBlacklistEntry? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
            ?: return null
        val normalizedTags = RepositoryPolicies.normalizeBlacklistTags(tags.orEmpty())
        if (normalizedTags.isEmpty()) return null
        return ForYouBlacklistEntry(source = resolvedSource, tags = normalizedTags)
    }

    companion object {
        fun fromDomain(entry: ForYouBlacklistEntry): LegacyForYouBlacklistEntryRecord {
            return LegacyForYouBlacklistEntryRecord(
                source = entry.source.name,
                tags = RepositoryPolicies.normalizeBlacklistTags(entry.tags),
            )
        }
    }
}

internal data class LegacyRecommendationProfileRecord(
    val profileId: String? = null,
    val name: String? = null,
) {
    fun toDomainOrNull(): RecommendationProfile? {
        val id = profileId?.trim().orEmpty()
        val profileName = name?.trim().orEmpty()
        if (id.isBlank() || profileName.isBlank()) return null
        return RecommendationProfile(profileId = id, name = profileName)
    }

    companion object {
        fun fromDomain(profile: RecommendationProfile): LegacyRecommendationProfileRecord {
            return LegacyRecommendationProfileRecord(profileId = profile.profileId, name = profile.name)
        }
    }
}

internal data class LegacyUiRestoreStoreRecord(
    val lastTab: String? = null,
    val searchScrollStates: Map<String, LegacySearchScrollStateRecord> = emptyMap(),
    val viewerLaunchContext: LegacyViewerLaunchContextRecord? = null,
) {
    fun toMemoryState(): PersistedUiRestoreState {
        return PersistedUiRestoreState(
            lastTab = lastTab?.trim()?.takeIf(String::isNotEmpty),
            scrollStates = searchScrollStates.mapNotNull { (queryHash, record) ->
                queryHash.trim().takeIf(String::isNotEmpty)?.let { normalizedHash ->
                    normalizedHash to record.toDomain()
                }
            }.toMap(),
            viewerLaunchContext = viewerLaunchContext?.toDomain(),
        )
    }

    fun importCounts(): Map<String, Int> {
        return linkedMapOf(
            "lastTab" to if (lastTab.isNullOrBlank()) 0 else 1,
            "searchScrollStates" to searchScrollStates.size,
            "viewerLaunchContext" to if (viewerLaunchContext == null) 0 else 1,
        )
    }

    companion object {
        fun fromMemoryState(state: PersistedUiRestoreState): LegacyUiRestoreStoreRecord {
            return LegacyUiRestoreStoreRecord(
                lastTab = state.lastTab,
                searchScrollStates = state.scrollStates.mapValues { (_, value) ->
                    LegacySearchScrollStateRecord.fromDomain(value)
                },
                viewerLaunchContext = state.viewerLaunchContext?.let(LegacyViewerLaunchContextRecord::fromDomain),
            )
        }
    }
}

internal data class PersistedUiRestoreState(
    val lastTab: String?,
    val scrollStates: Map<String, SearchScrollState>,
    val viewerLaunchContext: ViewerLaunchContext?,
)

internal data class LegacySearchScrollStateRecord(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemOffsetPx: Int,
) {
    fun toDomain(): SearchScrollState {
        return SearchScrollState(
            firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemOffsetPx = firstVisibleItemOffsetPx.coerceAtLeast(0),
        )
    }

    companion object {
        fun fromDomain(state: SearchScrollState): LegacySearchScrollStateRecord {
            return LegacySearchScrollStateRecord(
                firstVisibleItemIndex = state.firstVisibleItemIndex.coerceAtLeast(0),
                firstVisibleItemOffsetPx = state.firstVisibleItemOffsetPx.coerceAtLeast(0),
            )
        }
    }
}

internal data class LegacyViewerLaunchContextRecord(
    val queryHash: String,
    val startIndex: Int,
    val streamSource: String,
    val scrollOffsetHint: Int,
) {
    fun toDomain(): ViewerLaunchContext {
        return ViewerLaunchContext(
            queryHash = queryHash,
            startIndex = startIndex,
            streamSource = runCatching { ViewerStreamSource.valueOf(streamSource) }
                .getOrDefault(ViewerStreamSource.SEARCH),
            scrollOffsetHint = scrollOffsetHint,
        )
    }

    companion object {
        fun fromDomain(context: ViewerLaunchContext): LegacyViewerLaunchContextRecord {
            return LegacyViewerLaunchContextRecord(
                queryHash = context.queryHash,
                startIndex = context.startIndex,
                streamSource = context.streamSource.name,
                scrollOffsetHint = context.scrollOffsetHint,
            )
        }
    }
}

internal fun parseLegacyProfileId(
    profileId: String?,
    legacyProfile: String?,
): String {
    val normalizedProfileId = profileId?.trim().orEmpty()
    if (normalizedProfileId.isNotBlank()) {
        return when (normalizedProfileId) {
            "USER_1" -> "profile-main"
            "USER_2" -> "profile-alt"
            else -> normalizedProfileId
        }
    }

    return when (legacyProfile?.trim()) {
        "USER_1" -> "profile-main"
        "USER_2" -> "profile-alt"
        else -> "profile-main"
    }
}
