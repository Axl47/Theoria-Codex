package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId

data class StoredMediaDurationKey(
    val postId: PostId,
    val mediaFingerprint: String,
) {
    init {
        require(mediaFingerprint.isNotBlank()) { "Stored media fingerprint must not be blank" }
    }
}

sealed interface StoredMediaDurationState {
    data class Known(
        val durationMs: Long,
        val provenance: String,
    ) : StoredMediaDurationState {
        init {
            require(durationMs > 0L) { "Stored duration must be positive" }
            require(provenance.isNotBlank()) { "Stored duration provenance must not be blank" }
        }
    }

    data class Unsupported(
        val reason: String,
    ) : StoredMediaDurationState {
        init {
            require(reason.isNotBlank()) { "Stored unsupported reason must not be blank" }
        }
    }

    data class RetryableFailure(
        val retryAtEpochMs: Long,
        val reason: String,
    ) : StoredMediaDurationState {
        init {
            require(retryAtEpochMs > 0L) { "Stored retry deadline must be positive" }
            require(reason.isNotBlank()) { "Stored failure reason must not be blank" }
        }
    }
}

interface MediaDurationRepository {
    suspend fun get(key: StoredMediaDurationKey): StoredMediaDurationState?

    suspend fun put(key: StoredMediaDurationKey, state: StoredMediaDurationState)
}

const val DEFAULT_MEDIA_DURATION_ENTRY_LIMIT = 4_096
