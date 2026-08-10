package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDurationRepositoryTest {
    @Test
    fun `known unsupported and unexpired retryable decisions round trip`() = runTest {
        var now = 100L
        val repository = InMemoryMediaDurationRepository(clock = { now })
        val knownKey = key("known")
        val unsupportedKey = key("unsupported")
        val retryableKey = key("retryable")
        val known = StoredMediaDurationState.Known(5_000L, "PROVIDER")
        val unsupported = StoredMediaDurationState.Unsupported("NO_AUTHORITATIVE_MEDIA")
        val retryable = StoredMediaDurationState.RetryableFailure(1_000L, "TIMEOUT")

        repository.put(knownKey, known)
        repository.put(unsupportedKey, unsupported)
        repository.put(retryableKey, retryable)

        assertEquals(known, repository.get(knownKey))
        assertEquals(unsupported, repository.get(unsupportedKey))
        assertEquals(retryable, repository.get(retryableKey))
        now = 1_000L
        assertNull(repository.get(retryableKey))
        assertEquals(known, repository.get(knownKey))
    }

    @Test
    fun `bounded storage retains newest deterministic records`() = runTest {
        val repository = InMemoryMediaDurationRepository(maxEntries = 2, clock = { 100L })
        val state = StoredMediaDurationState.Unsupported("UNSUPPORTED_CONTAINER")
        repository.put(key("c"), state)
        repository.put(key("b"), state)
        repository.put(key("a"), state)

        assertEquals(state, repository.get(key("a")))
        assertEquals(state, repository.get(key("b")))
        assertNull(repository.get(key("c")))
    }

    @Test
    fun `same key replaces its decision without growing the bound`() = runTest {
        val repository = InMemoryMediaDurationRepository(maxEntries = 1)
        val key = key("same")
        repository.put(key, StoredMediaDurationState.Unsupported("PROVIDER_UNSUPPORTED"))
        repository.put(key, StoredMediaDurationState.Known(7_000L, "ACTIVE_PLAYER"))

        assertEquals(
            StoredMediaDurationState.Known(7_000L, "ACTIVE_PLAYER"),
            repository.get(key),
        )
    }

    private fun key(id: String): StoredMediaDurationKey {
        return StoredMediaDurationKey(
            postId = PostId(SourceKey.HITOMI, id),
            mediaFingerprint = "fingerprint-$id",
        )
    }
}
