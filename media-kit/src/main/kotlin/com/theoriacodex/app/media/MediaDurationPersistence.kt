package com.theoriacodex.app.media

import com.theoriacodex.data.repository.StoredMediaDurationKey
import com.theoriacodex.data.repository.StoredMediaDurationState

internal fun MediaDurationKey.toStoredKey(): StoredMediaDurationKey {
    return StoredMediaDurationKey(postId = postId, mediaFingerprint = mediaFingerprint)
}

internal fun MediaDurationState.toStoredState(): StoredMediaDurationState? {
    return when (this) {
        MediaDurationState.Pending -> null
        is MediaDurationState.Known -> StoredMediaDurationState.Known(
            durationMs = durationMs,
            provenance = provenance.name,
        )
        is MediaDurationState.Unsupported -> StoredMediaDurationState.Unsupported(reason.name)
        is MediaDurationState.RetryableFailure -> StoredMediaDurationState.RetryableFailure(
            retryAtEpochMs = retryAtEpochMs,
            reason = reason.name,
        )
    }
}

internal fun StoredMediaDurationState.toMediaDurationState(): MediaDurationState {
    return when (this) {
        is StoredMediaDurationState.Known -> MediaDurationState.Known(
            durationMs = durationMs,
            provenance = MediaDurationProvenance.valueOf(provenance),
        )
        is StoredMediaDurationState.Unsupported -> MediaDurationState.Unsupported(
            MediaDurationUnsupportedReason.valueOf(reason),
        )
        is StoredMediaDurationState.RetryableFailure -> MediaDurationState.RetryableFailure(
            retryAtEpochMs = retryAtEpochMs,
            reason = MediaDurationFailureReason.valueOf(reason),
        )
    }
}
