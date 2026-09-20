package com.theoriacodex.data.repository

import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProfileRestorePendingException(cause: Exception) : IllegalStateException(
    "Restore paused. Reopen Theoria to finish safely. Your existing profiles are preserved.", cause,
)

/**
 * Additive cross-store restore uses a durable intent and idempotent commits. Startup must finish
 * [recoverPendingRestore] before exposing repository-backed routes. No existing profile is deleted.
 */
class ProfileBackupService(
    private val settings: SettingsRepository,
    private val store: ProfileBackupStore,
    private val savedSearches: SavedSearchRepository,
    private val readingPositions: ReadingPositionRepository,
    private val journalFile: File,
    private val codec: ProfileBackupCodec = ProfileBackupCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    suspend fun export(includeRecents: Boolean = true): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!journalFile.exists()) { "Finish the pending restore before making a backup" }
            repeat(3) {
                val initialSettings = settings.observeSettings().first()
                val pins = savedSearches.observeSavedSearches().first()
                val positions = if (includeRecents) readingPositions.snapshot() else emptyList()
                val library = store.snapshot(includeRecents)
                if (initialSettings == settings.observeSettings().first() &&
                    pins == savedSearches.observeSavedSearches().first() &&
                    (!includeRecents || positions == readingPositions.snapshot())
                ) {
                    return@withLock codec.encode(ProfileBackup(clock(), initialSettings, library, pins, positions))
                }
            }
            error("Your library changed while preparing the backup. Try again after current saves finish.")
        }
    }

    fun preview(bytes: ByteArray): ProfileBackupPreview = codec.decode(bytes).preview()

    suspend fun restore(bytes: ByteArray, applyPreferences: Boolean = false): ProfileRestoreResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val backup = codec.decode(bytes)
                if (journalFile.exists()) {
                    val pending = readJournal()
                    require(codec.encode(pending.backup).contentEquals(codec.encode(backup)) &&
                        pending.applyPreferences == applyPreferences) { "Finish the pending restore first" }
                    return@withLock finish(pending)
                }
                val pending = PendingRestore(newOperationId(), applyPreferences, backup)
                val imported = backup.forRestore(pending.operationId)
                preflight(imported, applyPreferences)
                withContext(NonCancellable) {
                    writeJournal(pending)
                    finish(pending)
                }
            }
        }

    suspend fun recoverPendingRestore(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!journalFile.exists()) return@withLock false
            finish(readJournal())
            true
        }
    }

    private suspend fun preflight(imported: ProfileBackup, applyPreferences: Boolean) {
        settings.observeSettings().first().mergeRestoredSettings(imported.settings, applyPreferences)
        val existingPins = savedSearches.observeSavedSearches().first().mapTo(mutableSetOf()) { it.id }
        require((existingPins + imported.savedSearches.map { it.id }).size <= MAX_SAVED_SEARCHES) {
            "Restore would exceed the $MAX_SAVED_SEARCHES pinned-search limit. Remove some pins first."
        }
    }

    private suspend fun finish(pending: PendingRestore): ProfileRestoreResult = withContext(NonCancellable) {
        try {
            val imported = pending.backup.forRestore(pending.operationId)
            preflight(imported, pending.applyPreferences)
            store.merge(imported.library)
            savedSearches.merge(imported.savedSearches)
            readingPositions.merge(imported.readingPositions)
            settings.updateSettings { it.mergeRestoredSettings(imported.settings, pending.applyPreferences) }
            check(journalFile.delete() || !journalFile.exists()) { "Could not finish the restore journal" }
            ProfileRestoreResult(imported.settings.recommendationProfiles.size, imported.library.codices.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ProfileRestorePendingException(failure)
        }
    }

    private fun writeJournal(pending: PendingRestore) {
        val record = backupObject {
            addProperty("operationId", pending.operationId)
            addProperty("applyPreferences", pending.applyPreferences)
            add("backup", JsonParser.parseString(codec.encode(pending.backup).toString(Charsets.UTF_8)))
        }.toString().toByteArray(Charsets.UTF_8)
        journalFile.parentFile?.mkdirs()
        val temporary = File.createTempFile("restore-", ".tmp", journalFile.parentFile)
        try {
            FileOutputStream(temporary).use { output -> output.write(record); output.fd.sync() }
            Files.move(temporary.toPath(), journalFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun readJournal(): PendingRestore {
        require(journalFile.length() <= PROFILE_BACKUP_MAX_BYTES + 4_096L) { "Restore journal is too large" }
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(journalFile.readBytes())).toString()
        checkBackupDepth(raw)
        val record = JsonParser.parseString(raw).asJsonObject
        val operationId = record.backupString("operationId")
        require(operationId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid restore operation" }
        val preferences = requireNotNull(record.get("applyPreferences"))
        require(preferences.isJsonPrimitive && preferences.asJsonPrimitive.isBoolean)
        val backup = codec.decode(requireNotNull(record.get("backup")).toString().toByteArray(Charsets.UTF_8))
        return PendingRestore(operationId, preferences.asBoolean, backup)
    }
}

private data class PendingRestore(val operationId: String, val applyPreferences: Boolean, val backup: ProfileBackup)
