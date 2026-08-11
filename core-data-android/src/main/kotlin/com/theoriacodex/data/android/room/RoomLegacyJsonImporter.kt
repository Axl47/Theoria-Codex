package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.google.gson.Gson
import com.theoriacodex.data.storage.archiveVerifiedLegacyFile
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val LEGACY_MIGRATION_KEY = "legacy_codex_likes_json_v1"

data class LegacyMigrationProof(
    val sourceFingerprintSha256: String,
    val codexFileSha256: String,
    val likesFileSha256: String,
    val destinationFingerprintSha256: String,
    val proofSha256: String,
    val codexSourcePresent: Boolean,
    val likesSourcePresent: Boolean,
    val codexCount: Int,
    val postCount: Int,
    val itemCount: Int,
    val likeCount: Int,
    val completedAtEpochMs: Long,
    val codexArchived: Boolean,
    val likesArchived: Boolean,
)

data class LegacySourceIdentity(
    val sourceFingerprintSha256: String,
    val codexFileSha256: String,
    val likesFileSha256: String,
    val codexSourcePresent: Boolean,
    val likesSourcePresent: Boolean,
)

data class LegacySourceSummary(
    val identity: LegacySourceIdentity,
    val codexCount: Int,
    val postCount: Int,
    val itemCount: Int,
    val likeCount: Int,
)

data class LegacyDestinationSummary(
    val fingerprintSha256: String,
    val codexCount: Int,
    val postCount: Int,
    val itemCount: Int,
    val likeCount: Int,
) {
    val isEmpty: Boolean
        get() = codexCount == 0 && postCount == 0 && itemCount == 0 && likeCount == 0
}

sealed interface LegacyJsonImportResult {
    data class Imported(val proof: LegacyMigrationProof) : LegacyJsonImportResult
    data class AdoptedVerifiedDestination(val proof: LegacyMigrationProof) : LegacyJsonImportResult
    data class AlreadyImported(val proof: LegacyMigrationProof) : LegacyJsonImportResult

    /** Both stores are preserved; neither side is overwritten when their source identities differ. */
    data class SplitBrain(
        val importedProof: LegacyMigrationProof,
        val incomingSource: LegacySourceIdentity,
    ) : LegacyJsonImportResult

    /** A marker-free non-empty destination is never merged with a different legacy snapshot. */
    data class DestinationConflict(
        val incomingSource: LegacySourceSummary,
        val destination: LegacyDestinationSummary,
    ) : LegacyJsonImportResult

    /** The verified import marker remains intact, but Room no longer matches its destination. */
    data class DestinationDrift(
        val importedProof: LegacyMigrationProof,
        val destination: LegacyDestinationSummary,
    ) : LegacyJsonImportResult

    data class InvalidStoredProof(val reason: String) : LegacyJsonImportResult
}

sealed interface LegacyArchiveResult {
    data object NotImported : LegacyArchiveResult
    data class InvalidStoredProof(val reason: String) : LegacyArchiveResult
    data class Complete(val proof: LegacyMigrationProof) : LegacyArchiveResult
    data class Partial(
        val proof: LegacyMigrationProof,
        val blockedFile: String,
        val reason: String,
    ) : LegacyArchiveResult
}

class LegacyJsonMigrationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class LegacyDestinationVerificationException(message: String) : IllegalStateException(message)

/**
 * One-time importer for the legacy `codex_store.json` and `likes_store.json` pair.
 *
 * Both files are read and validated before SQLite is touched. Their rows and a checksum/count
 * proof then commit in one Room transaction. If the process stops before commit, SQLite rolls the
 * whole attempt back and the same files can be retried. After commit, the stored proof makes Room
 * authoritative and a changed old file is reported instead of merged silently.
 *
 * Import and archival are deliberately separate steps. After a verified import, the composition
 * root should call [archiveImportedSources] before exposing live writes. Archival records each
 * source independently, so a partial filesystem failure can be retried without replaying data.
 */
class RoomLegacyJsonImporter(
    private val database: TheoriaRoomDatabase,
    private val gson: Gson = Gson(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxSourceBytes: Long = DEFAULT_MAX_SOURCE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.codexLikesDao()
    private val preparer = LegacyDataPreparer(gson, clock)

    init {
        require(maxSourceBytes in 1L..Int.MAX_VALUE.toLong()) {
            "Legacy import size limit must fit a positive in-memory byte array"
        }
    }

    suspend fun importIfNeeded(baseDirectory: File): LegacyJsonImportResult {
        return importIfNeeded(
            codexFile = baseDirectory.resolve(LEGACY_CODEX_FILE_NAME),
            likesFile = baseDirectory.resolve(LEGACY_LIKES_FILE_NAME),
        )
    }

    suspend fun importIfNeeded(
        codexFile: File,
        likesFile: File,
    ): LegacyJsonImportResult {
        val start = resolveImportStart()
        start.terminalResult?.let { return it }
        val previousProof = start.previousProof
        val source = withContext(ioDispatcher) {
            readSourcePair(codexFile, likesFile, previousProof)
        }
        if (previousProof != null) {
            return resultForExistingProof(previousProof, source.identity)
        }
        val prepared = withContext(ioDispatcher) { preparer.prepare(source) }
        return commitPreparedImport(source.identity, prepared)
    }

    private suspend fun resolveImportStart(): ImportStart {
        val validation = database.withTransaction {
            dao.migrationMetadata(LEGACY_MIGRATION_KEY)?.validateProof()
        }
        if (validation == null) return ImportStart()
        if (validation is StoredProofValidation.Invalid) {
            return ImportStart(
                terminalResult = LegacyJsonImportResult.InvalidStoredProof(validation.reason)
            )
        }
        val proof = (validation as StoredProofValidation.Valid).proof
        if (proof.isFullyArchived) return ImportStart(previousProof = proof)
        val currentDestination = database.withTransaction { destinationSummary() }
        return if (currentDestination.matches(proof)) {
            ImportStart(previousProof = proof)
        } else {
            ImportStart(
                terminalResult = LegacyJsonImportResult.DestinationDrift(proof, currentDestination)
            )
        }
    }

    private fun resultForExistingProof(
        proof: LegacyMigrationProof,
        source: LegacySourceIdentity,
    ): LegacyJsonImportResult {
        return if (proof.sourceFingerprintSha256 == source.sourceFingerprintSha256) {
            LegacyJsonImportResult.AlreadyImported(proof)
        } else {
            LegacyJsonImportResult.SplitBrain(proof, source)
        }
    }

    private suspend fun commitPreparedImport(
        source: LegacySourceIdentity,
        prepared: PreparedLegacyData,
    ): LegacyJsonImportResult = database.withTransaction {
        val completed = dao.migrationMetadata(LEGACY_MIGRATION_KEY)
        if (completed != null) {
            return@withTransaction resultForConcurrentCompletion(completed, source)
        }
        commitPreparedDestination(source, prepared)
    }

    private fun resultForConcurrentCompletion(
        completed: MigrationMetadataEntity,
        source: LegacySourceIdentity,
    ): LegacyJsonImportResult {
        return when (val validation = completed.validateProof()) {
            is StoredProofValidation.Invalid -> {
                LegacyJsonImportResult.InvalidStoredProof(validation.reason)
            }
            is StoredProofValidation.Valid -> resultForValidatedCompletion(validation.proof, source)
        }
    }

    private fun resultForValidatedCompletion(
        proof: LegacyMigrationProof,
        source: LegacySourceIdentity,
    ): LegacyJsonImportResult {
        val currentDestination = destinationSummary()
        if (!proof.isFullyArchived && !currentDestination.matches(proof)) {
            return LegacyJsonImportResult.DestinationDrift(proof, currentDestination)
        }
        return resultForExistingProof(proof, source)
    }

    private fun commitPreparedDestination(
        source: LegacySourceIdentity,
        prepared: PreparedLegacyData,
    ): LegacyJsonImportResult {
        val expectedDestination = prepared.destinationSummary()
        val currentDestination = destinationSummary()
        if (!currentDestination.isEmpty && currentDestination != expectedDestination) {
            return LegacyJsonImportResult.DestinationConflict(
                incomingSource = prepared.sourceSummary(source),
                destination = currentDestination,
            )
        }
        val adoptedExistingDestination = !currentDestination.isEmpty
        if (!adoptedExistingDestination) insertPrepared(prepared)
        val verifiedDestination = destinationSummary()
        verifyPreparedDestination(verifiedDestination, expectedDestination)
        val proof = createProof(source, verifiedDestination, clock())
        dao.insertMigrationMetadata(proof.toEntity())
        return if (adoptedExistingDestination) {
            LegacyJsonImportResult.AdoptedVerifiedDestination(proof)
        } else {
            LegacyJsonImportResult.Imported(proof)
        }
    }

    private fun verifyPreparedDestination(
        actual: LegacyDestinationSummary,
        expected: LegacyDestinationSummary,
    ) {
        if (actual == expected) return
        throw LegacyDestinationVerificationException(
            "Room destination ${actual.fingerprintSha256} does not match " +
                "prepared legacy destination ${expected.fingerprintSha256}"
        )
    }

    suspend fun archiveImportedSources(
        baseDirectory: File,
        archiveDirectory: File = baseDirectory.resolve(DEFAULT_ARCHIVE_DIRECTORY_NAME),
    ): LegacyArchiveResult {
        var proof = when (val validation = database.withTransaction {
            dao.migrationMetadata(LEGACY_MIGRATION_KEY)?.validateProof()
        }) {
            null -> return LegacyArchiveResult.NotImported
            is StoredProofValidation.Invalid -> {
                return LegacyArchiveResult.InvalidStoredProof(validation.reason)
            }
            is StoredProofValidation.Valid -> validation.proof
        }
        if (!proof.isFullyArchived) {
            val destination = database.withTransaction { destinationSummary() }
            if (!destination.matches(proof)) {
                return LegacyArchiveResult.Partial(
                    proof = proof,
                    blockedFile = "Room destination",
                    reason = "destination no longer matches the verified import proof",
                )
            }
        }

        when (
            val outcome = withContext(ioDispatcher) {
                archiveOne(
                    source = baseDirectory.resolve(LEGACY_CODEX_FILE_NAME),
                    archiveDirectory = archiveDirectory,
                    sourceWasPresent = proof.codexSourcePresent,
                    expectedChecksum = proof.codexFileSha256,
                )
            }
        ) {
            ArchiveFileOutcome.Success -> {
                if (!proof.codexArchived) {
                    proof = updateArchiveState(codexArchived = true, likesArchived = proof.likesArchived)
                }
            }
            is ArchiveFileOutcome.Blocked -> {
                return LegacyArchiveResult.Partial(proof, LEGACY_CODEX_FILE_NAME, outcome.reason)
            }
        }

        when (
            val outcome = withContext(ioDispatcher) {
                archiveOne(
                    source = baseDirectory.resolve(LEGACY_LIKES_FILE_NAME),
                    archiveDirectory = archiveDirectory,
                    sourceWasPresent = proof.likesSourcePresent,
                    expectedChecksum = proof.likesFileSha256,
                )
            }
        ) {
            ArchiveFileOutcome.Success -> {
                if (!proof.likesArchived) {
                    proof = updateArchiveState(codexArchived = proof.codexArchived, likesArchived = true)
                }
            }
            is ArchiveFileOutcome.Blocked -> {
                return LegacyArchiveResult.Partial(proof, LEGACY_LIKES_FILE_NAME, outcome.reason)
            }
        }

        return LegacyArchiveResult.Complete(proof)
    }

    private fun insertPrepared(prepared: PreparedLegacyData) {
        prepared.codices.forEach { entity ->
            check(dao.insertCodex(entity) != -1L) { "Prepared Codex unexpectedly collided" }
        }
        prepared.posts.forEach { entity ->
            check(dao.insertPost(entity) != -1L) { "Prepared Post unexpectedly collided" }
        }
        prepared.items.forEach { entity ->
            check(dao.insertCodexItem(entity) != -1L) { "Prepared Codex membership unexpectedly collided" }
        }
        prepared.likes.forEach { entity ->
            check(dao.insertLike(entity) != -1L) { "Prepared Like unexpectedly collided" }
        }
    }

    private fun destinationSummary(): LegacyDestinationSummary {
        return summarizeDestination(
            codices = dao.codices(),
            posts = dao.posts(),
            items = dao.codexItems(),
            likes = dao.allLikes(),
        )
    }

    private fun createProof(
        source: LegacySourceIdentity,
        destination: LegacyDestinationSummary,
        completedAtEpochMs: Long,
    ): LegacyMigrationProof {
        val immutableProof = ImmutableProofFields(
            source = source,
            destination = destination,
            completedAtEpochMs = completedAtEpochMs,
        )
        return LegacyMigrationProof(
            sourceFingerprintSha256 = source.sourceFingerprintSha256,
            codexFileSha256 = source.codexFileSha256,
            likesFileSha256 = source.likesFileSha256,
            destinationFingerprintSha256 = destination.fingerprintSha256,
            proofSha256 = immutableProof.proofSha256(),
            codexSourcePresent = source.codexSourcePresent,
            likesSourcePresent = source.likesSourcePresent,
            codexCount = destination.codexCount,
            postCount = destination.postCount,
            itemCount = destination.itemCount,
            likeCount = destination.likeCount,
            completedAtEpochMs = completedAtEpochMs,
            codexArchived = !source.codexSourcePresent,
            likesArchived = !source.likesSourcePresent,
        )
    }

    private suspend fun updateArchiveState(
        codexArchived: Boolean,
        likesArchived: Boolean,
    ): LegacyMigrationProof {
        return database.withTransaction {
            check(
                dao.updateMigrationArchiveState(LEGACY_MIGRATION_KEY, codexArchived, likesArchived) == 1
            ) { "Legacy migration proof disappeared during archive" }
            when (val validation = dao.migrationMetadata(LEGACY_MIGRATION_KEY)?.validateProof()) {
                is StoredProofValidation.Valid -> validation.proof
                is StoredProofValidation.Invalid -> {
                    throw LegacyJsonMigrationException(validation.reason)
                }
                null -> throw LegacyJsonMigrationException("Legacy migration proof disappeared")
            }
        }
    }

    private fun archiveOne(
        source: File,
        archiveDirectory: File,
        sourceWasPresent: Boolean,
        expectedChecksum: String,
    ): ArchiveFileOutcome {
        return try {
            archiveOneUnchecked(source, archiveDirectory, sourceWasPresent, expectedChecksum)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ArchiveFileOutcome.Blocked("archive operation failed: ${error::class.java.simpleName}")
        }
    }

    private fun archiveOneUnchecked(
        source: File,
        archiveDirectory: File,
        sourceWasPresent: Boolean,
        expectedChecksum: String,
    ): ArchiveFileOutcome {
        if (!sourceWasPresent) return ArchiveFileOutcome.Success
        val archiveFile = archiveDirectory.resolve(
            "${source.name}.${expectedChecksum.take(16)}.imported"
        )
        archiveIntegrityFailure(source, archiveFile, expectedChecksum)?.let { return it }
        if (!source.exists()) return missingSourceOutcome(archiveFile)
        if (archiveFile.exists()) return removeSourceWithVerifiedArchive(source)
        archiveVerifiedLegacyFile(source, archiveFile)
        return verifyArchiveMove(source, archiveFile, expectedChecksum)
    }

    private fun archiveIntegrityFailure(
        source: File,
        archiveFile: File,
        expectedChecksum: String,
    ): ArchiveFileOutcome.Blocked? {
        if (archiveFile.exists() && fileChecksum(archiveFile) != expectedChecksum) {
            return ArchiveFileOutcome.Blocked("archive destination exists with different content")
        }
        if (source.exists() && fileChecksum(source) != expectedChecksum) {
            return ArchiveFileOutcome.Blocked("legacy source changed after its migration proof")
        }
        return null
    }

    private fun missingSourceOutcome(archiveFile: File): ArchiveFileOutcome {
        return if (archiveFile.exists()) {
            ArchiveFileOutcome.Success
        } else {
            ArchiveFileOutcome.Blocked("source and verified archive are both missing")
        }
    }

    private fun removeSourceWithVerifiedArchive(source: File): ArchiveFileOutcome {
        return if (source.delete()) {
            ArchiveFileOutcome.Success
        } else {
            ArchiveFileOutcome.Blocked("verified archive exists but source could not be removed")
        }
    }

    private fun verifyArchiveMove(
        source: File,
        archiveFile: File,
        expectedChecksum: String,
    ): ArchiveFileOutcome {
        val verified = !source.exists() &&
            archiveFile.exists() &&
            fileChecksum(archiveFile) == expectedChecksum
        return if (verified) {
            ArchiveFileOutcome.Success
        } else {
            ArchiveFileOutcome.Blocked("archive move completed without a verifiable destination")
        }
    }

    private fun readSourcePair(
        codexFile: File,
        likesFile: File,
        previousProof: LegacyMigrationProof?,
    ): LegacySourcePair {
        val codex = readBounded(codexFile)
        val likes = readBounded(likesFile)
        val codexIdentity = sourceComponentIdentity(
            source = codex,
            originalPresent = previousProof?.codexSourcePresent,
            originalChecksum = previousProof?.codexFileSha256,
        )
        val likesIdentity = sourceComponentIdentity(
            source = likes,
            originalPresent = previousProof?.likesSourcePresent,
            originalChecksum = previousProof?.likesFileSha256,
        )
        val fingerprint = sourceFingerprint(codexIdentity.checksum, likesIdentity.checksum)
        return LegacySourcePair(
            codex = codex,
            likes = likes,
            identity = LegacySourceIdentity(
                sourceFingerprintSha256 = fingerprint,
                codexFileSha256 = codexIdentity.checksum,
                likesFileSha256 = likesIdentity.checksum,
                codexSourcePresent = codexIdentity.present,
                likesSourcePresent = likesIdentity.present,
            ),
        )
    }

    private fun sourceComponentIdentity(
        source: LegacySource,
        originalPresent: Boolean?,
        originalChecksum: String?,
    ): SourceComponentIdentity {
        // A crash can move the file before the Room archive flag commits. Preserve the proven
        // source identity here; archiveImportedSources still requires the live file or its exact
        // checksum-addressed archive before readiness can succeed.
        if (!source.exists && originalPresent != null && originalChecksum != null) {
            return SourceComponentIdentity(originalPresent, originalChecksum)
        }
        return SourceComponentIdentity(source.exists, sourceChecksum(source))
    }

    private fun readBounded(file: File): LegacySource {
        if (!file.exists()) return LegacySource(exists = false, bytes = byteArrayOf())
        val size = file.length()
        if (size > maxSourceBytes) {
            throw LegacyJsonMigrationException(
                "${file.name} is $size bytes; legacy import limit is $maxSourceBytes bytes"
            )
        }
        val output = ByteArrayOutputStream(minOf(size, maxSourceBytes).toInt())
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxSourceBytes) {
                    throw LegacyJsonMigrationException(
                        "${file.name} grew beyond the $maxSourceBytes-byte legacy import limit while being read"
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        return LegacySource(exists = true, bytes = output.toByteArray())
    }

    private fun sourceChecksum(source: LegacySource): String {
        val prefix = if (source.exists) "present\n" else "missing\n"
        return sha256(prefix.encodeToByteArray() + source.bytes)
    }

    private fun fileChecksum(file: File): String {
        return sourceChecksum(readBounded(file))
    }

    private fun sourceFingerprint(codexChecksum: String, likesChecksum: String): String {
        return legacySourceFingerprint(codexChecksum, likesChecksum)
    }

    companion object {
        const val LEGACY_CODEX_FILE_NAME: String = "codex_store.json"
        const val LEGACY_LIKES_FILE_NAME: String = "likes_store.json"
        const val DEFAULT_ARCHIVE_DIRECTORY_NAME: String = "legacy-json-archive"
        const val DEFAULT_MAX_SOURCE_BYTES: Long = 32L * 1024L * 1024L
    }
}

private data class ImportStart(
    val previousProof: LegacyMigrationProof? = null,
    val terminalResult: LegacyJsonImportResult? = null,
)

internal data class LegacySource(
    val exists: Boolean,
    val bytes: ByteArray,
)

internal data class LegacySourcePair(
    val codex: LegacySource,
    val likes: LegacySource,
    val identity: LegacySourceIdentity,
)

internal data class PreparedLegacyData(
    val codices: List<CodexEntity>,
    val posts: List<PostEntity>,
    val items: List<CodexItemEntity>,
    val likes: List<LikedPostEntity>,
) {
    fun destinationSummary(): LegacyDestinationSummary {
        return summarizeDestination(codices, posts, items, likes)
    }

    fun sourceSummary(identity: LegacySourceIdentity): LegacySourceSummary {
        return LegacySourceSummary(
            identity = identity,
            codexCount = codices.size,
            postCount = posts.size,
            itemCount = items.size,
            likeCount = likes.size,
        )
    }
}

private data class SourceComponentIdentity(
    val present: Boolean,
    val checksum: String,
)

private sealed interface ArchiveFileOutcome {
    data object Success : ArchiveFileOutcome
    data class Blocked(val reason: String) : ArchiveFileOutcome
}

private fun LegacyDestinationSummary.matches(proof: LegacyMigrationProof): Boolean {
    return fingerprintSha256 == proof.destinationFingerprintSha256 &&
        codexCount == proof.codexCount &&
        postCount == proof.postCount &&
        itemCount == proof.itemCount &&
        likeCount == proof.likeCount
}

private val LegacyMigrationProof.isFullyArchived: Boolean
    get() = codexArchived && likesArchived
