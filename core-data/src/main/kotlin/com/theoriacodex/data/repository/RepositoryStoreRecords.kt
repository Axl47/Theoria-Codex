package com.theoriacodex.data.repository

import com.google.gson.annotations.SerializedName
import com.theoriacodex.domain.model.SourceKey

internal const val SHARED_SOURCE_CATALOG_VERSION = 3

/** Sources each catalog version introduced; older stored selections gain them once on read. */
private val SOURCES_ADDED_BY_CATALOG_VERSION = mapOf(
    2 to setOf(SourceKey.HITOMI),
    3 to setOf(SourceKey.DANBOORU, SourceKey.AIBOORU),
)

/**
 * The `settings` object of `settings_store_v3.json`, also the settings section of profile backups. Enum-like
 * values stay strings so a value this build does not know falls back instead of failing the whole file.
 */
internal data class SettingsStoreRecord(
    @field:SerializedName("followedCreators")
    val followedCreators: List<FollowedCreatorRecord>? = null,
    @field:SerializedName("sourceCatalogVersion")
    val sourceCatalogVersion: Int? = null,
    @field:SerializedName("enabledSources")
    val enabledSources: List<String> = SourceKey.builtIns.map { it.id },
    @field:SerializedName("sourceWeights")
    val sourceWeights: Map<String, Double> = SourceRuntimeSettings().sourceWeights.mapKeys { it.key.id },
    @field:SerializedName("sourceWeightsByConfiguration")
    val sourceWeightsByConfiguration: Map<String, Map<String, Double>>? = null,
    @field:SerializedName("viewerPlaybackMode") val viewerPlaybackMode: String? = null,
    @field:SerializedName("videoQuality") val videoQuality: String? = null,
    @field:SerializedName("downloadQuality") val downloadQuality: String? = null,
    @field:SerializedName("downloadsOverMetered") val downloadsOverMetered: Boolean? = null,
    @field:SerializedName("downloadsOverRoaming") val downloadsOverRoaming: Boolean? = null,
    @field:SerializedName("cacheFullImageOnSave")
    val cacheFullImageOnSave: Boolean = false,
    @field:SerializedName("resolveUnknownAnimatedDurations")
    val resolveUnknownAnimatedDurations: Boolean = false,
    @field:SerializedName("invertMultiImageScrollDirection")
    val invertMultiImageScrollDirection: Boolean = false,
    @field:SerializedName("oldSearchBar") val oldSearchBar: Boolean = false,
    @field:SerializedName("codexLibrarySort") val codexLibrarySort: String? = null,
    @field:SerializedName("instantSeek") val instantSeek: Boolean = false,
    @field:SerializedName("viewerAutoRotateVideos") val viewerAutoRotateVideos: Boolean? = null,
    @field:SerializedName("viewerCommentOrder") val viewerCommentOrder: String? = null,
    @field:SerializedName("viewerAutomaticTextTranslationEnabled")
    val viewerAutomaticTextTranslationEnabled: Boolean = false,
    @field:SerializedName("viewerEnabledOcrLanguages")
    val viewerEnabledOcrLanguages: List<String> = emptyList(),
    @field:SerializedName("scenarioPreset")
    val scenarioPreset: String = ScenarioPreset.NORMAL.name,
    @field:SerializedName("lastSelectedTabRoute")
    val lastSelectedTabRoute: String = "search",
    @field:SerializedName("recommendationProfiles")
    val recommendationProfiles: List<RecommendationProfileRecord>? = null,
    @field:SerializedName("activeProfileId")
    val activeProfileId: String? = null,
    @field:SerializedName("activeProfile")
    val activeProfile: String? = null,
    @field:SerializedName("forYouBlacklistByProfile")
    val forYouBlacklistByProfile: Map<String, List<ForYouBlacklistEntryRecord>>? = null,
    @field:SerializedName("favoriteTagsByProfile")
    val favoriteTagsByProfile: Map<String, List<FavoriteTagEntryRecord>>? = null,
    @field:SerializedName("providerHealth")
    val providerHealth: List<ProviderHealthSnapshotRecord>? = null,
) {
    fun toDomain(): AppSettings {
        val storedEnabledSources = enabledSources
            .mapNotNull { SourceKey.parse(it) }
            .toSet()
        val migratedEnabledSources = storedEnabledSources + SOURCES_ADDED_BY_CATALOG_VERSION
            .filterKeys { version -> version > sourceSchemaVersion() }
            .values.flatten()
        val runtime = SourceRuntimeSettings(
            enabledSources = migratedEnabledSources,
            sourceWeights = sourceWeights.mapNotNull { (key, value) ->
                SourceKey.parse(key)?.let { source -> source to value }
            }.toMap(),
            sourceWeightsByConfiguration = decodeWeightConfigurations(),
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
                oldSearchBar = oldSearchBar,
                codexLibrarySort = CodexLibrarySort.entries.firstOrNull { it.name == codexLibrarySort }
                    ?: CodexLibrarySort.MANUAL,
                followedCreators = followedCreators.orEmpty().mapNotNull { it.toDomain() }.distinctBy { it.creator.followKey() }.take(MAX_FOLLOWED_CREATORS),
                runtime = runtime,
                cache = toCacheSettings(),
                contentFilters = ContentFilterSettings(
                    resolveUnknownAnimatedDurations = resolveUnknownAnimatedDurations,
                ),
                viewer = toViewerSettings(),
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

    private fun decodeWeightConfigurations(): Map<String, Map<SourceKey, Double>> =
        sourceWeightsByConfiguration.orEmpty().mapValues { (_, weights) ->
            weights.mapNotNull { (name, weight) ->
                SourceKey.parse(name)?.let { it to weight }
            }.toMap()
        }

    private fun toCacheSettings() = CacheSettings(
        cacheFullImageOnSave = cacheFullImageOnSave,
        downloadQuality = decodeVideoQuality(downloadQuality, com.theoriacodex.domain.model.VideoQuality.BEST),
        downloadsOverMetered = downloadsOverMetered ?: true,
        downloadsOverRoaming = downloadsOverRoaming ?: false,
    )

    private fun toViewerSettings(): ViewerSettings {
        return ViewerSettings(
            playbackMode = com.theoriacodex.domain.model.ViewerPlaybackMode.entries.firstOrNull {
                it.name == viewerPlaybackMode
            } ?: com.theoriacodex.domain.model.ViewerPlaybackMode.LOOP,
            videoQuality = decodeVideoQuality(videoQuality, com.theoriacodex.domain.model.VideoQuality.AUTO),
            invertMultiImageScrollDirection = invertMultiImageScrollDirection,
            instantSeek = instantSeek,
            autoRotateVideos = viewerAutoRotateVideos ?: true,
            commentOrder = com.theoriacodex.domain.model.CommentOrder.entries.firstOrNull { it.name == viewerCommentOrder }
                ?: com.theoriacodex.domain.model.CommentOrder.OLDEST,
            automaticTextTranslationEnabled = viewerAutomaticTextTranslationEnabled,
            enabledOcrLanguages = viewerEnabledOcrLanguages.mapNotNullTo(linkedSetOf()) { name ->
                runCatching { ViewerOcrLanguage.valueOf(name) }.getOrNull()
            },
        )
    }

    fun sourceSchemaVersion(): Int = sourceCatalogVersion ?: 1

    companion object {
        fun fromDomain(settings: AppSettings): SettingsStoreRecord {
            return SettingsStoreRecord(
                followedCreators = settings.followedCreators.map(FollowedCreatorRecord::fromDomain),
                sourceCatalogVersion = SHARED_SOURCE_CATALOG_VERSION,
                oldSearchBar = settings.oldSearchBar,
                codexLibrarySort = settings.codexLibrarySort.name,
                enabledSources = settings.runtime.enabledSources.map { it.id },
                sourceWeights = settings.runtime.sourceWeights.mapKeys { it.key.id },
                sourceWeightsByConfiguration = settings.runtime.sourceWeightsByConfiguration.mapValues { (_, weights) ->
                    weights.mapKeys { it.key.id }
                },
                cacheFullImageOnSave = settings.cache.cacheFullImageOnSave,
                viewerPlaybackMode = settings.viewer.playbackMode.name,
                videoQuality = settings.viewer.videoQuality.name,
                downloadQuality = settings.cache.downloadQuality.name,
                downloadsOverMetered = settings.cache.downloadsOverMetered,
                downloadsOverRoaming = settings.cache.downloadsOverRoaming,
                resolveUnknownAnimatedDurations = settings.contentFilters.resolveUnknownAnimatedDurations,
                invertMultiImageScrollDirection = settings.viewer.invertMultiImageScrollDirection,
                instantSeek = settings.viewer.instantSeek,
                viewerAutoRotateVideos = settings.viewer.autoRotateVideos,
                viewerCommentOrder = settings.viewer.commentOrder.name,
                viewerAutomaticTextTranslationEnabled = settings.viewer.automaticTextTranslationEnabled,
                viewerEnabledOcrLanguages = settings.viewer.enabledOcrLanguages
                    .sortedBy(ViewerOcrLanguage::ordinal)
                    .map(ViewerOcrLanguage::name),
                scenarioPreset = settings.scenarioPreset.name,
                lastSelectedTabRoute = settings.lastSelectedTabRoute,
                recommendationProfiles = settings.recommendationProfiles.map(RecommendationProfileRecord::fromDomain),
                activeProfileId = settings.activeProfileId,
                activeProfile = null,
                forYouBlacklistByProfile = settings.forYouBlacklistByProfile.mapValues { (_, entries) ->
                    entries.map(ForYouBlacklistEntryRecord::fromDomain)
                },
                favoriteTagsByProfile = settings.favoriteTagsByProfile.mapValues { (_, entries) ->
                    entries.map(FavoriteTagEntryRecord::fromDomain)
                },
                providerHealth = settings.providerHealth.values
                    .sortedBy { snapshot -> snapshot.source.id }
                    .map(ProviderHealthSnapshotRecord::fromDomain),
            )
        }
    }
}

internal data class ProviderHealthSnapshotRecord(
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("status")
    val status: String? = null,
    @field:SerializedName("checkedAtEpochMs")
    val checkedAtEpochMs: Long? = null,
    @field:SerializedName("latencyMs")
    val latencyMs: Long? = null,
    @field:SerializedName("failureReason")
    val failureReason: String? = null,
    @field:SerializedName("message")
    val message: String? = null,
) {
    fun toDomainOrNull(): ProviderHealthSnapshot? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> SourceKey.parse(value) }
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
        fun fromDomain(snapshot: ProviderHealthSnapshot): ProviderHealthSnapshotRecord {
            return ProviderHealthSnapshotRecord(
                source = snapshot.source.id,
                status = snapshot.status.name,
                checkedAtEpochMs = snapshot.checkedAtEpochMs,
                latencyMs = snapshot.latencyMs,
                failureReason = snapshot.failureReason,
                message = snapshot.message,
            )
        }
    }
}

/**
 * A saved tag keeps its first tag in [tag], so builds that predate "or" groups still read one.
 * [tags] is written only for a group and, when present, holds every alternative; [name] is a
 * group's optional label.
 */
internal data class FavoriteTagEntryRecord(
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("tag")
    val tag: String? = null,
    @field:SerializedName("tags")
    val tags: List<String>? = null,
    @field:SerializedName("name")
    val name: String? = null,
) {
    fun toDomainOrNull(): FavoriteTagEntry? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> SourceKey.parse(value) }
            ?: return null
        return RepositoryPolicies.normalizeFavoriteTagEntry(resolvedSource, tags ?: listOfNotNull(tag), name)
    }

    companion object {
        fun fromDomain(entry: FavoriteTagEntry): FavoriteTagEntryRecord {
            val normalized = entry.tags.map { tag -> RepositoryPolicies.normalizeFavoriteTag(entry.source, tag) }
            return FavoriteTagEntryRecord(
                source = entry.source.id,
                tag = normalized.first(),
                tags = normalized.takeIf { it.size > 1 },
                name = entry.name,
            )
        }
    }
}

internal data class ForYouBlacklistEntryRecord(
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("tags")
    val tags: List<String>? = null,
) {
    fun toDomainOrNull(): ForYouBlacklistEntry? {
        val resolvedSource = source
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { value -> SourceKey.parse(value) }
            ?: return null
        val normalizedTags = RepositoryPolicies.normalizeBlacklistTags(tags.orEmpty())
        if (normalizedTags.isEmpty()) return null
        return ForYouBlacklistEntry(source = resolvedSource, tags = normalizedTags)
    }

    companion object {
        fun fromDomain(entry: ForYouBlacklistEntry): ForYouBlacklistEntryRecord {
            return ForYouBlacklistEntryRecord(
                source = entry.source.id,
                tags = RepositoryPolicies.normalizeBlacklistTags(entry.tags),
            )
        }
    }
}

internal data class RecommendationProfileRecord(
    @field:SerializedName("profileId")
    val profileId: String? = null,
    @field:SerializedName("name")
    val name: String? = null,
) {
    fun toDomainOrNull(): RecommendationProfile? {
        val id = profileId?.trim().orEmpty()
        val profileName = name?.trim().orEmpty()
        if (id.isBlank() || profileName.isBlank()) return null
        return RecommendationProfile(profileId = id, name = profileName)
    }

    companion object {
        fun fromDomain(profile: RecommendationProfile): RecommendationProfileRecord {
            return RecommendationProfileRecord(profileId = profile.profileId, name = profile.name)
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

private fun decodeVideoQuality(value: String?, fallback: com.theoriacodex.domain.model.VideoQuality) =
    com.theoriacodex.domain.model.VideoQuality.entries.firstOrNull { it.name == value } ?: fallback
