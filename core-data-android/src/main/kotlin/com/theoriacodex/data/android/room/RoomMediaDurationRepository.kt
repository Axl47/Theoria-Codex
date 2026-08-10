package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.theoriacodex.data.repository.DEFAULT_MEDIA_DURATION_ENTRY_LIMIT
import com.theoriacodex.data.repository.MediaDurationRepository
import com.theoriacodex.data.repository.StoredMediaDurationKey
import com.theoriacodex.data.repository.StoredMediaDurationState

class RoomMediaDurationRepository(
    private val database: TheoriaRoomDatabase,
    private val maxEntries: Int = DEFAULT_MEDIA_DURATION_ENTRY_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaDurationRepository {
    private val dao = database.mediaDurationDao()

    init {
        require(maxEntries > 0) { "Media duration entry limit must be positive" }
    }

    override suspend fun get(key: StoredMediaDurationKey): StoredMediaDurationState? {
        return database.withTransaction {
            val entity = dao.find(
                key.postId.source.name,
                key.postId.sourcePostId,
                key.mediaFingerprint,
            ) ?: return@withTransaction null
            val state = entity.toStoredState()
            if (
                state is StoredMediaDurationState.RetryableFailure &&
                state.retryAtEpochMs <= clock()
            ) {
                dao.delete(entity.source, entity.sourcePostId, entity.mediaFingerprint)
                null
            } else {
                state
            }
        }
    }

    override suspend fun put(key: StoredMediaDurationKey, state: StoredMediaDurationState) {
        database.withTransaction {
            dao.upsert(state.toEntity(key, clock()))
            dao.trimToLimit(maxEntries)
        }
    }
}

private fun MediaDurationEntity.toStoredState(): StoredMediaDurationState {
    return when (decision) {
        DECISION_KNOWN -> StoredMediaDurationState.Known(
            durationMs = requireNotNull(durationMs) { "Known duration row is missing duration_ms" },
            provenance = requireNotNull(provenance) { "Known duration row is missing provenance" },
        )
        DECISION_UNSUPPORTED -> StoredMediaDurationState.Unsupported(
            reason = requireNotNull(reason) { "Unsupported duration row is missing reason" },
        )
        DECISION_RETRYABLE_FAILURE -> StoredMediaDurationState.RetryableFailure(
            retryAtEpochMs = requireNotNull(retryAtEpochMs) {
                "Retryable duration row is missing retry_at_epoch_ms"
            },
            reason = requireNotNull(reason) { "Retryable duration row is missing reason" },
        )
        else -> error("Unknown stored media duration decision $decision")
    }
}

private fun StoredMediaDurationState.toEntity(
    key: StoredMediaDurationKey,
    updatedAtEpochMs: Long,
): MediaDurationEntity {
    val fields = when (this) {
        is StoredMediaDurationState.Known -> StoredFields(
            decision = DECISION_KNOWN,
            durationMs = durationMs,
            provenance = provenance,
        )
        is StoredMediaDurationState.Unsupported -> StoredFields(
            decision = DECISION_UNSUPPORTED,
            reason = reason,
        )
        is StoredMediaDurationState.RetryableFailure -> StoredFields(
            decision = DECISION_RETRYABLE_FAILURE,
            reason = reason,
            retryAtEpochMs = retryAtEpochMs,
        )
    }
    return MediaDurationEntity(
        key.postId.source.name,
        key.postId.sourcePostId,
        key.mediaFingerprint,
        fields.decision,
        fields.durationMs,
        fields.provenance,
        fields.reason,
        fields.retryAtEpochMs,
        updatedAtEpochMs,
    )
}

private data class StoredFields(
    val decision: String,
    val durationMs: Long? = null,
    val provenance: String? = null,
    val reason: String? = null,
    val retryAtEpochMs: Long? = null,
)

private const val DECISION_KNOWN = "KNOWN"
private const val DECISION_UNSUPPORTED = "UNSUPPORTED"
private const val DECISION_RETRYABLE_FAILURE = "RETRYABLE_FAILURE"
