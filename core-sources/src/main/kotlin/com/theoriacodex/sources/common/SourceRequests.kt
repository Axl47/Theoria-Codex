package com.theoriacodex.sources.common

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.credentials.ApiKeyUse
import com.theoriacodex.sources.credentials.UserApiKey
import com.theoriacodex.sources.credentials.UserApiKeyCredentialLookup
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostUnavailableException
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.IOException

internal inline fun <T> sourceRequest(siteLabel: String, request: () -> T): T = try {
    request()
} catch (error: IOException) {
    sourceNetworkFailure(siteLabel, error)
}

internal fun SourceHttpResponse.requireSuccess(
    failure: String,
    authBlockedBody: (String) -> Boolean = { false },
    providerMessage: (String) -> String? = { null },
): SourceHttpResponse {
    if (isSuccessful()) return this
    throw SourceAdapterException(
        reason = classifyHttpFailure(statusCode, body, authBlockedBody = authBlockedBody),
        message = providerMessage(body) ?: "$failure ($statusCode)",
    )
}

internal suspend fun SourceHttpClient.getChecked(
    siteLabel: String,
    url: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    authBlockedBody: (String) -> Boolean = { false },
    providerMessage: (String) -> String? = { null },
): String = sourceRequest(siteLabel) { get(url = url, query = query, headers = headers) }
    .requireSuccess("$siteLabel request failed", authBlockedBody, providerMessage)
    .body

internal suspend fun SourceHttpClient.getCheckedOrNull(siteLabel: String, url: String, headers: Map<String, String>): String? {
    val response = sourceRequest(siteLabel) { get(url = url, headers = headers) }
    if (response.statusCode == 404) return null
    return response.requireSuccess("$siteLabel request failed").body
}

internal suspend fun UserApiKeyCredentialLookup.keysFor(use: ApiKeyUse, siteLabel: String): UserApiKey? {
    val saved = invoke()
    if (saved == null && use == ApiKeyUse.REQUIRED) {
        throw SourceAdapterException(
            reason = SourceFailureReason.AUTH_REQUIRED,
            message = "$siteLabel credentials required",
        )
    }
    return saved
}

internal inline fun requestWithChallengeFallback(
    siteLabel: String,
    request: () -> SourceHttpResponse,
): SourceHttpResponse = sourceRequest(siteLabel) {
    try {
        request()
    } catch (error: HostUnavailableException) {
        if (error.kind != HostOutageKind.CHALLENGE) throw error
        CHALLENGED_RESPONSE
    }
}

internal val CHALLENGED_RESPONSE = SourceHttpResponse(
    statusCode = 403,
    body = "",
    headers = mapOf("cf-mitigated" to listOf("challenge")),
)
