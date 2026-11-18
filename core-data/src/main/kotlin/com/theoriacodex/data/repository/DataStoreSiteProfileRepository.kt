package com.theoriacodex.data.repository

import androidx.datastore.core.DataStore
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.openJsonDataStore
import com.theoriacodex.data.storage.updateDataInBackground
import com.theoriacodex.domain.source.SiteProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SITE_PROFILES_FILE_NAME = "custom_sites_v1.json"
internal const val SITE_PROFILES_SCHEMA_VERSION = 1

/**
 * User-added sites in `custom_sites_v1.json` (plan C2), following [SiteProfileListPolicy]. Records
 * this version cannot read are skipped, not fatal, and are written back untouched so a newer app
 * version keeps them. A newer schema is refused rather than overwritten; a corrupt file is set
 * aside and the store starts empty (library data is unaffected, and re-adding a site reconnects it).
 */
class DataStoreSiteProfileRepository(
    baseDirectory: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
) : SiteProfileRepository {
    private val storeFile = baseDirectory.resolve(SITE_PROFILES_FILE_NAME)
    private val dataStore: DataStore<SiteProfileStoreFile> = openJsonDataStore(
        file = storeFile,
        logicalStore = "Custom sites",
        schemaVersion = SITE_PROFILES_SCHEMA_VERSION,
        defaultValue = SiteProfileStoreFile(),
        serializer = SiteProfileStoreFile.serializer(),
        scope = scope,
        recoveries = recoveryRegistry,
    )

    override fun observeProfiles(): Flow<List<SiteProfile>> =
        dataStore.data.map(SiteProfileStoreFile::profiles).distinctUntilChanged()

    override suspend fun add(profile: SiteProfile): SiteProfileAddResult = mutate { SiteProfileListPolicy.add(it, profile) }

    override suspend fun update(sourceId: String, transform: (SiteProfile) -> SiteProfile): Boolean = mutate { current ->
        SiteProfileListPolicy.update(current, sourceId, transform)?.let { it to true } ?: (current to false)
    }

    override suspend fun remove(sourceId: String): Boolean = mutate { current ->
        SiteProfileListPolicy.remove(current, sourceId)?.let { it to true } ?: (current to false)
    }

    override suspend fun mergeRestored(profiles: List<SiteProfile>): SiteProfileMergeResult =
        mutate { SiteProfileListPolicy.mergeRestored(it, profiles) }

    /** Applies [change] to the readable profiles in one transaction and returns its result. */
    private suspend fun <R> mutate(change: (List<SiteProfile>) -> Pair<List<SiteProfile>, R>): R {
        var result: Result<R>? = null
        dataStore.updateDataInBackground { stored ->
            val (next, outcome) = change(stored.profiles())
            result = Result.success(outcome)
            if (next == stored.profiles()) stored else stored.withProfiles(next)
        }
        return checkNotNull(result).getOrThrow()
    }
}

@Serializable
internal data class SiteProfileStoreFile(
    @SerialName("schemaVersion") val schemaVersion: Int = SITE_PROFILES_SCHEMA_VERSION,
    /** Entries are nullable so one `null` entry is skipped instead of failing the whole file. */
    @SerialName("sites") val sites: List<SiteProfileRecord?> = emptyList(),
) {
    /** Readable profiles in added order; the first record wins for a repeated id. */
    fun profiles(): List<SiteProfile> = sites.mapNotNull { it?.toDomainOrNull() }.distinctBy(SiteProfile::sourceId)

    /** [profiles] become the readable records; unreadable records are carried over after them. */
    fun withProfiles(profiles: List<SiteProfile>): SiteProfileStoreFile {
        val unreadable = sites.filterNotNull().filter { it.toDomainOrNull() == null }
        return copy(sites = profiles.map(SiteProfileRecord::fromDomain) + unreadable)
    }
}
