package com.theoriacodex.app.sourceauth

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.source.SiteCredentials
import com.theoriacodex.sources.credentials.UserApiKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

        val store = createStore(envelope)

        assertEquals(CredentialStoreRecoveryState.Loading, store.recoveryState.value)
        assertTrue(envelope.events.isEmpty())
    }

    @Test
    fun `empty first launch becomes ready without creating an envelope`() = runTest {
        val envelope = FakeEnvelopeStore()
        val store = createStore(envelope)

        assertNull(store.getPixivTokens())

        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
        assertEquals(listOf("read"), envelope.events)
    }

    @Test
    fun `whole snapshot updates preserve unrelated accounts`() = runTest {
        val envelope = FakeEnvelopeStore()
        val store = createStore(envelope)
        val pixiv = PixivAuthTokens("access", "refresh", 1234L)
        val gelbooru = UserApiKey("gel-user", "gel-key")
        val rule34 = UserApiKey("r34-user", "r34-key")

        listOf(
            async { store.savePixivTokens(pixiv) },
            async { store.saveUserApiKey(SourceKey.GELBOORU, gelbooru) },
            async { store.saveUserApiKey(SourceKey.RULE34XXX, rule34) },
        ).awaitAll()

        assertEquals(pixiv, store.getPixivTokens())
        assertEquals(gelbooru, store.userApiKey(SourceKey.GELBOORU))
        assertEquals(rule34, store.userApiKey(SourceKey.RULE34XXX))
    }

    @Test
    fun `tampered envelope requires explicit reconnect without deletion`() = runTest {
        val envelope = FakeEnvelopeStore(
            initialReadResult = CredentialEnvelopeReadResult.ReconnectRequired,
        )
        val store = createStore(envelope)

        assertNull(store.getPixivTokens())

        assertEquals(CredentialStoreRecoveryState.ReconnectRequired, store.recoveryState.value)
        assertFalse("reads never reset encrypted storage", "reset" in envelope.events)
    }

    @Test
    fun `unsupported future envelope is preserved and cannot be reset as corruption`() = runTest {
        val envelope = FakeEnvelopeStore(
            initialReadResult = CredentialEnvelopeReadResult.UnsupportedVersion(7),
        )
        val store = createStore(envelope)

        assertNull(store.getPixivTokens())

        assertEquals(CredentialStoreRecoveryState.UnsupportedVersion(7), store.recoveryState.value)
        assertFalse(store.resetAfterReconnectRequired())
        assertFalse("unsupported data must remain untouched", "reset" in envelope.events)
    }

    @Test
    fun `explicit reconnect reset clears the envelope and a partial reset stays recoverable`() = runTest {
        val envelope = FakeEnvelopeStore(
            initialReadResult = CredentialEnvelopeReadResult.ReconnectRequired,
            resetResult = false,
        )
        val store = createStore(envelope)
        store.getPixivTokens()

        assertFalse(store.resetAfterReconnectRequired())
        assertEquals(CredentialStoreRecoveryState.ReconnectRequired, store.recoveryState.value)
        assertEquals(listOf("read", "reset"), envelope.events)

        envelope.resetResult = true
        assertTrue(store.resetAfterReconnectRequired())
        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
    }

    @Test
    fun `reset is rejected unless the store explicitly requires reconnect`() = runTest {
        val envelope = FakeEnvelopeStore()
        val store = createStore(envelope)

        assertFalse(store.resetAfterReconnectRequired())
        assertTrue(envelope.events.isEmpty())

        store.getPixivTokens()

        assertFalse(store.resetAfterReconnectRequired())
        assertFalse("ready credentials must not be deleted", "reset" in envelope.events)
    }

    @Test
    fun `failed runtime write exposes availability without leaking a secret`() = runTest {
        val envelope = FakeEnvelopeStore(
            nextWriteResult = CredentialStoreWriteResult.TemporarilyUnavailable,
        )
        val store = createStore(envelope)
        store.getPixivTokens()

        val error = runCatching {
            store.savePixivTokens(PixivAuthTokens("private-access", "private-refresh", 123L))
        }.exceptionOrNull()

        assertTrue(error is CredentialStoreUnavailableException)
        assertFalse(error.toString().contains("private-access"))
        assertFalse(populatedSnapshot().toString().contains("access"))
        assertEquals(CredentialStoreRecoveryState.TemporarilyUnavailable, store.recoveryState.value)

        assertNull(store.getPixivTokens())
        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
    }

    @Test
    fun `custom site keys are stored per source id beside built-in keys`() = runTest {
        val envelope = FakeEnvelopeStore(
            initialReadResult = CredentialEnvelopeReadResult.Success(populatedSnapshot()),
            snapshot = populatedSnapshot(),
        )
        val store = createStore(envelope)
        val changes = mutableListOf<Unit>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.changes.toList(changes) }

        store.save("custom_safebooru_org", SiteCredentials("42", "key"))

        assertEquals(SiteCredentials("42", "key"), store.get("custom_safebooru_org"))
        assertNull(store.get("custom_other_example"))
        assertEquals(populatedSnapshot().userApiKey(SourceKey.GELBOORU), envelope.snapshot?.userApiKey(SourceKey.GELBOORU))
        store.clear("custom_safebooru_org")
        assertNull(store.get("custom_safebooru_org"))
        assertEquals(populatedSnapshot(), envelope.snapshot)
        assertEquals("initial value, save and clear", 3, changes.size)
        collector.cancel()
    }

    @Test
    fun `custom site keys need a custom source id and survive built-in key changes`() = runTest {
        val envelope = FakeEnvelopeStore()
        val store = createStore(envelope)

        assertTrue(runCatching { store.save("GELBOORU", SiteCredentials("1", "k")) }.isFailure)
        store.save("custom_booru_example", SiteCredentials("alice", "k1"))
        store.saveUserApiKey(SourceKey.GELBOORU, UserApiKey("gel", "gk"))

        assertEquals(SiteCredentials("alice", "k1"), store.get("custom_booru_example"))
    }

    private fun kotlinx.coroutines.test.TestScope.createStore(
        envelope: FakeEnvelopeStore,
    ): VersionedSourceCredentialsStore = VersionedSourceCredentialsStore(
        envelopeStore = envelope,
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
) : CredentialEnvelopeStore {
    var snapshot: SourceCredentialSnapshot? = snapshot
    private var fixedReadResult: CredentialEnvelopeReadResult? = initialReadResult
        .takeUnless { result -> result == CredentialEnvelopeReadResult.Missing && snapshot != null }

    val events = mutableListOf<String>()

    override suspend fun read(): CredentialEnvelopeReadResult {
        events += "read"
        return fixedReadResult ?: snapshot
            ?.let(CredentialEnvelopeReadResult::Success)
            ?: CredentialEnvelopeReadResult.Missing
    }

    override suspend fun writeVerified(
        snapshot: SourceCredentialSnapshot,
    ): CredentialStoreWriteResult {
        events += "write"
        if (nextWriteResult == CredentialStoreWriteResult.Success) {
            this.snapshot = snapshot
            fixedReadResult = null
        }
        return nextWriteResult
    }

    override suspend fun reset(): Boolean {
        events += "reset"
        if (resetResult) {
            snapshot = null
            fixedReadResult = CredentialEnvelopeReadResult.Missing
        }
        return resetResult
    }
}
