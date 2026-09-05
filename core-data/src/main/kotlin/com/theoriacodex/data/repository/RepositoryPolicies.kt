package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.normalizeFavoriteTagForStorage
import com.theoriacodex.domain.tags.sourceTagKey

/**
 * Settings normalization shared by DataStore persistence, migration and test fixtures.
 *
 * Storage mechanics intentionally stay in each repository. This object only decides what the
 * next observable domain state should be for an operation.
 */
internal object RepositoryPolicies {
    data class Result<State, Value>(
        val state: State,
        val value: Value,
    )

    fun normalizeSettings(settings: AppSettings): AppSettings {
        val normalizedProfiles = settings.recommendationProfiles
            .asSequence()
            .map { profile ->
                RecommendationProfile(
                    profileId = profile.profileId.trim(),
                    name = profile.name.trim(),
                )
            }
            .filter { profile -> profile.profileId.isNotBlank() && profile.name.isNotBlank() }
            .distinctBy { profile -> profile.profileId }
            .toList()
            .ifEmpty { defaultRecommendationProfiles() }
        val requestedActiveProfileId = normalizeProfileId(settings.activeProfileId)
        val activeProfileId = requestedActiveProfileId
            .takeIf { active -> normalizedProfiles.any { profile -> profile.profileId == active } }
            ?: normalizedProfiles.first().profileId
        val profileIds = normalizedProfiles.mapTo(mutableSetOf()) { profile -> profile.profileId }
        val normalizedBlacklist = settings.forYouBlacklistByProfile
            .mapNotNull { (profileId, entries) ->
                val normalizedProfileId = normalizeProfileId(profileId)
                if (normalizedProfileId !in profileIds) return@mapNotNull null
                val normalizedEntries = entries
                    .asSequence()
                    .mapNotNull { entry ->
                        normalizeBlacklistTags(entry.tags)
                            .takeIf(List<String>::isNotEmpty)
                            ?.let { tags -> ForYouBlacklistEntry(source = entry.source, tags = tags) }
                    }
                    .distinctBy { entry -> entry.source to entry.tags }
                    .toList()
                normalizedProfileId to normalizedEntries
            }
            .toMap()
            .filterValues { entries -> entries.isNotEmpty() }
        val normalizedFavoriteTags = settings.favoriteTagsByProfile
            .mapNotNull { (profileId, entries) ->
                val normalizedProfileId = normalizeProfileId(profileId)
                if (normalizedProfileId !in profileIds) return@mapNotNull null
                val normalizedEntries = entries
                    .asSequence()
                    .mapNotNull { entry ->
                        normalizeFavoriteTag(entry.source, entry.tag)
                            .takeIf(String::isNotBlank)
                            ?.let { tag -> FavoriteTagEntry(source = entry.source, tag = tag) }
                    }
                    .distinctBy { entry -> entry.source to sourceTagKey(entry.source, entry.tag) }
                    .toList()
                normalizedProfileId to normalizedEntries
            }
            .toMap()
            .filterValues { entries -> entries.isNotEmpty() }
        val normalizedProviderHealth = settings.providerHealth
            .mapValues { (source, snapshot) -> snapshot.copy(source = source) }
            .filterValues { snapshot -> snapshot.checkedAtEpochMs >= 0L }

        return settings.copy(
            runtime = settings.runtime.copy(
                sourceWeights = normalizeSourceWeights(
                    enabledSources = settings.runtime.enabledSources,
                    rawWeights = settings.runtime.sourceWeights,
                ),
            ),
            recommendationProfiles = normalizedProfiles,
            activeProfileId = activeProfileId,
            forYouBlacklistByProfile = normalizedBlacklist,
            favoriteTagsByProfile = normalizedFavoriteTags,
            providerHealth = normalizedProviderHealth,
        )
    }

    fun normalizeSourceWeights(
        enabledSources: Set<SourceKey>,
        rawWeights: Map<SourceKey, Double>,
    ): Map<SourceKey, Double> {
        val orderedSources = SourceKey.entries.filter { source -> source in enabledSources }
        if (orderedSources.isEmpty()) return emptyMap()

        val defaults = SourceRuntimeSettings().sourceWeights
        val safeWeights = orderedSources.associateWith { source ->
            val rawWeight = rawWeights[source] ?: defaults[source] ?: 1.0
            rawWeight.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
        }
        val largestWeight = safeWeights.values.maxOrNull().orZero()
        if (largestWeight <= 0.0) {
            val uniformWeight = 1.0 / orderedSources.size.toDouble()
            return orderedSources.associateWith { uniformWeight }
        }

        // Scale before summing so even several Double.MAX_VALUE inputs cannot overflow the total.
        val scaledWeights = safeWeights.mapValues { (_, weight) -> weight / largestWeight }
        val scaledTotal = scaledWeights.values.sum()
        if (!scaledTotal.isFinite() || scaledTotal <= 0.0) {
            val uniformWeight = 1.0 / orderedSources.size.toDouble()
            return orderedSources.associateWith { uniformWeight }
        }
        return scaledWeights.mapValues { (_, weight) -> weight / scaledTotal }
    }

    fun addRecommendationProfile(
        settings: AppSettings,
        requestedName: String,
        profileId: String,
    ): Result<AppSettings, RecommendationProfile> {
        val current = normalizeSettings(settings)
        val created = RecommendationProfile(
            profileId = normalizeProfileId(profileId),
            name = requestedName.trim().ifBlank {
                "Profile ${current.recommendationProfiles.size + 1}"
            },
        )
        val updated = normalizeSettings(
            current.copy(
                recommendationProfiles = current.recommendationProfiles + created,
                activeProfileId = created.profileId,
            )
        )
        return Result(state = updated, value = created)
    }

    fun removeRecommendationProfile(
        settings: AppSettings,
        profileId: String,
    ): Result<AppSettings, Boolean> {
        val current = normalizeSettings(settings)
        val normalizedProfileId = normalizeProfileId(profileId)
        if (current.recommendationProfiles.size <= 1) return Result(current, false)
        if (current.recommendationProfiles.none { it.profileId == normalizedProfileId }) {
            return Result(current, false)
        }
        val remaining = current.recommendationProfiles.filterNot { it.profileId == normalizedProfileId }
        val updated = current.copy(
            recommendationProfiles = remaining,
            activeProfileId = if (current.activeProfileId == normalizedProfileId) {
                remaining.first().profileId
            } else {
                current.activeProfileId
            },
            forYouBlacklistByProfile = current.forYouBlacklistByProfile - normalizedProfileId,
            favoriteTagsByProfile = current.favoriteTagsByProfile - normalizedProfileId,
        )
        return Result(normalizeSettings(updated), true)
    }

    fun addBlacklistEntry(
        settings: AppSettings,
        profileId: String,
        source: SourceKey,
        tags: List<String>,
    ): Result<AppSettings, Boolean> {
        val current = normalizeSettings(settings)
        val normalizedProfileId = normalizeProfileId(profileId)
        if (current.recommendationProfiles.none { it.profileId == normalizedProfileId }) {
            return Result(current, false)
        }
        val normalizedTags = normalizeBlacklistTags(tags)
        if (normalizedTags.isEmpty()) return Result(current, false)
        val existing = current.forYouBlacklistByProfile[normalizedProfileId].orEmpty()
        if (existing.any { entry -> entry.source == source && entry.tags == normalizedTags }) {
            return Result(current, false)
        }
        val updated = current.copy(
            forYouBlacklistByProfile = current.forYouBlacklistByProfile + (
                normalizedProfileId to (existing + ForYouBlacklistEntry(source, normalizedTags))
                ),
        )
        return Result(normalizeSettings(updated), true)
    }

    fun removeBlacklistEntry(
        settings: AppSettings,
        profileId: String,
        source: SourceKey,
        tags: List<String>,
    ): Result<AppSettings, Boolean> {
        val current = normalizeSettings(settings)
        val normalizedProfileId = normalizeProfileId(profileId)
        val normalizedTags = normalizeBlacklistTags(tags)
        if (normalizedTags.isEmpty()) return Result(current, false)
        val existing = current.forYouBlacklistByProfile[normalizedProfileId].orEmpty()
        val updatedEntries = existing.filterNot { entry ->
            entry.source == source && entry.tags == normalizedTags
        }
        if (updatedEntries.size == existing.size) return Result(current, false)
        val updatedByProfile = current.forYouBlacklistByProfile.toMutableMap().apply {
            if (updatedEntries.isEmpty()) remove(normalizedProfileId) else put(normalizedProfileId, updatedEntries)
        }
        return Result(
            state = normalizeSettings(current.copy(forYouBlacklistByProfile = updatedByProfile)),
            value = true,
        )
    }

    fun addFavoriteTag(
        settings: AppSettings,
        profileId: String,
        source: SourceKey,
        tag: String,
    ): Result<AppSettings, Boolean> {
        val current = normalizeSettings(settings)
        val normalizedProfileId = normalizeProfileId(profileId)
        if (current.recommendationProfiles.none { it.profileId == normalizedProfileId }) {
            return Result(current, false)
        }
        val normalizedTag = normalizeFavoriteTag(source, tag)
        if (normalizedTag.isBlank()) return Result(current, false)
        val normalizedKey = sourceTagKey(source, normalizedTag)
        val existing = current.favoriteTagsByProfile[normalizedProfileId].orEmpty()
        if (existing.any { entry -> entry.source == source && sourceTagKey(source, entry.tag) == normalizedKey }) {
            return Result(current, false)
        }
        val updated = current.copy(
            favoriteTagsByProfile = current.favoriteTagsByProfile + (
                normalizedProfileId to (existing + FavoriteTagEntry(source, normalizedTag))
                ),
        )
        return Result(normalizeSettings(updated), true)
    }

    fun removeFavoriteTag(
        settings: AppSettings,
        profileId: String,
        source: SourceKey,
        tag: String,
    ): Result<AppSettings, Boolean> {
        val current = normalizeSettings(settings)
        val normalizedProfileId = normalizeProfileId(profileId)
        val normalizedKey = sourceTagKey(source, tag)
        if (normalizedKey.isBlank()) return Result(current, false)
        val existing = current.favoriteTagsByProfile[normalizedProfileId].orEmpty()
        val updatedEntries = existing.filterNot { entry ->
            entry.source == source && sourceTagKey(source, entry.tag) == normalizedKey
        }
        if (updatedEntries.size == existing.size) return Result(current, false)
        val updatedByProfile = current.favoriteTagsByProfile.toMutableMap().apply {
            if (updatedEntries.isEmpty()) remove(normalizedProfileId) else put(normalizedProfileId, updatedEntries)
        }
        return Result(
            state = normalizeSettings(current.copy(favoriteTagsByProfile = updatedByProfile)),
            value = true,
        )
    }

    fun mergeProviderHealth(
        settings: AppSettings,
        snapshots: List<ProviderHealthSnapshot>,
    ): AppSettings {
        val merged = settings.providerHealth.toMutableMap()
        snapshots.forEach { snapshot -> merged[snapshot.source] = snapshot }
        return normalizeSettings(settings.copy(providerHealth = merged))
    }

    fun normalizeFavoriteTag(source: SourceKey, tag: String): String {
        return normalizeFavoriteTagForStorage(source, tag)
    }

    fun normalizeProfileId(profileId: String): String = CodexLikesPolicy.normalizeProfileId(profileId)

    fun normalizeBlacklistTags(tags: List<String>): List<String> {
        return tags
            .asSequence()
            .map { tag -> tag.trim().lowercase() }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()
    }

    private fun Double?.orZero(): Double = this ?: 0.0
}
