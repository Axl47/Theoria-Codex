package com.theoriacodex.app.sourceauth

import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VersionedSourceCredentialsStoreTest {
    @Test
    fun `construction performs no storage work`() = runTest {
        val envelope = FakeEnvelopeStore()
        val legacy = FakeLegacyCredentialStore()

        val store = createStore(envelope, legacy)

        assertEquals(CredentialStoreRecoveryState.Loading, store.recoveryState.value)
        assertTrue(envelope.events.isEmpty())
        assertTrue(legacy.events.isEmpty())
    }

    @Test
    fun `empty first launch becomes ready without creating an envelope`() = runTest {
        val envelope = FakeEnvelopeStore()
        val legacy = FakeLegacyCredentialStore()
        val store = createStore(envelope, legacy)

        assertNull(store.getPixivTokens())

        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
        assertEquals(listOf("read"), envelope.events)
        assertEquals(listOf("read"), legacy.events)
    }

    @Test
    fun `legacy migration verifies new envelope before cleanup`() = runTest {
        val snapshot = populatedSnapshot()
        val events = mutableListOf<String>()
        val envelope = FakeEnvelopeStore(eventLog = events, eventPrefix = "envelope.")
        val legacy = FakeLegacyCredentialStore(
            result = LegacyCredentialReadResult.Success(snapshot),
            eventLog = events,
            eventPrefix = "legacy.",
        )
        val store = createStore(envelope, legacy)

        assertEquals(snapshot.pixivTokens(), store.getPixivTokens())

        assertEquals(listOf("envelope.read", "legacy.read", "envelope.write", "legacy.cleanup"), events)
        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
        assertEquals(snapshot, envelope.snapshot)
    }

    @Test
    fun `failed migration write keeps legacy intact and can retry`() = runTest {
        val snapshot = populatedSnapshot()
        val envelope = FakeEnvelopeStore(
            nextWriteResult = CredentialStoreWriteResult.TemporarilyUnavailable,
        )
        val legacy = FakeLegacyCredentialStore(
            result = LegacyCredentialReadResult.Success(snapshot),
        )
        val store = createStore(envelope, legacy)

        assertNull(store.getPixivTokens())

        assertEquals(CredentialStoreRecoveryState.TemporarilyUnavailable, store.recoveryState.value)
        assertFalse("cleanup must wait for a verified envelope", "cleanup" in legacy.events)

        envelope.nextWriteResult = CredentialStoreWriteResult.Success
        assertEquals(snapshot.pixivTokens(), store.getPixivTokens())
        assertEquals(1, legacy.events.count { event -> event == "cleanup" })
    }

    @Test
    fun `committed envelope wins over leftover legacy after process interruption`() = runTest {
        val current = populatedSnapshot()
        val staleLegacy = current.withGelbooru(GelbooruCredentials("old-user", "old-key"))
        val envelope = FakeEnvelopeStore(snapshot = current)
        val legacy = FakeLegacyCredentialStore(
            result = LegacyCredentialReadResult.Success(staleLegacy),
        )
        val store = createStore(envelope, legacy)

        assertEquals(current.gelbooruCredentials(), store.getGelbooruCredentials())

        assertFalse("the legacy snapshot must never be read after an envelope exists", "read" in legacy.events)
        assertTrue("leftover legacy storage is cleaned", "cleanup" in legacy.events)
    }

    @Test
    fun `cleanup failure keeps the verified envelope and retries on the next read`() = runTest {
        val snapshot = populatedSnapshot()
        val envelope = FakeEnvelopeStore(snapshot = snapshot)
        val legacy = FakeLegacyCredentialStore(cleanupResults = ArrayDeque(listOf(false, true)))
        val store = createStore(envelope, legacy)

        assertEquals(snapshot.pixivTokens(), store.getPixivTokens())
        assertEquals(CredentialStoreRecoveryState.Migrating, store.recoveryState.value)

        assertEquals(snapshot.gelbooruCredentials(), store.getGelbooruCredentials())
        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
        assertEquals(2, legacy.events.count { event -> event == "cleanup" })
    }

    @Test
    fun `whole snapshot updates preserve unrelated accounts`() = runTest {
        val envelope = FakeEnvelopeStore()
        val legacy = FakeLegacyCredentialStore()
        val store = createStore(envelope, legacy)
        val pixiv = PixivAuthTokens("access", "refresh", 1234L)
        val gelbooru = GelbooruCredentials("gel-user", "gel-key")
        val rule34 = Rule34XxxCredentials("r34-user", "r34-key")

        listOf(
            async { store.savePixivTokens(pixiv) },
            async { store.saveGelbooruCredentials(gelbooru) },
            async { store.saveRule34XxxCredentials(rule34) },
        ).awaitAll()

        assertEquals(pixiv, store.getPixivTokens())
        assertEquals(gelbooru, store.getGelbooruCredentials())
        assertEquals(rule34, store.getRule34XxxCredentials())
    }

    @Test
    fun `tampered envelope requires explicit reconnect without deletion`() = runTest {
        val envelope = FakeEnvelopeStore(
            initialReadResult = CredentialEnvelopeReadResult.ReconnectRequired,
        )
        val legacy = FakeLegacyCredentialStore()
        val store = createStore(envelope, legacy)

        assertNull(store.getPixivTokens())

        assertEquals(CredentialStoreRecoveryState.ReconnectRequired, store.recoveryState.value)
        assertFalse("reads never reset encrypted storage", "reset" in envelope.events)
        assertFalse("reads never reset legacy storage", "reset" in legacy.events)
    }

    @Test
    fun `unreadable legacy storage requires reconnect without cleanup or reset`() = runTest {
        val envelope = FakeEnvelopeStore()
        val legacy = FakeLegacyCredentialStore(
            result = LegacyCredentialReadResult.ReconnectRequired,
        )
        val store = createStore(envelope, legacy)

        assertNull(store.getGelbooruCredentials())

        assertEquals(CredentialStoreRecoveryState.ReconnectRequired, store.recoveryState.value)
        assertEquals(listOf("read"), envelope.events)
        assertEquals(listOf("read"), legacy.events)
    }

    @Test
    fun `unsupported future envelope is preserved and cannot be reset as corruption`() = runTest {
        val envelope = FakeEnvelopeStore(
            initialReadResult = CredentialEnvelopeReadResult.UnsupportedVersion(7),
        )
        val legacy = FakeLegacyCredentialStore()
        val store = createStore(envelope, legacy)

        assertNull(store.getPixivTokens())

        assertEquals(CredentialStoreRecoveryState.UnsupportedVersion(7), store.recoveryState.value)
        assertFalse(store.resetAfterReconnectRequired())
        assertFalse("unsupported data must remain untouched", "reset" in envelope.events)
    }

    @Test
    fun `explicit reconnect reset must clear both stores and partial reset stays recoverable`() = runTest {
        val envelope = FakeEnvelopeStore(
            initialReadResult = CredentialEnvelopeReadResult.ReconnectRequired,
            resetResult = false,
        )
        val legacy = FakeLegacyCredentialStore(resetResult = true)
        val store = createStore(envelope, legacy)
        store.getPixivTokens()

        assertFalse(store.resetAfterReconnectRequired())
        assertEquals(CredentialStoreRecoveryState.ReconnectRequired, store.recoveryState.value)
        assertEquals(listOf("read", "reset"), envelope.events)
        assertEquals(listOf("reset"), legacy.events)

        envelope.resetResult = true
        assertTrue(store.resetAfterReconnectRequired())
        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
    }

    @Test
    fun `reset is rejected unless the store explicitly requires reconnect`() = runTest {
        val envelope = FakeEnvelopeStore()
        val legacy = FakeLegacyCredentialStore()
        val store = createStore(envelope, legacy)

        assertFalse(store.resetAfterReconnectRequired())
        assertTrue(envelope.events.isEmpty())
        assertTrue(legacy.events.isEmpty())

        store.getPixivTokens()

        assertFalse(store.resetAfterReconnectRequired())
        assertFalse("ready credentials must not be deleted", "reset" in envelope.events)
        assertFalse("ready legacy state must not be deleted", "reset" in legacy.events)
    }

    @Test
    fun `failed runtime write exposes availability without leaking a secret`() = runTest {
        val envelope = FakeEnvelopeStore(
            nextWriteResult = CredentialStoreWriteResult.TemporarilyUnavailable,
        )
        val store = createStore(envelope, FakeLegacyCredentialStore())
        store.getPixivTokens()

        val error = runCatching {
            store.savePixivTokens(PixivAuthTokens("private-access", "private-refresh", 123L))
        }.exceptionOrNull()

        assertTrue(error is CredentialStoreUnavailableException)
        assertFalse(error.toString().contains("private-access"))
        assertFalse(populatedSnapshot().toString().contains("access"))
        assertEquals(CredentialStoreRecoveryState.TemporarilyUnavailable, store.recoveryState.value)

        // The failed write invalidates the cache. A later read proves the authoritative store
        // again rather than serving a potentially stale snapshot indefinitely.
        assertNull(store.getPixivTokens())
        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
    }

    private fun kotlinx.coroutines.test.TestScope.createStore(
        envelope: FakeEnvelopeStore,
        legacy: FakeLegacyCredentialStore,
    ): VersionedSourceCredentialsStore = VersionedSourceCredentialsStore(
        envelopeStore = envelope,
        legacyStore = legacy,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun populatedSnapshot(): SourceCredentialSnapshot = SourceCredentialSnapshot(
        pixivAccessToken = "access",
        pixivRefreshToken = "refresh",
        pixivExpiresAtEpochMs = 1234L,
        gelbooruUserId = "gel-user",
        gelbooruApiKey = "gel-key",
        rule34XxxUserId = "r34-user",
        rule34XxxApiKey = "r34-key",
    )
}

private class FakeEnvelopeStore(
    initialReadResult: CredentialEnvelopeReadResult = CredentialEnvelopeReadResult.Missing,
    snapshot: SourceCredentialSnapshot? = null,
    var nextWriteResult: CredentialStoreWriteResult = CredentialStoreWriteResult.Success,
    var resetResult: Boolean = true,
    private val eventLog: MutableList<String> = mutableListOf(),
    private val eventPrefix: String = "",
) : CredentialEnvelopeStore {
    var snapshot: SourceCredentialSnapshot? = snapshot
    private var fixedReadResult: CredentialEnvelopeReadResult? = initialReadResult
        .takeUnless { result -> result == CredentialEnvelopeReadResult.Missing && snapshot != null }

    val events: List<String> get() = eventLog

    override suspend fun read(): CredentialEnvelopeReadResult {
        eventLog += "${eventPrefix}read"
        return fixedReadResult ?: snapshot
            ?.let(CredentialEnvelopeReadResult::Success)
            ?: CredentialEnvelopeReadResult.Missing
    }

    override suspend fun writeVerified(
        snapshot: SourceCredentialSnapshot,
    ): CredentialStoreWriteResult {
        eventLog += "${eventPrefix}write"
        if (nextWriteResult == CredentialStoreWriteResult.Success) {
            this.snapshot = snapshot
            fixedReadResult = null
        }
        return nextWriteResult
    }

    override suspend fun reset(): Boolean {
        eventLog += "reset"
        if (resetResult) {
            snapshot = null
            fixedReadResult = CredentialEnvelopeReadResult.Missing
        }
        return resetResult
    }
}

private class FakeLegacyCredentialStore(
    private val result: LegacyCredentialReadResult = LegacyCredentialReadResult.Missing,
    private val cleanupResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true)),
    var resetResult: Boolean = true,
    private val eventLog: MutableList<String> = mutableListOf(),
    private val eventPrefix: String = "",
) : LegacyCredentialStore {
    val events: List<String> get() = eventLog

    override suspend fun read(): LegacyCredentialReadResult {
        eventLog += "${eventPrefix}read"
        return result
    }

    override suspend fun cleanup(): Boolean {
        eventLog += "${eventPrefix}cleanup"
        return if (cleanupResults.isEmpty()) true else cleanupResults.removeFirst()
    }

    override suspend fun reset(): Boolean {
        eventLog += "reset"
        return resetResult
    }
}
