package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import kotlin.math.abs
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
        val normalizedProfiles = normalizeRecommendationProfiles(settings.recommendationProfiles)
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
                    .mapNotNull { entry -> normalizeFavoriteTagEntry(entry.source, entry.tags, entry.name) }
                    .distinctBy { entry -> entry.source to favoriteTagKeys(entry.source, entry.tags) }
                    .toList()
                normalizedProfileId to normalizedEntries
            }
            .toMap()
            .filterValues { entries -> entries.isNotEmpty() }
        val normalizedProviderHealth = settings.providerHealth
            .mapValues { (source, snapshot) -> snapshot.copy(source = source) }
            .filterValues { snapshot -> snapshot.checkedAtEpochMs >= 0L }

        return settings.copy(
            runtime = SourceWeightSettings.normalize(settings.runtime),
            recommendationProfiles = normalizedProfiles,
            activeProfileId = activeProfileId,
            forYouBlacklistByProfile = normalizedBlacklist,
            favoriteTagsByProfile = normalizedFavoriteTags,
            providerHealth = normalizedProviderHealth,
        )
    }

    private fun normalizeRecommendationProfiles(profiles: List<RecommendationProfile>): List<RecommendationProfile> {
        return profiles
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
    }

    fun normalizeSourceWeights(
        enabledSources: Set<SourceKey>,
        rawWeights: Map<SourceKey, Double>,
    ): Map<SourceKey, Double> {
        val orderedSources = enabledSources.sorted()
        if (orderedSources.isEmpty()) return emptyMap()

        val defaults = SourceRuntimeSettings().sourceWeights
        val knownWeights = orderedSources.associateWith { source -> rawWeights[source] ?: defaults[source] }
            .mapValues { (_, weight) -> weight?.takeIf(Double::isFinite)?.coerceAtLeast(0.0) }
        // A source with no stored or default weight (a newly opted-in custom site) gets the others'
        // mean, an even share, rather than a raw 1.0 that would dominate already-normalized weights.
        val fallbackWeight = knownWeights.values.filterNotNull().takeIf { it.isNotEmpty() }?.average() ?: 1.0
        val safeWeights = orderedSources.associateWith { source ->
            when {
                source !in rawWeights && source !in defaults -> fallbackWeight
                else -> knownWeights[source] ?: 0.0
            }
        }
        // Already-normalized weights pass through, so normalizing again never drifts in the last bit.
        if (abs(safeWeights.values.sum() - 1.0) <= NORMALIZED_WEIGHT_TOLERANCE) return safeWeights
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

    fun renameRecommendationProfile(
        settings: AppSettings,
        profileId: String,
        requestedName: String,
    ): Result<AppSettings, Boolean> {
        val current = normalizeSettings(settings)
        val normalizedProfileId = normalizeProfileId(profileId)
        val name = requestedName.trim()
        if (name.isBlank() || current.recommendationProfiles.none { it.profileId == normalizedProfileId }) {
            return Result(current, false)
        }
        val updated = current.copy(
            recommendationProfiles = current.recommendationProfiles.map { profile ->
                if (profile.profileId == normalizedProfileId) profile.copy(name = name) else profile
            },
        )
        return Result(normalizeSettings(updated), true)
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
        tags: List<String>,
        name: String? = null,
    ): Result<AppSettings, Boolean> {
        val current = normalizeSettings(settings)
        val normalizedProfileId = normalizeProfileId(profileId)
        if (current.recommendationProfiles.none { it.profileId == normalizedProfileId }) {
            return Result(current, false)
        }
        val entry = normalizeFavoriteTagEntry(source, tags, name) ?: return Result(current, false)
        val keys = favoriteTagKeys(source, entry.tags)
        val existing = current.favoriteTagsByProfile[normalizedProfileId].orEmpty()
        if (existing.any { saved -> saved.source == source && favoriteTagKeys(source, saved.tags) == keys }) {
            return Result(current, false)
        }
        val updated = current.copy(
            favoriteTagsByProfile = current.favoriteTagsByProfile + (normalizedProfileId to (existing + entry)),
        )
        return Result(normalizeSettings(updated), true)
    }

    fun removeFavoriteTag(
        settings: AppSettings,
        profileId: String,
        source: SourceKey,
        tags: List<String>,
    ): Result<AppSettings, Boolean> {
        val current = normalizeSettings(settings)
        val normalizedProfileId = normalizeProfileId(profileId)
        val keys = favoriteTagKeys(source, tags)
        if (keys.isEmpty()) return Result(current, false)
        val existing = current.favoriteTagsByProfile[normalizedProfileId].orEmpty()
        val updatedEntries = existing.filterNot { entry ->
            entry.source == source && favoriteTagKeys(source, entry.tags) == keys
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

    fun renameFavoriteTag(
        settings: AppSettings,
        profileId: String,
        source: SourceKey,
        tags: List<String>,
        name: String?,
    ): Result<AppSettings, Boolean> {
        val current = normalizeSettings(settings)
        val normalizedProfileId = normalizeProfileId(profileId)
        val keys = favoriteTagKeys(source, tags)
        val existing = current.favoriteTagsByProfile[normalizedProfileId].orEmpty()
        var changed = false
        val updatedEntries = existing.map { entry ->
            if (entry.source != source || favoriteTagKeys(source, entry.tags) != keys) return@map entry
            val renamed = normalizeFavoriteTagEntry(source, entry.tags, name) ?: entry
            changed = changed || renamed != entry
            renamed
        }
        if (!changed) return Result(current, false)
        return Result(
            state = normalizeSettings(current.copy(favoriteTagsByProfile = current.favoriteTagsByProfile + (normalizedProfileId to updatedEntries))),
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

    /**
     * Normalizes each tag and drops blanks and repeats; null when no tag remains. Only a group keeps
     * a [name], trimmed onto one line and capped at [MAX_FAVORITE_TAG_NAME_LENGTH] characters.
     */
    fun normalizeFavoriteTagEntry(source: SourceKey, tags: List<String>, name: String? = null): FavoriteTagEntry? {
        val normalized = tags
            .map { tag -> normalizeFavoriteTag(source, tag) }
            .filter(String::isNotBlank)
            .distinctBy { tag -> sourceTagKey(source, tag) }
            .takeIf(List<String>::isNotEmpty)
            ?: return null
        val groupName = name?.replace(LINE_BREAK_REGEX, " ")?.trim()?.take(MAX_FAVORITE_TAG_NAME_LENGTH)
            ?.takeIf { it.isNotBlank() && normalized.size > 1 }
        return FavoriteTagEntry(source, normalized, groupName)
    }

    /** An entry's identity: its tags as the source compares them, ignoring order. */
    private fun favoriteTagKeys(source: SourceKey, tags: List<String>): Set<String> =
        tags.mapNotNullTo(mutableSetOf()) { tag -> sourceTagKey(source, tag).takeIf(String::isNotBlank) }

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

private const val MAX_FAVORITE_TAG_NAME_LENGTH = 60
private const val NORMALIZED_WEIGHT_TOLERANCE = 1e-9
private val LINE_BREAK_REGEX = Regex("\\s*[\\r\\n]+\\s*")
