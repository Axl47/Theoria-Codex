package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.diagnostics.OffMainWork
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class HitomiPageTokenCodec {
    fun encode(token: HitomiPageToken): String {
        val json = hitomiPageTokenJson.encodeToString(token).toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json)
    }

    fun decode(raw: String): DecodedHitomiPageToken {
        OffMainWork.note(OffMainWork.SOURCE_PARSE)
        val token = try {
            val json = Base64.getUrlDecoder().decode(raw).toString(Charsets.UTF_8)
            hitomiPageTokenJson.decodeFromString<HitomiPageToken>(json)
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

@Serializable
internal data class HitomiPageToken(
    @SerialName("version") val version: Int? = null,
    @SerialName("queryHash") val queryHash: String? = null,
    @SerialName("primaryKey") val primaryKey: String? = null,
    @SerialName("primaryOffset") val primaryOffset: Long? = null,
    @SerialName("randomSeed") val randomSeed: Long? = null,
    @SerialName("randomSnapshotFingerprint") val randomSnapshotFingerprint: String? = null,
    @SerialName("randomPermutationVersion") val randomPermutationVersion: Int? = null,
    @SerialName("globalIndexVersion") val globalIndexVersion: String? = null,
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

/**
 * core-sources cannot see core-data's DurableJson, and a page token needs less: only this codec writes tokens, so
 * the reader just tolerates keys a newer build added and omits nulls the way the Gson encoder did.
 */
internal val hitomiPageTokenJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

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
