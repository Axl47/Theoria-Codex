package com.theoriacodex.data.repository

import com.theoriacodex.domain.source.SiteProfile

/**
 * The list rules every [SiteProfileRepository] applies, as pure functions over the stored list, so
 * the in-memory reference and the DataStore store cannot drift apart.
 */
internal object SiteProfileListPolicy {
    fun add(current: List<SiteProfile>, profile: SiteProfile): Pair<List<SiteProfile>, SiteProfileAddResult> = when {
        current.any { it.sourceId == profile.sourceId } -> current to SiteProfileAddResult.Duplicate(profile.sourceId)
        current.size >= SiteProfile.MAX_CUSTOM_SITES -> current to SiteProfileAddResult.LimitReached
        else -> (current + profile) to SiteProfileAddResult.Added
    }

    /** Null when [sourceId] is not stored. [transform] must keep the id and address. */
    fun update(current: List<SiteProfile>, sourceId: String, transform: (SiteProfile) -> SiteProfile): List<SiteProfile>? {
        val index = current.indexOfFirst { it.sourceId == sourceId }
        if (index < 0) return null
        val updated = transform(current[index])
        require(updated.sourceId == sourceId && updated.address == current[index].address) {
            "A site's id and address cannot change"
        }
        return current.toMutableList().apply { set(index, updated) }
    }

    /** Null when [sourceId] is not stored. */
    fun remove(current: List<SiteProfile>, sourceId: String): List<SiteProfile>? =
        current.filterNot { it.sourceId == sourceId }.takeIf { it.size != current.size }

    /** Adds absent ids, keeps stored profiles, and never applies the site limit (see [SiteProfileRepository.mergeRestored]). */
    fun mergeRestored(current: List<SiteProfile>, imported: List<SiteProfile>): Pair<List<SiteProfile>, SiteProfileMergeResult> {
        val stored = current.associateBy(SiteProfile::sourceId)
        val distinct = imported.distinctBy(SiteProfile::sourceId)
        val added = distinct.filter { it.sourceId !in stored }
        val kept = distinct.mapNotNull { profile ->
            stored[profile.sourceId]
                ?.takeIf { it.address != profile.address || it.familyId != profile.familyId }
                ?.sourceId
        }
        return (current + added) to SiteProfileMergeResult(added = added.size, keptExisting = kept)
    }
}
