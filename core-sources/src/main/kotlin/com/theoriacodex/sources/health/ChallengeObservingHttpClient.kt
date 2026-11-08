package com.theoriacodex.sources.health

import com.theoriacodex.sources.custom.isVerificationChallenge
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.http.toTextResponse
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts browser challenges answered to the probes, so a step that fails behind one can report that
 * the provider could not be checked instead of that it is down.
 */
internal class ChallengeObservingHttpClient(
    private val delegate: SourceHttpClient,
) : SourceHttpClient by delegate {
    private val challenges = AtomicInteger()

    fun challengesSeen(): Int = challenges.get()

    override suspend fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse = delegate.get(url, query, headers).also(::observe)

    override suspend fun getBytes(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        range: SourceByteRange?,
        maxBodyBytes: Int,
    ): SourceByteResponse = delegate.getBytes(url, query, headers, range, maxBodyBytes).also { response ->
        observe(response.toTextResponse())
    }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse = delegate.postForm(url, form, headers).also(::observe)

    override suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): SourceHttpResponse = delegate.postJson(url, body, headers).also(::observe)

    private fun observe(response: SourceHttpResponse) {
        if (response.isBrowserChallenge()) challenges.incrementAndGet()
    }
}

/** A challenge served instead of the API. A maintenance page is an outage, so it does not count. */
internal fun SourceHttpResponse.isBrowserChallenge(): Boolean =
    HostOutageTracker.outageKindOf(statusCode, headers) == HostOutageKind.CHALLENGE || isVerificationChallenge()

/**
 * Runs one probe step. A problem during which a browser challenge answered says nothing about the
 * provider, so it is reported as [ProviderHealthStatus.CANNOT_CHECK].
 */
internal suspend fun observingChallenges(
    challengesSeen: () -> Int,
    step: suspend () -> ProviderProbeStepResult,
): ProviderProbeStepResult {
    val challengesBefore = challengesSeen()
    val result = step()
    val isProblem = result.status == ProviderHealthStatus.FAILED || result.status == ProviderHealthStatus.DEGRADED
    if (!isProblem || challengesSeen() == challengesBefore) return result
    return result.copy(
        status = ProviderHealthStatus.CANNOT_CHECK,
        failureReason = null,
        message = "A browser challenge answered instead of the provider",
    )
}
