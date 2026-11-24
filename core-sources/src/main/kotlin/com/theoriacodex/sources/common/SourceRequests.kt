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

/** Runs one provider request, reporting a transport failure as a [sourceNetworkFailure] of [siteLabel]. */
internal inline fun <T> sourceRequest(siteLabel: String, request: () -> T): T = try {
    request()
} catch (error: IOException) {
    sourceNetworkFailure(siteLabel, error)
}

/**
 * This answer when it succeeded. Otherwise throws a failure classified by its status ([authBlockedBody] marks
 * other failing bodies as auth failures) that says the provider's own [providerMessage], or "[failure] (status)".
 */
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

internal suspend inline fun SourceHttpClient.getChecked(
    siteLabel: String,
    url: String,
    query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    noinline authBlockedBody: (String) -> Boolean = { false },
    noinline providerMessage: (String) -> String? = { null },
    notFound: () -> String? = { null },
): String {
    val response = sourceRequest(siteLabel) { get(url = url, query = query, headers = headers) }
    if (response.statusCode == 404) notFound()?.let { return it }
    return response.requireSuccess("$siteLabel request failed", authBlockedBody, providerMessage).body
}

/** The keys [use] attaches to a site's API calls; a site that requires keys fails before the request without them. */
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

/**
 * Runs a direct request whose Cloudflare challenge has a mirror to fall back on. A client with an outage tracker
 * throws a challenge it recognizes, and refuses the host up front while that outage lasts, so such a failure
 * comes back as [CHALLENGED_RESPONSE] for the caller's [matchesChallenge] check to route to the mirror. The
 * outage stays recorded, so later requests skip the direct host. Other transport failures are
 * [sourceNetworkFailure]s.
 */
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

/** The answer a Cloudflare challenge gives, standing in for one the HTTP client already turned into an outage. */
internal val CHALLENGED_RESPONSE = SourceHttpResponse(
    statusCode = 403,
    body = "",
    headers = mapOf("cf-mitigated" to listOf("challenge")),
)
