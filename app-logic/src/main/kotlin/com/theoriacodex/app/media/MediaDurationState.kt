package com.theoriacodex.app.media

import com.theoriacodex.domain.model.PostId
import java.security.MessageDigest

data class MediaDurationKey(
    val postId: PostId,
    val mediaFingerprint: String,
) {
    init {
        require(mediaFingerprint.isNotBlank()) { "Media fingerprint must not be blank" }
    }
}

data class MediaDurationFingerprintInput(
    val postId: PostId,
    val normalizedAuthoritativeMediaIdentity: String,
    val mime: String?,
    val mediaCount: Int?,
) {
    init {
        require(normalizedAuthoritativeMediaIdentity.isNotBlank()) {
            "Authoritative media identity must not be blank"
        }
    }
}

fun mediaDurationFingerprint(input: MediaDurationFingerprintInput): String {
    val canonical = buildString {
        append(input.postId.source.id)
        append('\u0000')
        append(input.postId.sourcePostId)
        append('\u0000')
        append(input.normalizedAuthoritativeMediaIdentity)
        append('\u0000')
        append(input.mime?.trim()?.lowercase().orEmpty())
        append('\u0000')
        append(input.mediaCount ?: -1)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
    val encoded = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        encoded[index * 2] = HEX_DIGITS[value ushr 4]
        encoded[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return encoded.concatToString()
}

private const val HEX_DIGITS = "0123456789abcdef"

sealed interface MediaDurationState {
    data object Pending : MediaDurationState

    data class Known(
        val durationMs: Long,
        val provenance: MediaDurationProvenance,
    ) : MediaDurationState {
        init {
            require(durationMs > 0L) { "Known duration must be positive" }
        }
    }

    data class Unsupported(
        val reason: MediaDurationUnsupportedReason,
    ) : MediaDurationState

    data class RetryableFailure(
        val retryAtEpochMs: Long,
        val reason: MediaDurationFailureReason,
    ) : MediaDurationState {
        init {
            require(retryAtEpochMs > 0L) { "Retry deadline must be positive" }
        }
    }
}

/** No lookup has decided this duration yet: none was asked for, or one is still running. */
val MediaDurationState?.isUndecided: Boolean
    get() = this == null || this == MediaDurationState.Pending

enum class MediaDurationProvenance {
    PROVIDER,
    ACTIVE_PLAYER,
    CONTAINER_PROBE,
}

enum class MediaDurationUnsupportedReason {
    STATIC_MEDIA,
    PREVIEW_ONLY_MEDIA,
    NO_AUTHORITATIVE_MEDIA,
    PROVIDER_UNSUPPORTED,
    UNSUPPORTED_CONTAINER,
}

enum class MediaDurationFailureReason {
    PROVIDER_FAILURE,
    TRANSPORT_FAILURE,
    TIMEOUT,
    /** The host pushed back on our request rate, so the media itself may be fine. */
    RATE_LIMITED,
}

enum class DurationDemandPriority {
    ACTIVE_FILTER,
    VISIBLE,
    NEAR_VIEWPORT,
    BACKGROUND_IDLE,
}

data class DurationDemand(
    val identity: String,
    val key: MediaDurationKey,
    val priority: DurationDemandPriority,
) {
    init {
        require(identity.isNotBlank()) { "Demand identity must not be blank" }
    }
}

/** What a post without a feed-supplied duration offers for looking one up. */
data class DurationAcquisitionFacts(
    val hasAuthoritativeFullVideo: Boolean,
    val hasProviderDurationResolver: Boolean,
)

sealed interface DurationAcquisitionPlan {
    data object AskProvider : DurationAcquisitionPlan
    data object ProbeAuthoritativeMedia : DurationAcquisitionPlan
    data class Unsupported(val reason: MediaDurationUnsupportedReason) : DurationAcquisitionPlan
}

fun planDurationAcquisition(facts: DurationAcquisitionFacts): DurationAcquisitionPlan {
    if (facts.hasAuthoritativeFullVideo) {
        return DurationAcquisitionPlan.ProbeAuthoritativeMedia
    }
    if (facts.hasProviderDurationResolver) {
        return DurationAcquisitionPlan.AskProvider
    }
    return DurationAcquisitionPlan.Unsupported(MediaDurationUnsupportedReason.NO_AUTHORITATIVE_MEDIA)
}
