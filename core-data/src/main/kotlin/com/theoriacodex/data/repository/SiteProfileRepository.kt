package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.MemoryStateCell
import com.theoriacodex.data.storage.StateCell
import com.theoriacodex.domain.source.SiteProfile
import kotlinx.coroutines.flow.Flow

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

class InMemorySiteProfileRepository(initial: List<SiteProfile> = emptyList()) :
    SiteProfileRepository by CellSiteProfileRepository(MemoryStateCell(initial.distinctBy(SiteProfile::sourceId)))

/** Sites over a [StateCell], following [SiteProfileListPolicy]; shared by the stored and in-memory repositories. */
internal class CellSiteProfileRepository(private val cell: StateCell<List<SiteProfile>>) : SiteProfileRepository {
    override fun observeProfiles(): Flow<List<SiteProfile>> = cell.data

    override suspend fun add(profile: SiteProfile): SiteProfileAddResult =
        cell.updateReturning { SiteProfileListPolicy.add(it, profile) }

    override suspend fun update(sourceId: String, transform: (SiteProfile) -> SiteProfile): Boolean =
        cell.updateReturning { current ->
            SiteProfileListPolicy.update(current, sourceId, transform)?.let { it to true } ?: (current to false)
        }

    override suspend fun remove(sourceId: String): Boolean = cell.updateReturning { current ->
        SiteProfileListPolicy.remove(current, sourceId)?.let { it to true } ?: (current to false)
    }

    override suspend fun mergeRestored(profiles: List<SiteProfile>): SiteProfileMergeResult =
        cell.updateReturning { SiteProfileListPolicy.mergeRestored(it, profiles) }
}
