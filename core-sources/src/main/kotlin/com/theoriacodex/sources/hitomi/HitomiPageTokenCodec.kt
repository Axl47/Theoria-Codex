package com.theoriacodex.sources.hitomi

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.Base64

internal class HitomiPageTokenCodec(
    private val gson: Gson = Gson(),
) {
    fun encode(token: HitomiPageToken): String {
        val json = gson.toJson(token).toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json)
    }

    fun decode(raw: String): DecodedHitomiPageToken {
        val token = try {
            val json = Base64.getUrlDecoder().decode(raw).toString(Charsets.UTF_8)
            gson.fromJson(json, HitomiPageToken::class.java)
                ?: throw IllegalArgumentException("null token")
        } catch (error: RuntimeException) {
            throw HitomiPageTokenException("Hitomi page token was malformed", error)
        }
        if (
            token.version !in SUPPORTED_PAGE_TOKEN_VERSIONS ||
            token.queryHash.isNullOrBlank() ||
            token.primaryKey.isNullOrBlank() ||
            token.primaryOffset == null ||
            token.randomSeed == null
        ) {
            throw HitomiPageTokenException("Hitomi page token used an unsupported shape")
        }
        return DecodedHitomiPageToken(
            queryHash = token.queryHash,
            primaryKey = token.primaryKey,
            primaryOffset = token.primaryOffset,
            randomSeed = token.randomSeed,
            randomSnapshotFingerprint = token.randomSnapshotFingerprint
                ?.takeIf(RANDOM_SNAPSHOT_FINGERPRINT::matches)
                ?: token.randomSnapshotFingerprint?.let {
                    throw HitomiPageTokenException(
                        "Hitomi page token contained an invalid random snapshot",
                    )
                },
            randomPermutationVersion = if (token.version == HITOMI_PAGE_TOKEN_VERSION) {
                token.randomPermutationVersion
            } else {
                null
            },
            globalIndexVersion = if (token.version in GLOBAL_INDEX_PAGE_TOKEN_VERSIONS) {
                token.globalIndexVersion
                    ?.takeIf(GLOBAL_INDEX_VERSION_PATTERN::matches)
                    ?: token.globalIndexVersion?.let {
                        throw HitomiPageTokenException(
                            "Hitomi page token contained an invalid global index version",
                        )
                    }
            } else {
                null
            },
        )
    }
}

internal data class HitomiPageToken(
    @field:SerializedName("version") val version: Int? = null,
    @field:SerializedName("queryHash") val queryHash: String? = null,
    @field:SerializedName("primaryKey") val primaryKey: String? = null,
    @field:SerializedName("primaryOffset") val primaryOffset: Long? = null,
    @field:SerializedName("randomSeed") val randomSeed: Long? = null,
    @field:SerializedName("randomSnapshotFingerprint") val randomSnapshotFingerprint: String? = null,
    @field:SerializedName("randomPermutationVersion") val randomPermutationVersion: Int? = null,
    @field:SerializedName("globalIndexVersion") val globalIndexVersion: String? = null,
)

internal data class DecodedHitomiPageToken(
    val queryHash: String,
    val primaryKey: String,
    val primaryOffset: Long,
    val randomSeed: Long,
    val randomSnapshotFingerprint: String?,
    val randomPermutationVersion: Int?,
    val globalIndexVersion: String?,
)

internal class HitomiPageTokenException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal const val HITOMI_PAGE_TOKEN_VERSION = 4
private const val LEGACY_PAGE_TOKEN_VERSION = 2
private const val PREVIOUS_PAGE_TOKEN_VERSION = 3
private val SUPPORTED_PAGE_TOKEN_VERSIONS = setOf(
    LEGACY_PAGE_TOKEN_VERSION,
    PREVIOUS_PAGE_TOKEN_VERSION,
    HITOMI_PAGE_TOKEN_VERSION,
)
private val GLOBAL_INDEX_PAGE_TOKEN_VERSIONS = setOf(
    PREVIOUS_PAGE_TOKEN_VERSION,
    HITOMI_PAGE_TOKEN_VERSION,
)
private val GLOBAL_INDEX_VERSION_PATTERN = Regex("[0-9]+")
private val RANDOM_SNAPSHOT_FINGERPRINT = Regex("[0-9a-f]{64}")
