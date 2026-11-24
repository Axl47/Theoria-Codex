package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AsynchronousStore
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.JsonDataStore
import com.theoriacodex.data.storage.focus
import com.theoriacodex.data.storage.openJsonDataStore
import com.theoriacodex.domain.source.SiteProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SITE_PROFILES_FILE_NAME = "custom_sites_v1.json"
internal const val SITE_PROFILES_SCHEMA_VERSION = 1

class DataStoreSiteProfileRepository private constructor(
    store: JsonDataStore<SiteProfileStoreFile>,
) : SiteProfileRepository by CellSiteProfileRepository(
    store.focus(SiteProfileStoreFile::profiles, SiteProfileStoreFile::withProfiles),
), AsynchronousStore by store {
    constructor(
        baseDirectory: File,
        scope: CoroutineScope,
        recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
    ) : this(
        openJsonDataStore(
            file = baseDirectory.resolve(SITE_PROFILES_FILE_NAME),
            logicalStore = "Custom sites",
            schemaVersion = SITE_PROFILES_SCHEMA_VERSION,
            defaultValue = SiteProfileStoreFile(),
            serializer = SiteProfileStoreFile.serializer(),
            scope = scope,
            recoveries = recoveryRegistry,
        ),
    )
}

@Serializable
internal data class SiteProfileStoreFile(
    @SerialName("schemaVersion") val schemaVersion: Int = SITE_PROFILES_SCHEMA_VERSION,
    @SerialName("sites") val sites: List<SiteProfileRecord?> = emptyList(),
) {
    fun profiles(): List<SiteProfile> = sites.mapNotNull { it?.toDomainOrNull() }.distinctBy(SiteProfile::sourceId)

    fun withProfiles(profiles: List<SiteProfile>): SiteProfileStoreFile {
        val unreadable = sites.filterNotNull().filter { it.toDomainOrNull() == null }
        return copy(sites = profiles.map(SiteProfileRecord::fromDomain) + unreadable)
    }
}
