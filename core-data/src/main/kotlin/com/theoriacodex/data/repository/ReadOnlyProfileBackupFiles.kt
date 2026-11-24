package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.SavedSearchSnapshotSerializer
import java.io.File

class ReadOnlyProfileBackupFiles private constructor(
    private val directory: File,
    private val documents: Map<String, ByteArray?>,
) {

    val settings: AppSettings = (
        documents[DATASTORE_SETTINGS_FILE_NAME]?.let { bytes ->
            DurableJson.decodeFromString(SettingsDataStoreFile.serializer(), decodeDocumentUtf8(bytes)).also { record ->
                require(record.schemaVersion == SETTINGS_DATASTORE_SCHEMA_VERSION)
                record.validate()
            }
        } ?: SettingsDataStoreFile()
    ).toDomain()
    val savedSearches: List<SavedSearchEntry> = documents["pinned_searches.json"]?.let { bytes ->
        DurableJson.decodeFromString(SavedSearchSnapshotSerializer, decodeDocumentUtf8(bytes)).entries
    }.orEmpty()
    val readingPositions: List<ReadingPosition> = documents[READING_POSITIONS_FILE_NAME]?.let { bytes ->
        decodeReadingPositions(DurableJson.decodeFromString(ReadingPositionsFile.serializer(), decodeDocumentUtf8(bytes)))
    }.orEmpty()

    init {
        requireNoPendingOperations()
    }

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
