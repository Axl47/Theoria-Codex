package com.theoriacodex.data.repository

import androidx.datastore.core.DataMigration
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.LegacyFileSnapshot
import com.theoriacodex.data.storage.LegacyImportProof
import com.theoriacodex.data.storage.archiveVerifiedLegacyFile
import com.theoriacodex.data.storage.readLegacySnapshot
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

internal class SettingsLegacyDataMigration(
    private val legacyFile: File,
    private val archiveFile: File,
    private val destinationExists: () -> Boolean,
    private val shouldReplayAfterCorruption: () -> Boolean,
    private val gson: Gson,
    private val onImported: (List<LegacyImportProof>) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : DataMigration<SettingsDataStoreFile> {
    override suspend fun shouldMigrate(currentData: SettingsDataStoreFile): Boolean {
        if (currentData.legacyImports.isNotEmpty()) return false
        if (destinationExists() && !shouldReplayAfterCorruption()) return false
        return readLegacySnapshot(legacyFile, archiveFile) != null
    }

    override suspend fun migrate(currentData: SettingsDataStoreFile): SettingsDataStoreFile {
        return migrationAttempt(onFailure) {
            val snapshot = readLegacySnapshot(legacyFile, archiveFile)
                ?: throw IOException("Legacy settings disappeared before migration")
            val legacy = snapshot.parse(LegacySettingsStoreRecord::class.java, gson, "legacy settings")
            val proof = snapshot.proof(
                sourceSchemaVersion = legacy.sourceSchemaVersion(),
                destinationSchemaVersion = SETTINGS_DATASTORE_SCHEMA_VERSION,
                importedCounts = legacy.importCounts(),
            )
            SettingsDataStoreFile.fromDomain(
                settings = legacy.toDomain(),
                legacyImports = listOf(proof),
            ).also { onImported(it.legacyImports) }
        }
    }

    override suspend fun cleanUp() {
        archiveVerifiedLegacyFile(legacyFile, archiveFile)
    }
}

internal class UiRestoreLegacyDataMigration(
    private val legacyUiFile: File,
    private val legacyUiArchiveFile: File,
    private val legacySettingsFile: File,
    private val legacySettingsArchiveFile: File,
    private val destinationExists: () -> Boolean,
    private val shouldReplayAfterCorruption: () -> Boolean,
    private val gson: Gson,
    private val onImported: (List<LegacyImportProof>) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : DataMigration<UiRestoreDataStoreFile> {
    override suspend fun shouldMigrate(currentData: UiRestoreDataStoreFile): Boolean {
        if (currentData.legacyImports.isNotEmpty()) return false
        if (destinationExists() && !shouldReplayAfterCorruption()) return false
        return readLegacySnapshot(legacyUiFile, legacyUiArchiveFile) != null ||
            readLegacySnapshot(legacySettingsFile, legacySettingsArchiveFile) != null
    }

    override suspend fun migrate(currentData: UiRestoreDataStoreFile): UiRestoreDataStoreFile {
        return migrationAttempt(onFailure) {
            val proofs = mutableListOf<LegacyImportProof>()
            val uiSnapshot = readLegacySnapshot(legacyUiFile, legacyUiArchiveFile)
            val legacyUi = uiSnapshot?.parse(LegacyUiRestoreStoreRecord::class.java, gson, "legacy UI restore")
            var state = legacyUi?.toMemoryState() ?: PersistedUiRestoreState(
                lastTab = null,
                scrollStates = emptyMap(),
                settingsSectionExpansion = emptyMap(),
                viewerLaunchContext = null,
            )
            if (uiSnapshot != null && legacyUi != null) {
                proofs += uiSnapshot.proof(
                    sourceSchemaVersion = 1,
                    destinationSchemaVersion = UI_RESTORE_DATASTORE_SCHEMA_VERSION,
                    importedCounts = legacyUi.importCounts(),
                )
            }

            if (state.lastTab == null) {
                val settingsSnapshot = readLegacySnapshot(legacySettingsFile, legacySettingsArchiveFile)
                val legacySettings = settingsSnapshot?.parse(
                    LegacySettingsStoreRecord::class.java,
                    gson,
                    "legacy settings last-tab state",
                )
                val migratedTab = legacySettings?.lastSelectedTabRoute
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                if (settingsSnapshot != null && legacySettings != null) {
                    if (migratedTab != null) state = state.copy(lastTab = migratedTab)
                    proofs += settingsSnapshot.proof(
                        sourceSchemaVersion = legacySettings.sourceSchemaVersion(),
                        destinationSchemaVersion = UI_RESTORE_DATASTORE_SCHEMA_VERSION,
                        importedCounts = mapOf("lastTab" to if (migratedTab == null) 0 else 1),
                    )
                }
            }

            UiRestoreDataStoreFile.fromMemoryState(
                state = state,
                legacyImports = proofs,
            ).also { onImported(it.legacyImports) }
        }
    }

    override suspend fun cleanUp() {
        // Settings owns its own archive because both DataStore migrations may run concurrently.
        archiveVerifiedLegacyFile(legacyUiFile, legacyUiArchiveFile)
    }
}

/**
 * Moves the pre-F05 Search offset map out of the still-live query file. The proof is stored in
 * UI restore DataStore, while cleanup rewrites query_store.json in place so applied queries remain
 * available and the legacy field cannot become a second live scroll store again.
 */
internal class QueryScrollOffsetDataMigration(
    private val queryFile: File,
    private val gson: Gson,
    private val onImported: (List<LegacyImportProof>) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : DataMigration<UiRestoreDataStoreFile> {
    private val fileStore = AtomicJsonFileStore(gson = gson)

    override suspend fun shouldMigrate(currentData: UiRestoreDataStoreFile): Boolean {
        return readQuerySnapshot()?.payload?.has(LEGACY_SCROLL_OFFSETS_FIELD) == true
    }

    override suspend fun migrate(currentData: UiRestoreDataStoreFile): UiRestoreDataStoreFile {
        return migrationAttempt(onFailure) {
            val snapshot = readQuerySnapshot()
                ?: throw IOException("Legacy query offsets disappeared before migration")
            val priorProofExists = currentData.legacyImports.any { proof ->
                proof.sourceFileName == queryFile.name &&
                    LEGACY_SCROLL_IMPORT_COUNT in proof.importedCounts
            }
            if (priorProofExists) return@migrationAttempt currentData

            val offsets = snapshot.readOffsets()
            val state = currentData.toMemoryState()
            val migratedScrollStates = LinkedHashMap(state.scrollStates)
            offsets.forEach { (queryHash, offsetPx) ->
                migratedScrollStates.putIfAbsent(
                    queryHash,
                    SearchScrollState(
                        firstVisibleItemIndex = 0,
                        firstVisibleItemOffsetPx = offsetPx.coerceAtLeast(0),
                    ),
                )
            }
            val proof = snapshot.source.proof(
                sourceSchemaVersion = 1,
                destinationSchemaVersion = UI_RESTORE_DATASTORE_SCHEMA_VERSION,
                importedCounts = mapOf(LEGACY_SCROLL_IMPORT_COUNT to offsets.size),
            )
            UiRestoreDataStoreFile.fromMemoryState(
                state = state.copy(scrollStates = migratedScrollStates),
                legacyImports = currentData.legacyImports + proof,
            ).also { migrated -> onImported(migrated.legacyImports) }
        }
    }

    override suspend fun cleanUp() {
        migrationAttempt(onFailure) {
            val snapshot = readQuerySnapshot() ?: return@migrationAttempt
            if (!snapshot.payload.has(LEGACY_SCROLL_OFFSETS_FIELD)) return@migrationAttempt
            val cleaned = snapshot.payload.deepCopy().apply {
                remove(LEGACY_SCROLL_OFFSETS_FIELD)
            }
            fileStore.write(queryFile, cleaned)
        }
    }

    private fun readQuerySnapshot(): LegacyQuerySnapshot? {
        if (!queryFile.isFile) return null
        val source = LegacyFileSnapshot(
            logicalFileName = queryFile.name,
            actualFile = queryFile,
            bytes = queryFile.readBytes(),
        )
        val payload = source.parse(JsonObject::class.java, gson, "legacy query store")
        return LegacyQuerySnapshot(source = source, payload = payload)
    }
}

private data class LegacyQuerySnapshot(
    val source: LegacyFileSnapshot,
    val payload: JsonObject,
) {
    fun readOffsets(): Map<String, Int> {
        val offsets = payload.get(LEGACY_SCROLL_OFFSETS_FIELD)
            ?.takeIf { element -> element.isJsonObject }
            ?.asJsonObject
            ?: return emptyMap()
        return offsets.entrySet().mapNotNull { (rawHash, element) ->
            val queryHash = rawHash.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val offset = runCatching { element.asInt }.getOrNull() ?: return@mapNotNull null
            queryHash to offset
        }.toMap()
    }
}

private inline fun <T> migrationAttempt(
    onFailure: (Throwable) -> Unit,
    block: () -> T,
): T {
    return try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        onFailure(failure)
        throw failure
    }
}

private fun <T> LegacyFileSnapshot.parse(
    type: Class<T>,
    gson: Gson,
    description: String,
): T {
    return try {
        gson.fromJson(String(bytes, StandardCharsets.UTF_8), type)
            ?: throw IOException("$description did not contain a JSON value")
    } catch (parseFailure: JsonParseException) {
        throw IOException("Could not parse $description from ${actualFile.absolutePath}", parseFailure)
    }
}

private fun LegacyFileSnapshot.proof(
    sourceSchemaVersion: Int,
    destinationSchemaVersion: Int,
    importedCounts: Map<String, Int>,
): LegacyImportProof {
    return LegacyImportProof(
        sourceFileName = logicalFileName,
        sourceSchemaVersion = sourceSchemaVersion,
        destinationSchemaVersion = destinationSchemaVersion,
        sourceSha256 = sha256,
        sourceByteCount = bytes.size.toLong(),
        importedCounts = importedCounts,
    )
}

private const val LEGACY_SCROLL_OFFSETS_FIELD = "scrollOffsets"
private const val LEGACY_SCROLL_IMPORT_COUNT = "searchScrollStates"
