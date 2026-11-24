package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeFavoriteTagForStorage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val SHARED_SOURCE_CATALOG_VERSION = 3

private val SOURCES_ADDED_BY_CATALOG_VERSION = mapOf(
    2 to setOf(SourceKey.HITOMI),
    3 to setOf(SourceKey.DANBOORU, SourceKey.AIBOORU),
)

@Serializable
internal data class SettingsStoreRecord(
    @SerialName("followedCreators")
    val followedCreators: List<FollowedCreatorRecord>? = null,
    @SerialName("sourceCatalogVersion")
    val sourceCatalogVersion: Int? = null,
    @SerialName("enabledSources")
    val enabledSources: List<String> = SourceKey.builtIns.map { it.id },
    @SerialName("sourceWeights")
    val sourceWeights: Map<String, Double> = SourceRuntimeSettings().sourceWeights.mapKeys { it.key.id },
    @SerialName("sourceWeightsByConfiguration")
    val sourceWeightsByConfiguration: Map<String, Map<String, Double>>? = null,
    @SerialName("viewerPlaybackMode") val viewerPlaybackMode: String? = null,
    @SerialName("videoQuality") val videoQuality: String? = null,
    @SerialName("downloadQuality") val downloadQuality: String? = null,
    @SerialName("downloadsOverMetered") val downloadsOverMetered: Boolean? = null,
    @SerialName("downloadsOverRoaming") val downloadsOverRoaming: Boolean? = null,
    @SerialName("cacheFullImageOnSave")
    val cacheFullImageOnSave: Boolean = false,
    @SerialName("resolveUnknownAnimatedDurations")
    val resolveUnknownAnimatedDurations: Boolean = false,
    @SerialName("invertMultiImageScrollDirection")
    val invertMultiImageScrollDirection: Boolean = false,
    @SerialName("oldSearchBar") val oldSearchBar: Boolean = false,
    @SerialName("seriesUpdateInterval") val seriesUpdateInterval: String? = null,
    @SerialName("seriesDeleteAfterReading") val seriesDeleteAfterReading: Boolean? = null,
    @SerialName("codexLibrarySort") val codexLibrarySort: String? = null,
    @SerialName("instantSeek") val instantSeek: Boolean = false,
    @SerialName("viewerAutoRotateVideos") val viewerAutoRotateVideos: Boolean? = null,
    @SerialName("viewerCommentOrder") val viewerCommentOrder: String? = null,
    @SerialName("recommendationProfiles")
    val recommendationProfiles: List<RecommendationProfileRecord>? = null,
    @SerialName("activeProfileId")
    val activeProfileId: String? = null,
    @SerialName("forYouBlacklistByProfile")
    val forYouBlacklistByProfile: Map<String, List<ForYouBlacklistEntryRecord>>? = null,
    @SerialName("favoriteTagsByProfile")
    val favoriteTagsByProfile: Map<String, List<FavoriteTagEntryRecord>>? = null,
    @SerialName("providerHealth")
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
            ?: profiles.first().profileId
        return RepositoryPolicies.normalizeSettings(
            AppSettings(
                oldSearchBar = oldSearchBar,
                seriesUpdateInterval = SeriesUpdateInterval.entries.firstOrNull { it.name == seriesUpdateInterval }
                    ?: AppSettings().seriesUpdateInterval,
                seriesDeleteAfterReading = seriesDeleteAfterReading ?: false,
                codexLibrarySort = CodexLibrarySort.entries.firstOrNull { it.name == codexLibrarySort }
                    ?: CodexLibrarySort.MANUAL,
                followedCreators = followedCreators.orEmpty().mapNotNull { it.toDomain() }.distinctBy { it.creator.followKey() }.take(MAX_FOLLOWED_CREATORS),
                runtime = runtime,
                cache = toCacheSettings(),
                contentFilters = ContentFilterSettings(
                    resolveUnknownAnimatedDurations = resolveUnknownAnimatedDurations,
                ),
                viewer = toViewerSettings(),
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
        )
    }

    fun sourceSchemaVersion(): Int = sourceCatalogVersion ?: 1

    companion object {
        fun fromDomain(settings: AppSettings): SettingsStoreRecord {
            return SettingsStoreRecord(
                followedCreators = settings.followedCreators.map(FollowedCreatorRecord::fromDomain),
                sourceCatalogVersion = SHARED_SOURCE_CATALOG_VERSION,
                oldSearchBar = settings.oldSearchBar,
                seriesUpdateInterval = settings.seriesUpdateInterval.takeIf { it != AppSettings().seriesUpdateInterval }?.name,
                seriesDeleteAfterReading = settings.seriesDeleteAfterReading.takeIf { it },
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
                recommendationProfiles = settings.recommendationProfiles.map(RecommendationProfileRecord::fromDomain),
                activeProfileId = settings.activeProfileId,
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

@Serializable
internal data class ProviderHealthSnapshotRecord(
    @SerialName("source")
    val source: String? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("checkedAtEpochMs")
    val checkedAtEpochMs: Long? = null,
    @SerialName("latencyMs")
    val latencyMs: Long? = null,
    @SerialName("failureReason")
    val failureReason: String? = null,
    @SerialName("message")
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

@Serializable
internal data class FavoriteTagEntryRecord(
    @SerialName("source")
    val source: String? = null,
    @SerialName("tag")
    val tag: String? = null,
    @SerialName("tags")
    val tags: List<String>? = null,
    @SerialName("name")
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
            val normalized = entry.tags.map { tag -> normalizeFavoriteTagForStorage(entry.source, tag) }
            return FavoriteTagEntryRecord(
                source = entry.source.id,
                tag = normalized.first(),
                tags = normalized.takeIf { it.size > 1 },
                name = entry.name,
            )
        }
    }
}

@Serializable
internal data class ForYouBlacklistEntryRecord(
    @SerialName("source")
    val source: String? = null,
    @SerialName("tags")
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

@Serializable
internal data class RecommendationProfileRecord(
    @SerialName("profileId")
    val profileId: String? = null,
    @SerialName("name")
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

private fun decodeVideoQuality(value: String?, fallback: com.theoriacodex.domain.model.VideoQuality) =
    com.theoriacodex.domain.model.VideoQuality.entries.firstOrNull { it.name == value } ?: fallback
