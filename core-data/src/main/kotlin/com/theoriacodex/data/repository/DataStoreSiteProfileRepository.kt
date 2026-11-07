package com.theoriacodex.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.theoriacodex.data.storage.GsonDataStoreSerializer
import com.theoriacodex.data.storage.UnsupportedStoreSchemaException
import com.theoriacodex.data.storage.preserveCorruptFile
import com.theoriacodex.data.storage.updateDataInBackground
import com.theoriacodex.domain.source.SiteProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

const val SITE_PROFILES_FILE_NAME = "custom_sites_v1.json"
internal const val SITE_PROFILES_SCHEMA_VERSION = 1

/**
 * User-added sites in `custom_sites_v1.json` (plan C2), following [SiteProfileListPolicy]. Records
 * this version cannot read are skipped, not fatal, and are written back untouched so a newer app
 * version keeps them. A newer schema is refused rather than overwritten; a corrupt file is copied
 * aside and the store starts empty (library data is unaffected, and re-adding a site reconnects it).
 */
class DataStoreSiteProfileRepository(
    baseDirectory: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    gson: Gson = Gson(),
) : SiteProfileRepository {
    private val storeFile = baseDirectory.resolve(SITE_PROFILES_FILE_NAME)
    private val dataStore: DataStore<SiteProfileStoreFile> = DataStoreFactory.create(
        serializer = GsonDataStoreSerializer(
            storeName = SITE_PROFILES_FILE_NAME,
            defaultValue = SiteProfileStoreFile(),
            type = object : TypeToken<SiteProfileStoreFile>() {}.type,
            gson = gson,
            validate = SiteProfileStoreFile::validate,
        ),
        corruptionHandler = ReplaceFileCorruptionHandler {
            preserveCorruptFile(storeFile)
            SiteProfileStoreFile()
        },
        scope = scope,
        produceFile = { storeFile },
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

internal data class SiteProfileStoreFile(
    @field:SerializedName("schemaVersion") val schemaVersion: Int = SITE_PROFILES_SCHEMA_VERSION,
    /** Entries are nullable because Gson writes whatever the file holds, including `null`. */
    @field:SerializedName("sites") val sites: List<SiteProfileRecord?> = emptyList(),
) {
    /** Readable profiles in added order; the first record wins for a repeated id. */
    fun profiles(): List<SiteProfile> = sites.mapNotNull { it?.toDomainOrNull() }.distinctBy(SiteProfile::sourceId)

    /** [profiles] become the readable records; unreadable records are carried over after them. */
    fun withProfiles(profiles: List<SiteProfile>): SiteProfileStoreFile {
        val unreadable = sites.filterNotNull().filter { it.toDomainOrNull() == null }
        return copy(sites = profiles.map(SiteProfileRecord::fromDomain) + unreadable)
    }

    fun validate() {
        if (schemaVersion > SITE_PROFILES_SCHEMA_VERSION) {
            throw UnsupportedStoreSchemaException(SITE_PROFILES_FILE_NAME, schemaVersion, SITE_PROFILES_SCHEMA_VERSION)
        }
        require(schemaVersion == SITE_PROFILES_SCHEMA_VERSION) { "Unsupported site profile schema $schemaVersion" }
    }
}
