package com.theoriacodex.app.sourceauth

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.UserApiKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservableSourceAccountStoreTest {
    @Test
    fun `account owner invalidates refresh authority even when identical tokens are restored`() = runTest {
        val tokens = PixivAuthTokens("access", "refresh", 100L)
        val store = ObservableSourceAccountStore(FakeRecoverableCredentialsStore(pixivTokens = tokens))
        val session = store.getPixivSession()
        store.clearPixivTokens()
        store.savePixivTokens(tokens)
        assertFalse(store.commitPixivRefresh(session, tokens.copy(accessToken = "stale-refresh")))
        assertEquals(tokens, store.getPixivTokens())
    }

    @Test
    fun `rule34 capability changes without replacing the account store`() = runTest {
        val delegate = FakeRecoverableCredentialsStore()
        val store = ObservableSourceAccountStore(delegate)
        val baseSources = store.availableSources.value

        assertFalse(SourceKey.RULE34XXX in baseSources)

        store.saveUserApiKey(SourceKey.RULE34XXX, UserApiKey(userId = "user", apiKey = "key"))
        assertTrue(SourceKey.RULE34XXX in store.availableSources.value)
        assertEquals(baseSources, store.availableSources.value - SourceKey.RULE34XXX)

        store.clearUserApiKey(SourceKey.RULE34XXX)
        assertEquals(baseSources, store.availableSources.value)
    }

    @Test
    fun `reading persisted rule34 credentials publishes their capability`() = runTest {
        val delegate = FakeRecoverableCredentialsStore(
            rule34Credentials = UserApiKey(userId = "persisted", apiKey = "secret"),
        )
        val store = ObservableSourceAccountStore(delegate)

        assertFalse(SourceKey.RULE34XXX in store.availableSources.value)

        assertEquals(delegate.rule34Credentials, store.userApiKey(SourceKey.RULE34XXX))
        assertTrue(SourceKey.RULE34XXX in store.availableSources.value)
    }

    @Test
    fun `confirmed recovery reset removes credential gated capabilities`() = runTest {
        val delegate = FakeRecoverableCredentialsStore(
            rule34Credentials = UserApiKey(userId = "user", apiKey = "key"),
        )
        val store = ObservableSourceAccountStore(delegate)
        store.userApiKey(SourceKey.RULE34XXX)
        delegate.mutableRecoveryState.value = CredentialStoreRecoveryState.ReconnectRequired

        assertTrue(store.resetAfterReconnectRequired())

        assertFalse(SourceKey.RULE34XXX in store.availableSources.value)
        assertEquals(CredentialStoreRecoveryState.Ready, store.recoveryState.value)
    }

    @Test
    fun `credential recovery failure removes gated capabilities after any account operation`() = runTest {
        val delegate = FakeRecoverableCredentialsStore()
        val store = ObservableSourceAccountStore(delegate)
        store.saveUserApiKey(SourceKey.RULE34XXX, UserApiKey(userId = "user", apiKey = "key"))
        delegate.mutableRecoveryState.value = CredentialStoreRecoveryState.ReconnectRequired

        store.getPixivTokens()

        assertFalse(SourceKey.RULE34XXX in store.availableSources.value)
    }

    @Test
    fun `non-ready credential states cannot leave a gated source exposed`() = runTest {
        val unavailableStates = listOf(
            CredentialStoreRecoveryState.TemporarilyUnavailable,
            CredentialStoreRecoveryState.UnsupportedVersion(2),
        )

        unavailableStates.forEach { unavailable ->
            val delegate = FakeRecoverableCredentialsStore(
                rule34Credentials = UserApiKey(userId = "user", apiKey = "key"),
            )
            val store = ObservableSourceAccountStore(delegate)
            store.userApiKey(SourceKey.RULE34XXX)
            assertTrue(SourceKey.RULE34XXX in store.availableSources.value)

            delegate.mutableRecoveryState.value = unavailable
            store.getPixivTokens()

            assertFalse(SourceKey.RULE34XXX in store.availableSources.value)
        }
    }
}

private class FakeRecoverableCredentialsStore(
    var pixivTokens: PixivAuthTokens? = null,
    var gelbooruCredentials: UserApiKey? = null,
    var rule34Credentials: UserApiKey? = null,
) : RecoverableSourceCredentialsStore {
    val mutableRecoveryState = MutableStateFlow<CredentialStoreRecoveryState>(
        CredentialStoreRecoveryState.Ready,
    )
    override val recoveryState: StateFlow<CredentialStoreRecoveryState> = mutableRecoveryState

    override suspend fun getPixivTokens(): PixivAuthTokens? = pixivTokens

    override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
        pixivTokens = tokens
    }

    override suspend fun clearPixivTokens() {
        pixivTokens = null
    }

    override suspend fun userApiKey(source: SourceKey): UserApiKey? = when (source) {
        SourceKey.GELBOORU -> gelbooruCredentials
        SourceKey.RULE34XXX -> rule34Credentials
        else -> null
    }

    override suspend fun saveUserApiKey(source: SourceKey, key: UserApiKey) = setUserApiKey(source, key)

    override suspend fun clearUserApiKey(source: SourceKey) = setUserApiKey(source, null)

    private fun setUserApiKey(source: SourceKey, key: UserApiKey?) {
        if (source == SourceKey.RULE34XXX) rule34Credentials = key else gelbooruCredentials = key
    }

    override suspend fun resetAfterReconnectRequired(): Boolean {
        pixivTokens = null
        gelbooruCredentials = null
        rule34Credentials = null
        mutableRecoveryState.value = CredentialStoreRecoveryState.Ready
        return true
    }
}
