package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.theoriacodex.data.repository.CodexLikesPolicy
import com.theoriacodex.data.storage.CURRENT_POST_STORAGE_SCHEMA_VERSION
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.data.storage.archiveVerifiedLegacyFile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
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
    private val postCodec = LocalPostPayloadCodec(gson)

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
        val storedProof = database.withTransaction {
            dao.migrationMetadata(LEGACY_MIGRATION_KEY)?.validateProof()
        }
        if (storedProof is StoredProofValidation.Invalid) {
            return LegacyJsonImportResult.InvalidStoredProof(storedProof.reason)
        }
        val previousProof = (storedProof as? StoredProofValidation.Valid)?.proof
        if (previousProof != null && !previousProof.isFullyArchived) {
            val currentDestination = database.withTransaction { destinationSummary() }
            if (!currentDestination.matches(previousProof)) {
                return LegacyJsonImportResult.DestinationDrift(previousProof, currentDestination)
            }
        }
        val source = withContext(ioDispatcher) {
            readSourcePair(codexFile, likesFile, previousProof)
        }
        if (previousProof != null) {
            return if (previousProof.sourceFingerprintSha256 == source.identity.sourceFingerprintSha256) {
                LegacyJsonImportResult.AlreadyImported(previousProof)
            } else {
                LegacyJsonImportResult.SplitBrain(
                    importedProof = previousProof,
                    incomingSource = source.identity,
                )
            }
        }
        val prepared = withContext(ioDispatcher) { prepare(source) }

        return database.withTransaction {
            val completed = dao.migrationMetadata(LEGACY_MIGRATION_KEY)
            if (completed != null) {
                return@withTransaction when (val validation = completed.validateProof()) {
                    is StoredProofValidation.Invalid -> {
                        LegacyJsonImportResult.InvalidStoredProof(validation.reason)
                    }
                    is StoredProofValidation.Valid -> {
                        val currentDestination = destinationSummary()
                        if (
                            !validation.proof.isFullyArchived &&
                            !currentDestination.matches(validation.proof)
                        ) {
                            LegacyJsonImportResult.DestinationDrift(
                                validation.proof,
                                currentDestination,
                            )
                        } else if (
                            validation.proof.sourceFingerprintSha256 ==
                            source.identity.sourceFingerprintSha256
                        ) {
                            LegacyJsonImportResult.AlreadyImported(validation.proof)
                        } else {
                            LegacyJsonImportResult.SplitBrain(validation.proof, source.identity)
                        }
                    }
                }
            }

            val expectedDestination = prepared.destinationSummary()
            val currentDestination = destinationSummary()
            if (!currentDestination.isEmpty && currentDestination != expectedDestination) {
                return@withTransaction LegacyJsonImportResult.DestinationConflict(
                    incomingSource = prepared.sourceSummary(source.identity),
                    destination = currentDestination,
                )
            }
            val adoptedExistingDestination = !currentDestination.isEmpty
            if (!adoptedExistingDestination) {
                insertPrepared(prepared)
            }
            val verifiedDestination = destinationSummary()
            if (verifiedDestination != expectedDestination) {
                throw LegacyDestinationVerificationException(
                    "Room destination ${verifiedDestination.fingerprintSha256} does not match " +
                        "prepared legacy destination ${expectedDestination.fingerprintSha256}"
                )
            }

            val proof = createProof(
                source = source.identity,
                destination = verifiedDestination,
                completedAtEpochMs = clock(),
            )
            dao.insertMigrationMetadata(proof.toEntity())
            if (adoptedExistingDestination) {
                LegacyJsonImportResult.AdoptedVerifiedDestination(proof)
            } else {
                LegacyJsonImportResult.Imported(proof)
            }
        }
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

        if (archiveFile.exists() && fileChecksum(archiveFile) != expectedChecksum) {
            return ArchiveFileOutcome.Blocked("archive destination exists with different content")
        }
        if (source.exists() && fileChecksum(source) != expectedChecksum) {
            return ArchiveFileOutcome.Blocked("legacy source changed after its migration proof")
        }
        if (!source.exists()) {
            return if (archiveFile.exists()) {
                ArchiveFileOutcome.Success
            } else {
                ArchiveFileOutcome.Blocked("source and verified archive are both missing")
            }
        }

        if (archiveFile.exists()) {
            return if (source.delete()) {
                ArchiveFileOutcome.Success
            } else {
                ArchiveFileOutcome.Blocked("verified archive exists but source could not be removed")
            }
        }

        archiveVerifiedLegacyFile(source, archiveFile)
        return if (
            !source.exists() && archiveFile.exists() && fileChecksum(archiveFile) == expectedChecksum
        ) {
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

    private fun prepare(source: LegacySourcePair): PreparedLegacyData {
        val codexStore = parseSource(
            source = source.codex,
            label = LEGACY_CODEX_FILE_NAME,
            type = LegacyCodexStoreFile::class.java,
            emptyValue = LegacyCodexStoreFile(),
        )
        val likesStore = parseSource(
            source = source.likes,
            label = LEGACY_LIKES_FILE_NAME,
            type = LegacyLikesStoreFile::class.java,
            emptyValue = LegacyLikesStoreFile(),
        )

        val codicesById = linkedMapOf<String, CodexEntity>()
        codexStore.codices.orEmpty().forEachIndexed { index, record ->
            val value = record ?: migrationFailure("Codex record at index $index is null")
            val id = requireNonBlankKey(value.codexId, "Codex record at index $index has no id")
            if (id in codicesById) {
                migrationFailure("Codex id '$id' appears more than once")
            }
            codicesById[id] = CodexEntity(
                id,
                value.name?.trim()?.ifBlank { "Codex" } ?: "Codex",
                value.createdAtEpochMs ?: 0L,
                codicesById.size,
            )
        }
        val codices = codicesById.values.toList()

        val postsById = linkedMapOf<PostId, Post>()
        codexStore.posts.orEmpty().forEachIndexed { index, record ->
            val post = decodeLegacyPost(record, index)
            if (post.id in postsById) {
                migrationFailure("Post id '${post.id.source}:${post.id.sourcePostId}' appears more than once")
            }
            postsById[post.id] = post
        }
        val posts = postsById.values.map { post ->
            PostEntity(post.id.source.name, post.id.sourcePostId, postCodec.encode(post))
        }

        val itemsByKey = linkedMapOf<Triple<String, SourceKey, String>, CodexItemEntity>()
        codexStore.items.orEmpty().forEach { (mapCodexId, records) ->
            val codexId = requireNonBlankKey(mapCodexId, "Codex item group has no Codex id")
            if (codexId !in codicesById) {
                migrationFailure("Codex item group '$codexId' references an unknown Codex")
            }
            val group = records
                ?: migrationFailure("Codex item group '$codexId' is null")
            group.forEachIndexed { index, record ->
                val value = record
                    ?: migrationFailure("Codex item '$codexId'[$index] is null")
                val recordCodexId = requireNonBlankKey(
                    value.codexId,
                    "Codex item '$codexId'[$index] has no record Codex id",
                )
                if (recordCodexId != codexId) {
                    migrationFailure(
                        "Codex item '$codexId'[$index] declares mismatched Codex '$recordCodexId'"
                    )
                }
                val sourceKey = requireSource(
                    value.source,
                    "Codex item '$codexId'[$index]",
                )
                val sourcePostId = requireNonBlankKey(
                    value.sourcePostId,
                    "Codex item '$codexId'[$index] has no post id",
                )
                val postId = PostId(sourceKey, sourcePostId)
                if (postId !in postsById) {
                    migrationFailure(
                        "Codex item '$codexId'[$index] references missing post " +
                            "'${sourceKey.name}:$sourcePostId'"
                    )
                }
                val key = Triple(codexId, sourceKey, sourcePostId)
                if (key in itemsByKey) {
                    migrationFailure(
                        "Codex item '${sourceKey.name}:$sourcePostId' appears more than once in '$codexId'"
                    )
                }
                itemsByKey[key] = CodexItemEntity(
                    codexId,
                    sourceKey.name,
                    sourcePostId,
                    value.savedAtEpochMs ?: 0L,
                )
            }
        }

        val likesByKey = linkedMapOf<Triple<String, SourceKey, String>, LikedPostEntity>()
        likesStore.likes.orEmpty().forEachIndexed { index, record ->
            val value = record ?: migrationFailure("Like record at index $index is null")
            val sourceKey = requireSource(value.source, "Like record at index $index")
            val sourcePostId = requireNonBlankKey(
                value.sourcePostId,
                "Like record at index $index has no post id",
            )
            val profileId = parseStoredProfileId(value.profileId, value.profile)
            val key = Triple(profileId, sourceKey, sourcePostId)
            if (key in likesByKey) {
                migrationFailure(
                    "Like '${sourceKey.name}:$sourcePostId' appears more than once for profile '$profileId'"
                )
            }
            val tags = try {
                CodexLikesPolicy.normalizeLikedTags(value.tags.orEmpty())
            } catch (error: RuntimeException) {
                migrationFailure("Like record at index $index contains invalid tags", error)
            }
            likesByKey[key] = LikedPostEntity(
                profileId,
                sourceKey.name,
                sourcePostId,
                value.likedAtEpochMs ?: clock(),
                gson.toJson(tags),
            )
        }

        return PreparedLegacyData(
            codices = codices,
            posts = posts,
            items = itemsByKey.values.toList(),
            likes = likesByKey.values.toList(),
        )
    }

    private fun decodeLegacyPost(record: JsonObject?, index: Int): Post {
        val value = record ?: migrationFailure("Post record at index $index is null")
        val label = "Post record at index $index"
        val sourceName = requiredJsonString(value, "source", "$label has no source")
        val sourceKey = requireSource(sourceName, label)
        val sourcePostId = requiredJsonString(value, "sourcePostId", "$label has no post id")
        validatePostSchemaVersion(value, label)

        val storageRecord = try {
            gson.fromJson(value, PostStorageRecord::class.java)
                ?: migrationFailure("$label decoded to null")
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            migrationFailure("$label does not match the legacy Post schema", error)
        }
        val post = try {
            PostStorageCodec.decode(storageRecord)
                ?: migrationFailure("$label cannot be decoded by Post storage schema v1")
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            migrationFailure("$label contains invalid Post values", error)
        }
        val expectedId = PostId(sourceKey, sourcePostId)
        if (post.id != expectedId) {
            migrationFailure("$label changed identity while decoding")
        }
        return post
    }

    private fun validatePostSchemaVersion(record: JsonObject, label: String) {
        val element = record.get("schemaVersion") ?: return
        if (element.isJsonNull) return
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            migrationFailure("$label has a non-integer Post schema version")
        }
        val raw = element.asJsonPrimitive.asString
        if (!raw.matches(LEGACY_INTEGER_PATTERN)) {
            migrationFailure("$label has a non-integer Post schema version")
        }
        val version = raw.toIntOrNull()
            ?: migrationFailure("$label has a Post schema version outside the integer range")
        if (version != CURRENT_POST_STORAGE_SCHEMA_VERSION) {
            migrationFailure("$label uses unsupported future Post schema version $version")
        }
    }

    private fun requiredJsonString(record: JsonObject, field: String, failure: String): String {
        val element = record.get(field) ?: migrationFailure(failure)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            migrationFailure(failure)
        }
        return requireNonBlankKey(element.asString, failure)
    }

    private fun requireSource(raw: String?, label: String): SourceKey {
        val normalized = requireNonBlankKey(raw, "$label has no source")
        return normalized.toSourceKeyOrNull()
            ?: migrationFailure("$label uses unknown source '$normalized'")
    }

    private fun requireNonBlankKey(raw: String?, failure: String): String {
        return raw?.trim()?.takeIf(String::isNotBlank) ?: migrationFailure(failure)
    }

    private fun migrationFailure(message: String, cause: Throwable? = null): Nothing {
        throw LegacyJsonMigrationException(message, cause)
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

    private fun <T> parseSource(
        source: LegacySource,
        label: String,
        type: Class<T>,
        emptyValue: T,
    ): T {
        if (!source.exists || source.bytes.isEmpty() || source.bytes.all(Byte::isWhitespaceByte)) {
            return emptyValue
        }
        return try {
            gson.fromJson(source.bytes.decodeToString(), type)
                ?: throw LegacyJsonMigrationException("$label decoded to null")
        } catch (error: JsonSyntaxException) {
            throw LegacyJsonMigrationException("$label is not valid legacy JSON", error)
        }
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

private data class LegacySource(
    val exists: Boolean,
    val bytes: ByteArray,
)

private data class LegacySourcePair(
    val codex: LegacySource,
    val likes: LegacySource,
    val identity: LegacySourceIdentity,
)

private data class PreparedLegacyData(
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

private fun Byte.isWhitespaceByte(): Boolean = toInt().toChar().isWhitespace()

private val LEGACY_INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")

private fun LegacyDestinationSummary.matches(proof: LegacyMigrationProof): Boolean {
    return fingerprintSha256 == proof.destinationFingerprintSha256 &&
        codexCount == proof.codexCount &&
        postCount == proof.postCount &&
        itemCount == proof.itemCount &&
        likeCount == proof.likeCount
}

private val LegacyMigrationProof.isFullyArchived: Boolean
    get() = codexArchived && likesArchived
