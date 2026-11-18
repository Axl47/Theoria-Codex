package com.theoriacodex.data.repository

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import com.theoriacodex.domain.source.SiteProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ProfileRestorePendingException(cause: Exception) : IllegalStateException(
    "Restore paused. Reopen Theoria to finish safely. Your existing profiles are preserved.", cause,
)

/**
 * Restore merges by the backup's own identities through a durable intent, so repeating one or retrying
 * after a crash adds nothing twice. Startup must finish [recoverPendingRestore] before exposing
 * repository-backed routes. Nothing already on the device is deleted or overwritten.
 */
class ProfileBackupService(
    private val settings: SettingsRepository,
    private val store: ProfileBackupStore,
    private val savedSearches: SavedSearchRepository,
    private val readingPositions: ReadingPositionRepository,
    private val journalFile: File,
    private val statistics: StatisticsRepository? = null,
    private val writeGuard: ProfileLibraryWriteGuard? = null,
    private val codec: ProfileBackupCodec = ProfileBackupCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val siteProfiles: SiteProfileRepository = InMemorySiteProfileRepository(),
) {
    private val mutex = Mutex()

    suspend fun export(includeRecents: Boolean = true): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!journalFile.exists()) { "Finish the pending restore before making a backup" }
            repeat(3) {
                val initialSettings = settings.observeSettings().first()
                val sites = siteProfiles.observeProfiles().first()
                val pins = savedSearches.observeSavedSearches().first()
                val positions = if (includeRecents) readingPositions.snapshot() else emptyList()
                val library = store.snapshot(includeRecents)
                if (initialSettings == settings.observeSettings().first() &&
                    sites == siteProfiles.observeProfiles().first() &&
                    pins == savedSearches.observeSavedSearches().first() &&
                    (!includeRecents || positions == readingPositions.snapshot())
                ) {
                    return@withLock codec.encode(
                        ProfileBackup(clock(), initialSettings, library, pins, positions, statisticsFor(includeRecents), sites),
                    )
                }
            }
            error("Your library changed while preparing the backup. Try again after current saves finish.")
        }
    }

    /** Usage totals change constantly and share nothing with the library, so they need no stable read. */
    private suspend fun statisticsFor(includeRecents: Boolean): LifetimeStatistics? =
        if (includeRecents) statistics?.observeStatistics()?.first() else null

    fun preview(bytes: ByteArray): ProfileBackupPreview = codec.decode(bytes).preview()

    suspend fun restore(
        bytes: ByteArray,
        applyPreferences: Boolean = false,
        selectImportedProfile: Boolean = false,
    ): ProfileRestoreResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val backup = codec.decode(bytes)
                if (journalFile.exists()) {
                    val pending = readJournal()
                    require(codec.encode(pending.backup).contentEquals(codec.encode(backup)) &&
                        pending.applyPreferences == applyPreferences &&
                        pending.selectImportedProfile == selectImportedProfile) { "Finish the pending restore first" }
                    return@withLock finish(pending)
                }
                val pending = PendingRestore(applyPreferences, backup, selectImportedProfile)
                preflight(backup, applyPreferences)
                preflightSites(backup)
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

    /**
     * Checked only before the journal exists: [finish] also runs during startup recovery, and a site
     * added since must never make recovery fail. Merging may therefore exceed the limit slightly.
     */
    private suspend fun preflightSites(imported: ProfileBackup) {
        val existing = siteProfiles.observeProfiles().first().mapTo(mutableSetOf(), SiteProfile::sourceId)
        val added = imported.customSources.map(SiteProfile::sourceId).filterNot(existing::contains)
        require(added.isEmpty() || existing.size + added.size <= SiteProfile.MAX_CUSTOM_SITES) {
            "Restore would exceed the ${SiteProfile.MAX_CUSTOM_SITES}-site limit. Remove some sites first."
        }
    }

    private suspend fun finish(pending: PendingRestore): ProfileRestoreResult = withContext(NonCancellable) {
        try {
            val imported = pending.legacyOperationId?.let(pending.backup::forLegacyRestore) ?: pending.backup
            preflight(imported, pending.applyPreferences)
            val existingProfileIds = settings.observeSettings().first().recommendationProfiles.mapTo(mutableSetOf()) { it.profileId }
            val collectionsAdded = store.merge(imported.library)
            savedSearches.merge(imported.savedSearches)
            readingPositions.merge(imported.readingPositions)
            val sites = siteProfiles.mergeRestored(imported.customSources)
            imported.statistics?.let { statistics?.mergeRestored(it) }
            settings.updateSettings {
                val merged = it.mergeRestoredSettings(imported.settings, pending.applyPreferences)
                if (pending.selectImportedProfile) merged.copy(activeProfileId = imported.settings.activeProfileId) else merged
            }
            val persistedIds = settings.observeSettings().first().recommendationProfiles.mapTo(mutableSetOf()) { it.profileId }
            check(imported.settings.recommendationProfiles.all { it.profileId in persistedIds }) {
                "Restored profiles were not retained"
            }
            // A profile deleted earlier in this process is back, so it must be usable before a restart.
            imported.settings.recommendationProfiles.forEach { writeGuard?.reinstate(it.profileId) }
            check(journalFile.delete() || !journalFile.exists()) { "Could not finish the restore journal" }
            ProfileRestoreResult(
                profilesAdded = imported.settings.recommendationProfiles.count { it.profileId !in existingProfileIds },
                collectionsAdded = collectionsAdded,
                keptExistingSites = sites.keptExisting,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ProfileRestorePendingException(failure)
        }
    }

    private fun writeJournal(pending: PendingRestore) {
        val record = buildJsonObject {
            put("applyPreferences", pending.applyPreferences)
            put("selectImportedProfile", pending.selectImportedProfile)
            put("backup", codec.encodeDocument(pending.backup))
        }.toString().toByteArray(Charsets.UTF_8)
        // Never write a journal that readJournal would refuse, or startup recovery could not finish it.
        require(record.size <= MAX_JOURNAL_BYTES) { "Backup exceeds the 32 MiB metadata limit" }
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
        require(journalFile.length() <= MAX_JOURNAL_BYTES) { "Restore journal is too large" }
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(journalFile.readBytes())).toString()
        checkDocumentDepth(raw)
        val record = parseBackupObject(raw)
        val preferences = requireNotNull(record["applyPreferences"])
        require(preferences.isBackupBoolean())
        val legacyOperationId = record["operationId"]?.let { record.backupString("operationId") }
        require(legacyOperationId == null || legacyOperationId.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid restore operation" }
        val backup = codec.decodeDocument(record.backupObject("backup"))
        val selection = record["selectImportedProfile"]
        require(selection == null || selection.isBackupBoolean())
        return PendingRestore(preferences.isTrue(), backup, selection?.isTrue() ?: false, legacyOperationId)
    }
}

private const val MAX_JOURNAL_BYTES = PROFILE_BACKUP_MAX_BYTES + 4_096L

/** A JSON true; callers have already checked [isBackupBoolean]. */
private fun JsonElement.isTrue() = jsonPrimitive.boolean

private data class PendingRestore(
    val applyPreferences: Boolean,
    val backup: ProfileBackup,
    val selectImportedProfile: Boolean = false,
    val legacyOperationId: String? = null,
)
