// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// The simple class name is fixed by extension binaries, which look for it in the client they rebuild.
package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.sources.http.CLEARANCE_COOKIE
import com.theoriacodex.sources.http.ChallengePass
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.HostUnavailableException
import com.theoriacodex.sources.http.RefusedRequest
import com.theoriacodex.sources.http.challengedRequest
import com.theoriacodex.sources.http.cookieValue
import com.theoriacodex.sources.http.isCloudflareChallenge
import okhttp3.Interceptor
import okhttp3.Response

/**
 * The extensions' way through a Cloudflare browser check: [pass] tries it out of sight, as Mihon does, and a
 * challenge still standing after that is recorded in [outages] with the request and agent it refused, then fails the
 * request as [HostUnavailableException]. Extensions parse whatever answer they get, so a challenge page must never
 * reach them. The source then shows as needing browser verification, like any challenged source, and the app's check
 * page passes it with that agent. While the record lasts, [pass] tries no hidden browser for the host, and the record
 * is not renewed, so it still expires on time; a successful answer from the host passes it at once. A record another
 * client made without the refused request gains this one's, or the check page would have nothing to open.
 */
class CloudflareInterceptor internal constructor(
    private val pass: ChallengePass,
    private val outages: HostOutageTracker,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = pass.intercept(chain)
        if (!response.isCloudflareChallenge()) return response.also(::clearRecovered).also(::reportRefusal)
        response.close()
        val challenged = response.challengedRequest
        val url = challenged.url.toString()
        outages.recordChallenge(url, RefusedRequest(url, challenged.header("User-Agent").orEmpty()))
        throw HostUnavailableException(HostOutageTracker.normalizeHost(challenged.url.host), HostOutageKind.CHALLENGE)
    }

    /**
     * Records a refusal that is not a challenge, which reaches the extension as a plain HTTP error: whether it carried a
     * clearance tells a rejected clearance (such as one from a hidden pass) from a site that refuses outright.
     */
    private fun reportRefusal(response: Response) {
        if (response.code != HTTP_FORBIDDEN && response.code != HTTP_UNAVAILABLE) return
        val sent = response.challengedRequest
        val clearance = if (cookieValue(sent.header("Cookie"), CLEARANCE_COOKIE) != null) "with" else "without"
        Diagnostics.report(
            "extension-network",
            "${sent.url.host} refused ${response.code} $clearance a clearance, no challenge (server ${response.header("Server") ?: "unknown"})",
        )
    }

    /** Ends the challenge record of a host that answered [response] successfully; looks only while records exist. */
    private fun clearRecovered(response: Response) {
        val recorded = outages.outages.value
        if (recorded.isEmpty() || !response.isSuccessful) return
        val host = HostOutageTracker.normalizeHost(response.request.url.host)
        if (recorded[host]?.kind == HostOutageKind.CHALLENGE) outages.pass(host)
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
        const val HTTP_UNAVAILABLE = 503
    }
}
