package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.google.gson.Gson
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.data.storage.LegacyRecentsStoreFile
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val RECENTS_MIGRATION_KEY = "legacy_recents_json_to_room_v2"
const val LEGACY_RECENTS_FILE_NAME = "recents_store.json"

data class RecentsMigrationProof(
    val sourceSha256: String,
    val sourceByteCount: Long,
    val sourcePresent: Boolean,
    val sourceQuarantined: Boolean,
    val destinationSha256: String,
    val watchedCount: Int,
    val searchCount: Int,
    val proofSha256: String,
    val completedAtEpochMs: Long,
    val sourceArchived: Boolean,
)

sealed interface RecentsImportResult {
    data class Imported(val proof: RecentsMigrationProof) : RecentsImportResult
    data class AlreadyImported(val proof: RecentsMigrationProof) : RecentsImportResult
    data class SourceChanged(val proof: RecentsMigrationProof, val incomingSha256: String) : RecentsImportResult
    data class DestinationConflict(val watchedCount: Int, val searchCount: Int) : RecentsImportResult
    data class DestinationDrift(val proof: RecentsMigrationProof) : RecentsImportResult
    data class InvalidProof(val reason: String) : RecentsImportResult
    data class ArchiveFailed(val proof: RecentsMigrationProof, val reason: String) : RecentsImportResult
}

/** Imports the last whole-file Recents format before Room-backed route owners become visible. */
class RoomRecentsLegacyImporter(
    private val database: TheoriaRoomDatabase,
    private val recoveryRegistry: LegacyJsonRecoveryRegistry,
    private val gson: Gson = Gson(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.recentsDao()
    private val contentDao = database.codexLikesDao()
    private val postCodec = LocalPostPayloadCodec(gson)
    private val sharedPostPayloads = SharedPostPayloadWriter(contentDao, postCodec)
    private val fileStore = AtomicJsonFileStore(ioDispatcher = ioDispatcher, gson = gson)

    suspend fun importAndArchive(baseDirectory: File): RecentsImportResult {
        val sourceFile = baseDirectory.resolve(LEGACY_RECENTS_FILE_NAME)
        val result = importRowsIfNeeded(sourceFile)
        val proof = when (result) {
            is RecentsImportResult.Imported -> result.proof
            is RecentsImportResult.AlreadyImported -> result.proof
            else -> return result
        }
        if (proof.sourceArchived) return result
        return archiveSource(sourceFile, proof)
    }

    suspend fun importRowsIfNeeded(sourceFile: File): RecentsImportResult {
        recoveryRegistry.registerStore("Recent activity", sourceFile)
        val stored = database.withTransaction { dao.migration(RECENTS_MIGRATION_KEY) }
        val validated = stored?.validateProof()
        if (validated is ProofValidation.Invalid) return RecentsImportResult.InvalidProof(validated.reason)
        val previous = (validated as? ProofValidation.Valid)?.proof
        val destination = database.withTransaction { destinationSummary() }
        if (previous != null && !previous.sourceArchived && destination != previous.destinationSummary()) {
            return RecentsImportResult.DestinationDrift(previous)
        }

        val source = readSource(sourceFile, previous)
        previous?.let { proof -> return source.resultFor(proof) }
        return commitPrepared(source, prepare(source.value))
    }

    private suspend fun commitPrepared(
        source: SourceSnapshot,
        prepared: PreparedRecents,
    ): RecentsImportResult {
        return database.withTransaction {
            val raced = dao.migration(RECENTS_MIGRATION_KEY)
            raced?.let { return@withTransaction it.resultAfterRace() }
            val current = destinationSummary()
            if (!current.isEmpty) {
                return@withTransaction RecentsImportResult.DestinationConflict(
                    current.watchedCount,
                    current.searchCount,
                )
            }
            insertPrepared(prepared)
            contentDao.deleteOrphanPosts()
            val verified = destinationSummary()
            val proof = createProof(source, verified)
            dao.insertMigration(proof.toEntity())
            RecentsImportResult.Imported(proof)
        }
    }

    private fun insertPrepared(prepared: PreparedRecents) {
        prepared.watched.forEachIndexed { index, entry ->
            val post = entry.post
            sharedPostPayloads.upsert(post)
            dao.upsertWatched(
                RecentWatchedEntity(
                    post.id.source.name,
                    post.id.sourcePostId,
                    entry.section.name,
                    entry.viewedAtEpochMs,
                    (prepared.watched.size - index).toLong(),
                    entry.origin.name,
                    entry.originQueryHash,
                    entry.maxViewedMediaNumber.coerceAtLeast(1),
                )
            )
        }
        prepared.searches.forEachIndexed { index, entry ->
            dao.upsertSearch(
                RecentSearchEntity(
                    entry.queryHash,
                    RecentSearchPayloadCodec.encodeJson(entry, gson),
                    entry.searchedAtEpochMs,
                    (prepared.searches.size - index).toLong(),
                )
            )
        }
    }

    private suspend fun archiveSource(sourceFile: File, proof: RecentsMigrationProof): RecentsImportResult {
        if (!proof.sourcePresent || proof.sourceQuarantined) {
            return markArchived(proof)
        }
        val archive = requireNotNull(sourceFile.parentFile).resolve("legacy-json-archive")
            .resolve("${sourceFile.name}.${proof.sourceByteCount}-${proof.sourceSha256}.imported")
        val error = withContext(ioDispatcher) { archiveFailure(sourceFile, archive, proof) }
        return if (error == null) markArchived(proof) else RecentsImportResult.ArchiveFailed(proof, error)
    }

    private fun archiveFailure(
        sourceFile: File,
        archive: File,
        proof: RecentsMigrationProof,
    ): String? = try {
        archivePreflight(sourceFile, archive, proof)?.let { return it }
        if (!archive.exists()) installArchive(sourceFile, archive)
        check(archive.readBytes().sha256() == proof.sourceSha256)
        check(sourceFile.delete() || !sourceFile.exists())
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.message ?: error::class.java.simpleName
    }

    private fun archivePreflight(
        sourceFile: File,
        archive: File,
        proof: RecentsMigrationProof,
    ): String? {
        if (archive.exists() && archive.readBytes().sha256() != proof.sourceSha256) {
            return "archive destination contains different bytes"
        }
        if (sourceFile.exists() && sourceFile.readBytes().sha256() != proof.sourceSha256) {
            return "legacy source changed after Room commit"
        }
        if (!sourceFile.exists() && !archive.exists()) {
            return "source and verified archive are both missing"
        }
        return null
    }

    private fun installArchive(sourceFile: File, archive: File) {
        requireNotNull(archive.parentFile).mkdirs()
        val bytes = sourceFile.readBytes()
        val temp = File.createTempFile("${archive.name}.", ".tmp", archive.parentFile)
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            check(temp.readBytes().contentEquals(bytes))
            try {
                Files.move(temp.toPath(), archive.toPath(), ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), archive.toPath())
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private suspend fun markArchived(proof: RecentsMigrationProof): RecentsImportResult =
        database.withTransaction {
            if (!proof.sourceArchived) check(dao.markArchived(RECENTS_MIGRATION_KEY) == 1)
            val updated = dao.migration(RECENTS_MIGRATION_KEY)!!.validateProof()
            when (updated) {
                is ProofValidation.Invalid -> RecentsImportResult.InvalidProof(updated.reason)
                is ProofValidation.Valid -> RecentsImportResult.AlreadyImported(updated.proof)
            }
        }

    private suspend fun readSource(sourceFile: File, previous: RecentsMigrationProof?): SourceSnapshot {
        if (!sourceFile.exists()) {
            if (previous?.sourceArchived == true) {
                return SourceSnapshot(previous.sourceSha256, previous.sourceByteCount, previous.sourcePresent,
                    previous.sourceQuarantined, LegacyRecentsStoreFile())
            }
            if (previous != null && previous.sourcePresent && !previous.sourceQuarantined) {
                val archive = requireNotNull(sourceFile.parentFile).resolve("legacy-json-archive")
                    .resolve("${sourceFile.name}.${previous.sourceByteCount}-${previous.sourceSha256}.imported")
                if (archive.exists()) {
                    val bytes = withContext(ioDispatcher) { archive.readBytes() }
                    check(bytes.size.toLong() == previous.sourceByteCount && bytes.sha256() == previous.sourceSha256) {
                        "Recents migrated archive does not match its pending proof"
                    }
                    return SourceSnapshot(previous.sourceSha256, previous.sourceByteCount, true, false,
                        LegacyRecentsStoreFile())
                }
            }
            val recoveries = recoveryRegistry.recoveries.value.filter { it.logicalFile == sourceFile.name }
            check(recoveries.map(CorruptionRecovery::sha256).distinct().size <= 1) {
                "Multiple different Recents quarantines require manual recovery"
            }
            val recovery = recoveries.singleOrNull()
            return if (recovery != null) {
                SourceSnapshot(recovery.sha256, recovery.byteCount, true, true, LegacyRecentsStoreFile())
            } else {
                SourceSnapshot(EMPTY_SHA256, 0L, false, false, LegacyRecentsStoreFile())
            }
        }
        val bytes = withContext(ioDispatcher) { sourceFile.readBytes() }
        var recovery: CorruptionRecovery? = null
        val decoded = fileStore.read(
            sourceFile,
            LegacyRecentsStoreFile(),
            logicalStore = "Recent activity",
            onRecovery = { value -> recovery = value; recoveryRegistry.record(value) },
        )
        return SourceSnapshot(bytes.sha256(), bytes.size.toLong(), true, recovery != null, decoded)
    }

    private fun prepare(file: LegacyRecentsStoreFile): PreparedRecents {
        val watched = file.watchedPosts.orEmpty().mapNotNull { it?.toDomainOrNull() }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<RecentPostEntry>> { it.value.viewedAtEpochMs }
                .thenBy { it.index })
            .distinctBy { it.value.post.id }
            .take(DEFAULT_RECENT_WATCHED_LIMIT)
            .map { it.value }
        val searches = file.searches.orEmpty().mapNotNull { it?.toDomainOrNull() }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<RecentSearchEntry>> { it.value.searchedAtEpochMs }
                .thenBy { it.index })
            .distinctBy { it.value.queryHash }
            .take(DEFAULT_RECENT_SEARCH_LIMIT)
            .map { it.value }
        return PreparedRecents(watched, searches)
    }

    private fun destinationSummary(): RecentsDestination {
        val watched = dao.watched()
        val searches = dao.searches()
        val canonical = buildString {
            append("recents-destination-v1\n")
            watched.forEach { row ->
                val post = contentDao.post(row.source, row.sourcePostId)
                    ?: error("Recent watched row has no Post")
                append(gson.toJson(listOf(row.source, row.sourcePostId, post.payloadJson,
                    row.viewedAtEpochMs, row.sortSequence, row.origin, row.originQueryHash))).append('\n')
            }
            searches.forEach { row ->
                append(gson.toJson(listOf(row.queryHash, row.queryPayloadJson,
                    row.searchedAtEpochMs, row.sortSequence))).append('\n')
            }
        }
        return RecentsDestination(canonical.encodeToByteArray().sha256(), watched.size, searches.size)
    }

    private fun createProof(source: SourceSnapshot, destination: RecentsDestination): RecentsMigrationProof {
        val completed = clock()
        val immutable = listOf(RECENTS_MIGRATION_KEY, source.sha256, source.byteCount.toString(),
            source.present.toString(), source.quarantined.toString(), destination.sha256,
            destination.watchedCount.toString(), destination.searchCount.toString(), completed.toString())
            .joinToString("\n")
        return RecentsMigrationProof(source.sha256, source.byteCount, source.present, source.quarantined,
            destination.sha256, destination.watchedCount, destination.searchCount,
            immutable.encodeToByteArray().sha256(), completed,
            sourceArchived = !source.present || source.quarantined)
    }
}

private data class SourceSnapshot(
    val sha256: String,
    val byteCount: Long,
    val present: Boolean,
    val quarantined: Boolean,
    val value: LegacyRecentsStoreFile,
) {
    fun matches(proof: RecentsMigrationProof): Boolean =
        sha256 == proof.sourceSha256 && byteCount == proof.sourceByteCount &&
            present == proof.sourcePresent && quarantined == proof.sourceQuarantined

    fun resultFor(proof: RecentsMigrationProof): RecentsImportResult = if (matches(proof)) {
        RecentsImportResult.AlreadyImported(proof)
    } else {
        RecentsImportResult.SourceChanged(proof, sha256)
    }
}
private data class PreparedRecents(val watched: List<RecentPostEntry>, val searches: List<RecentSearchEntry>)
private data class RecentsDestination(val sha256: String, val watchedCount: Int, val searchCount: Int) {
    val isEmpty: Boolean get() = watchedCount == 0 && searchCount == 0
}

private sealed interface ProofValidation {
    data class Valid(val proof: RecentsMigrationProof) : ProofValidation
    data class Invalid(val reason: String) : ProofValidation
}

private fun RecentsMigrationEntity.validateProof(): ProofValidation {
    if (!sourceSha256.matches(Regex("[0-9a-f]{64}")) ||
        !destinationSha256.matches(Regex("[0-9a-f]{64}")) ||
        !proofSha256.matches(Regex("[0-9a-f]{64}")) || sourceByteCount < 0L ||
        watchedCount < 0 || searchCount < 0 || completedAtEpochMs < 0L
    ) return ProofValidation.Invalid("Recents migration proof fields are invalid")
    val immutable = listOf(RECENTS_MIGRATION_KEY, sourceSha256, sourceByteCount.toString(),
        isSourcePresent.toString(), isSourceQuarantined.toString(), destinationSha256,
        watchedCount.toString(), searchCount.toString(), completedAtEpochMs.toString()).joinToString("\n")
    if (immutable.encodeToByteArray().sha256() != proofSha256) {
        return ProofValidation.Invalid("Recents migration proof checksum is invalid")
    }
    return ProofValidation.Valid(RecentsMigrationProof(sourceSha256, sourceByteCount,
        isSourcePresent, isSourceQuarantined, destinationSha256, watchedCount, searchCount,
        proofSha256, completedAtEpochMs, isSourceArchived))
}

private fun RecentsMigrationEntity.resultAfterRace(): RecentsImportResult = when (val proof = validateProof()) {
    is ProofValidation.Invalid -> RecentsImportResult.InvalidProof(proof.reason)
    is ProofValidation.Valid -> RecentsImportResult.AlreadyImported(proof.proof)
}

private fun RecentsMigrationProof.toEntity() = RecentsMigrationEntity(
    RECENTS_MIGRATION_KEY, sourceSha256, sourceByteCount, sourcePresent, sourceQuarantined,
    destinationSha256, watchedCount, searchCount, proofSha256, completedAtEpochMs, sourceArchived,
)
private fun RecentsMigrationProof.destinationSummary() =
    RecentsDestination(destinationSha256, watchedCount, searchCount)

private fun ByteArray.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it) }
private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
