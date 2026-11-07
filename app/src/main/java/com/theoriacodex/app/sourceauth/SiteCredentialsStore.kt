package com.theoriacodex.app.sourceauth

import com.theoriacodex.domain.source.SiteCredentials
import kotlinx.coroutines.flow.Flow

/**
 * Keys for user-added sites, by source id (plan C6). Stored in the encrypted credential payload's
 * additive `customSites` map, never in the site profile or a backup. Reads return null while the
 * credential store is unavailable; writes then throw.
 */
interface SiteCredentialsStore {
    suspend fun get(sourceId: String): SiteCredentials?
    suspend fun save(sourceId: String, credentials: SiteCredentials)
    suspend fun clear(sourceId: String)

    /** Emits on collection and after every committed change, so the custom-source runtime can rebuild. */
    val changes: Flow<Unit>
}
