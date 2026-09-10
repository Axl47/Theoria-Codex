package com.theoriacodex.app.fixtures

import com.theoriacodex.app.sourceauth.CredentialStoreRecoveryState
import com.theoriacodex.app.sourceauth.InMemorySourceCredentialsStore
import com.theoriacodex.app.sourceauth.SourceAccountStore
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import kotlinx.coroutines.flow.MutableStateFlow

internal class JourneyAccounts(sources: Set<SourceKey>) : SourceAccountStore,
    SourceCredentialsProvider by InMemorySourceCredentialsStore() {
    override val recoveryState = MutableStateFlow<CredentialStoreRecoveryState>(CredentialStoreRecoveryState.Ready)
    override val availableSources = MutableStateFlow(sources)
    override suspend fun refreshAvailability() = Unit
    override suspend fun resetAfterReconnectRequired() = true
}

/** Every unconfigured external request fails locally, before opening a socket. */
object JourneyNoNetworkHttpClient : SourceHttpClient {
    override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
        error("Unexpected external request in offline fixture")
    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
        error("Unexpected external request in offline fixture")
}
