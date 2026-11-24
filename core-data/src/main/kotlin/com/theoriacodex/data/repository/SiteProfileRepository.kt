package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.MemoryStateCell
import com.theoriacodex.data.storage.StateCell
import com.theoriacodex.domain.source.SiteProfile
import kotlinx.coroutines.flow.Flow

interface SiteProfileRepository {
    fun observeProfiles(): Flow<List<SiteProfile>>

    suspend fun add(profile: SiteProfile): SiteProfileAddResult

    suspend fun update(sourceId: String, transform: (SiteProfile) -> SiteProfile): Boolean

    suspend fun remove(sourceId: String): Boolean

    suspend fun mergeRestored(profiles: List<SiteProfile>): SiteProfileMergeResult
}

sealed interface SiteProfileAddResult {
    data object Added : SiteProfileAddResult
    data class Duplicate(val existingSourceId: String) : SiteProfileAddResult
    data object LimitReached : SiteProfileAddResult
}

data class SiteProfileMergeResult(val added: Int, val keptExisting: List<String>)

class InMemorySiteProfileRepository(initial: List<SiteProfile> = emptyList()) :
    SiteProfileRepository by CellSiteProfileRepository(MemoryStateCell(initial.distinctBy(SiteProfile::sourceId)))

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
