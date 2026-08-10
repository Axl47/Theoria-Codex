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
        append(input.postId.source.name)
        append('\u0000')
        append(input.postId.sourcePostId)
        append('\u0000')
        append(input.normalizedAuthoritativeMediaIdentity)
        append('\u0000')
        append(input.mime?.trim()?.lowercase().orEmpty())
        append('\u0000')
        append(input.mediaCount ?: -1)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

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
}

enum class DurationDemandPriority {
    ACTIVE_FILTER,
    VISIBLE,
    NEAR_VIEWPORT,
    BACKGROUND_IDLE,
}

enum class DurationDemandReason {
    FILTER,
    VIEWPORT,
    APPEND,
    PLAYER,
}

data class DurationDemand(
    val identity: String,
    val key: MediaDurationKey,
    val priority: DurationDemandPriority,
    val reason: DurationDemandReason,
) {
    init {
        require(identity.isNotBlank()) { "Demand identity must not be blank" }
    }
}

data class DurationAcquisitionFacts(
    val knownDurationMs: Long?,
    val persistedState: MediaDurationState?,
    val hasAuthoritativeFullVideo: Boolean,
    val hasProviderDurationResolver: Boolean,
)

sealed interface DurationAcquisitionPlan {
    data class AlreadyKnown(val durationMs: Long) : DurationAcquisitionPlan
    data class UsePersisted(val state: MediaDurationState) : DurationAcquisitionPlan
    data object AskProvider : DurationAcquisitionPlan
    data object ProbeAuthoritativeMedia : DurationAcquisitionPlan
    data class Unsupported(val reason: MediaDurationUnsupportedReason) : DurationAcquisitionPlan
}

fun planDurationAcquisition(facts: DurationAcquisitionFacts): DurationAcquisitionPlan {
    facts.knownDurationMs?.takeIf { durationMs -> durationMs > 0L }?.let { durationMs ->
        return DurationAcquisitionPlan.AlreadyKnown(durationMs)
    }
    facts.persistedState?.takeUnless { state -> state == MediaDurationState.Pending }?.let { state ->
        return DurationAcquisitionPlan.UsePersisted(state)
    }
    if (facts.hasAuthoritativeFullVideo) {
        return DurationAcquisitionPlan.ProbeAuthoritativeMedia
    }
    if (facts.hasProviderDurationResolver) {
        return DurationAcquisitionPlan.AskProvider
    }
    return DurationAcquisitionPlan.Unsupported(MediaDurationUnsupportedReason.NO_AUTHORITATIVE_MEDIA)
}

