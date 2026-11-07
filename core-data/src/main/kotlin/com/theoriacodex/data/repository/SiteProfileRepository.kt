package com.theoriacodex.data.repository

import com.theoriacodex.domain.source.SiteProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stored user-added sites (plan contract C2). Step 3 implements it over `custom_sites_v1.json`;
 * [InMemorySiteProfileRepository] is the reference behavior and the default for backups and tests.
 */
interface SiteProfileRepository {
    /** Profiles in the order they were added. */
    fun observeProfiles(): Flow<List<SiteProfile>>

    suspend fun add(profile: SiteProfile): SiteProfileAddResult

    /**
     * Replaces one profile with [transform]'s result, which must keep its `sourceId` and `address`.
     * Returns false when [sourceId] is not stored.
     */
    suspend fun update(sourceId: String, transform: (SiteProfile) -> SiteProfile): Boolean

    suspend fun remove(sourceId: String): Boolean

    /**
     * Restore: adds profiles whose `sourceId` is absent; profiles already stored keep their values.
     * Never fails on [SiteProfile.MAX_CUSTOM_SITES] (backup preflight enforces it), so journal
     * recovery cannot be blocked by a site added after preflight.
     */
    suspend fun mergeRestored(profiles: List<SiteProfile>): SiteProfileMergeResult
}

sealed interface SiteProfileAddResult {
    data object Added : SiteProfileAddResult
    data class Duplicate(val existingSourceId: String) : SiteProfileAddResult
    data object LimitReached : SiteProfileAddResult
}

/** [keptExisting]: ids whose stored profile won over an imported one with a different address or family. */
data class SiteProfileMergeResult(val added: Int, val keptExisting: List<String>)

class InMemorySiteProfileRepository(initial: List<SiteProfile> = emptyList()) : SiteProfileRepository {
    private val mutex = Mutex()
    private val profiles = MutableStateFlow(initial.distinctBy(SiteProfile::sourceId))

    override fun observeProfiles(): Flow<List<SiteProfile>> = profiles.asStateFlow()

    override suspend fun add(profile: SiteProfile): SiteProfileAddResult = mutex.withLock {
        val current = profiles.value
        when {
            current.any { it.sourceId == profile.sourceId } -> SiteProfileAddResult.Duplicate(profile.sourceId)
            current.size >= SiteProfile.MAX_CUSTOM_SITES -> SiteProfileAddResult.LimitReached
            else -> {
                profiles.value = current + profile
                SiteProfileAddResult.Added
            }
        }
    }

    override suspend fun update(sourceId: String, transform: (SiteProfile) -> SiteProfile): Boolean = mutex.withLock {
        val current = profiles.value
        val index = current.indexOfFirst { it.sourceId == sourceId }
        if (index < 0) return@withLock false
        val updated = transform(current[index])
        require(updated.sourceId == sourceId && updated.address == current[index].address) {
            "A site's id and address cannot change"
        }
        profiles.value = current.toMutableList().apply { set(index, updated) }
        true
    }

    override suspend fun remove(sourceId: String): Boolean = mutex.withLock {
        val current = profiles.value
        val remaining = current.filterNot { it.sourceId == sourceId }
        profiles.value = remaining
        remaining.size != current.size
    }

    override suspend fun mergeRestored(profiles: List<SiteProfile>): SiteProfileMergeResult = mutex.withLock {
        val stored = this.profiles.value.associateBy(SiteProfile::sourceId)
        val imported = profiles.distinctBy(SiteProfile::sourceId)
        val added = imported.filter { it.sourceId !in stored }
        val kept = imported.mapNotNull { profile ->
            stored[profile.sourceId]
                ?.takeIf { it.address != profile.address || it.familyId != profile.familyId }
                ?.sourceId
        }
        this.profiles.value += added
        SiteProfileMergeResult(added = added.size, keptExisting = kept)
    }
}
