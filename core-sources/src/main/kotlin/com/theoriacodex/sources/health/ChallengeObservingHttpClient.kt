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

internal fun SourceHttpResponse.isBrowserChallenge(): Boolean =
    HostOutageTracker.outageKindOf(statusCode, headers) == HostOutageKind.CHALLENGE || isVerificationChallenge()
