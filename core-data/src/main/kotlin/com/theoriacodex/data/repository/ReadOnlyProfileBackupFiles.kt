package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.theoriacodex.data.storage.SavedSearchSnapshot
import com.theoriacodex.data.storage.SavedSearchSnapshotAdapter
import java.io.File

/** Strict snapshot decoding without DataStore startup, migration, quarantine, or recovery writes. */
class ReadOnlyProfileBackupFiles private constructor(
    private val directory: File,
    private val documents: Map<String, ByteArray?>,
) {

    val settings: AppSettings = requireNotNull(documents[DATASTORE_SETTINGS_FILE_NAME]).let { bytes ->
        val record = requireNotNull(Gson().fromJson(decodeDocumentUtf8(bytes), SettingsDataStoreFile::class.java))
        require(record.schemaVersion == SETTINGS_DATASTORE_SCHEMA_VERSION)
        record.validate()
        record.toDomain()
    }
    val savedSearches: List<SavedSearchEntry> = documents["pinned_searches.json"]?.let { bytes ->
        val gson = GsonBuilder().registerTypeAdapter(SavedSearchSnapshot::class.java, SavedSearchSnapshotAdapter()).create()
        requireNotNull(gson.fromJson(decodeDocumentUtf8(bytes), SavedSearchSnapshot::class.java)).entries
    }.orEmpty()
    val readingPositions: List<ReadingPosition> = documents[READING_POSITIONS_FILE_NAME]?.let { bytes ->
        decodeReadingPositions(JsonParser.parseString(decodeDocumentUtf8(bytes)).asJsonObject)
    }.orEmpty()

    init {
        requireNoPendingOperations()
    }

    /** Detects cross-store changes while the independent library snapshot was being acquired. */
    suspend fun requireUnchanged() {
        requireNoPendingOperations()
        require(names.all { name ->
            val before = documents[name]
            val after = read(directory, name)
            if (before == null) after == null else after != null && before.contentEquals(after)
        }) { "Profile files changed during export" }
    }

    private fun requireNoPendingOperations() {
        require(listOf("pending_profile_restore.json", "pending_profile_deletion.json").none {
            File(directory, it).exists()
        }) { "Finish pending profile operations in the ordinary app first" }
    }

    companion object {
        private val names = listOf(DATASTORE_SETTINGS_FILE_NAME, "pinned_searches.json", READING_POSITIONS_FILE_NAME)

        suspend fun capture(directory: File): ReadOnlyProfileBackupFiles = ReadOnlyProfileBackupFiles(
            directory, names.associateWith { name -> read(directory, name) },
        )

        private suspend fun read(directory: File, name: String): ByteArray? {
            val file = File(directory, name)
            if (!file.exists()) return null
            return file.inputStream().use { readBoundedDocument(it, PROFILE_BACKUP_MAX_BYTES) }.also {
                checkDocumentDepth(decodeDocumentUtf8(it))
            }
        }
    }
}
