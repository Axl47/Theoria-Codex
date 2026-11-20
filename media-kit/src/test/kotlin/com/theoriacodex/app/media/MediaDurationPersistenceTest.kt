package com.theoriacodex.app.media

import com.theoriacodex.data.repository.StoredMediaDurationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDurationPersistenceTest {
    @Test
    fun `terminal states retain their typed wire meaning`() {
        val states = listOf<MediaDurationState>(
            MediaDurationState.Known(5_000L, MediaDurationProvenance.PROVIDER),
            MediaDurationState.Known(6_000L, MediaDurationProvenance.ACTIVE_PLAYER),
            MediaDurationState.Known(7_000L, MediaDurationProvenance.CONTAINER_PROBE),
            MediaDurationState.Unsupported(MediaDurationUnsupportedReason.PREVIEW_ONLY_MEDIA),
            MediaDurationState.Unsupported(MediaDurationUnsupportedReason.UNSUPPORTED_CONTAINER),
            MediaDurationState.RetryableFailure(10_000L, MediaDurationFailureReason.TIMEOUT),
            MediaDurationState.RetryableFailure(11_000L, MediaDurationFailureReason.TRANSPORT_FAILURE),
        )

        states.forEach { state ->
            assertEquals(state, requireNotNull(state.toStoredState()).toMediaDurationState())
        }
    }

    @Test
    fun `pending is never durable`() {
        assertNull(MediaDurationState.Pending.toStoredState())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown durable enum fails closed before publication`() {
        StoredMediaDurationState.Unsupported("FUTURE_REASON").toMediaDurationState()
    }
}
