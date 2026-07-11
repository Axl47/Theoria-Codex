package com.theoriacodex.data.android.room

import java.nio.ByteBuffer
import java.security.MessageDigest

internal sealed interface StoredProofValidation {
    data class Valid(val proof: LegacyMigrationProof) : StoredProofValidation
    data class Invalid(val reason: String) : StoredProofValidation
}

internal data class ImmutableProofFields(
    val source: LegacySourceIdentity,
    val destination: LegacyDestinationSummary,
    val completedAtEpochMs: Long,
) {
    fun proofSha256(): String {
        return CanonicalDigest()
            .add("legacy-proof-v1")
            .add(source.sourceFingerprintSha256)
            .add(source.codexFileSha256)
            .add(source.likesFileSha256)
            .add(source.codexSourcePresent)
            .add(source.likesSourcePresent)
            .add(destination.fingerprintSha256)
            .add(destination.codexCount)
            .add(destination.postCount)
            .add(destination.itemCount)
            .add(destination.likeCount)
            .add(completedAtEpochMs)
            .finish()
    }
}

internal fun MigrationMetadataEntity.validateProof(): StoredProofValidation {
    val hashes = listOf(
        sourceFingerprintSha256,
        codexFileSha256,
        likesFileSha256,
        destinationFingerprintSha256,
        proofSha256,
    )
    if (hashes.any { hash -> !hash.matches(SHA256_PATTERN) }) {
        return StoredProofValidation.Invalid("migration proof contains a malformed SHA-256 value")
    }
    if (listOf(codexCount, postCount, itemCount, likeCount).any { count -> count < 0 }) {
        return StoredProofValidation.Invalid("migration proof contains a negative record count")
    }
    if (completedAtEpochMs < 0L) {
        return StoredProofValidation.Invalid("migration proof completion time is invalid")
    }
    val source = LegacySourceIdentity(
        sourceFingerprintSha256 = sourceFingerprintSha256,
        codexFileSha256 = codexFileSha256,
        likesFileSha256 = likesFileSha256,
        codexSourcePresent = isCodexSourcePresent,
        likesSourcePresent = isLikesSourcePresent,
    )
    if (legacySourceFingerprint(codexFileSha256, likesFileSha256) != sourceFingerprintSha256) {
        return StoredProofValidation.Invalid("migration source fingerprint does not match its files")
    }
    val destination = LegacyDestinationSummary(
        fingerprintSha256 = destinationFingerprintSha256,
        codexCount = codexCount,
        postCount = postCount,
        itemCount = itemCount,
        likeCount = likeCount,
    )
    val expectedProofSha = ImmutableProofFields(source, destination, completedAtEpochMs).proofSha256()
    if (expectedProofSha != proofSha256) {
        return StoredProofValidation.Invalid("migration proof checksum does not match its contents")
    }
    return StoredProofValidation.Valid(
        LegacyMigrationProof(
            sourceFingerprintSha256 = sourceFingerprintSha256,
            codexFileSha256 = codexFileSha256,
            likesFileSha256 = likesFileSha256,
            destinationFingerprintSha256 = destinationFingerprintSha256,
            proofSha256 = proofSha256,
            codexSourcePresent = isCodexSourcePresent,
            likesSourcePresent = isLikesSourcePresent,
            codexCount = codexCount,
            postCount = postCount,
            itemCount = itemCount,
            likeCount = likeCount,
            completedAtEpochMs = completedAtEpochMs,
            codexArchived = isCodexArchived,
            likesArchived = isLikesArchived,
        )
    )
}

internal fun LegacyMigrationProof.toEntity(): MigrationMetadataEntity {
    return MigrationMetadataEntity(
        LEGACY_MIGRATION_KEY,
        sourceFingerprintSha256,
        codexFileSha256,
        likesFileSha256,
        destinationFingerprintSha256,
        proofSha256,
        codexSourcePresent,
        likesSourcePresent,
        codexCount,
        postCount,
        itemCount,
        likeCount,
        completedAtEpochMs,
        codexArchived,
        likesArchived,
    )
}

internal fun summarizeDestination(
    codices: List<CodexEntity>,
    posts: List<PostEntity>,
    items: List<CodexItemEntity>,
    likes: List<LikedPostEntity>,
): LegacyDestinationSummary {
    val digest = CanonicalDigest().add("legacy-destination-v1")
    codices.sortedWith(
        compareBy<CodexEntity> { entity -> entity.displayOrder }
            .thenBy { entity -> entity.createdAtEpochMs }
            .thenBy { entity -> entity.codexId }
    ).forEach { entity ->
        digest.add("codex")
            .add(entity.codexId)
            .add(entity.name)
            .add(entity.createdAtEpochMs)
            .add(entity.displayOrder)
    }
    posts.sortedWith(compareBy<PostEntity> { it.source }.thenBy { it.sourcePostId }).forEach { entity ->
        digest.add("post")
            .add(entity.source)
            .add(entity.sourcePostId)
            .add(entity.payloadJson)
    }
    items.sortedWith(
        compareBy<CodexItemEntity> { it.codexId }
            .thenBy { it.source }
            .thenBy { it.sourcePostId }
    ).forEach { entity ->
        digest.add("item")
            .add(entity.codexId)
            .add(entity.source)
            .add(entity.sourcePostId)
            .add(entity.savedAtEpochMs)
    }
    likes.sortedWith(
        compareBy<LikedPostEntity> { it.profileId }
            .thenBy { it.source }
            .thenBy { it.sourcePostId }
    ).forEach { entity ->
        digest.add("like")
            .add(entity.profileId)
            .add(entity.source)
            .add(entity.sourcePostId)
            .add(entity.likedAtEpochMs)
            .add(entity.tagsJson)
    }
    return LegacyDestinationSummary(
        fingerprintSha256 = digest.finish(),
        codexCount = codices.size,
        postCount = posts.size,
        itemCount = items.size,
        likeCount = likes.size,
    )
}

internal fun legacySourceFingerprint(codexChecksum: String, likesChecksum: String): String {
    return sha256("$LEGACY_MIGRATION_KEY\n$codexChecksum\n$likesChecksum".encodeToByteArray())
}

internal fun sha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHexString()
}

private class CanonicalDigest {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun add(value: String): CanonicalDigest {
        val bytes = value.encodeToByteArray()
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Int): CanonicalDigest = add(value.toString())
    fun add(value: Long): CanonicalDigest = add(value.toString())
    fun add(value: Boolean): CanonicalDigest = add(if (value) "1" else "0")

    fun finish(): String = digest.digest().toHexString()
}

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte) }
}
