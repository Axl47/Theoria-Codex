// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// The simple class name is fixed by extension binaries, which look for it in the client they rebuild.
package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.sources.http.ChallengePass
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.HostUnavailableException
import com.theoriacodex.sources.http.RefusedRequest
import com.theoriacodex.sources.http.challengedRequest
import com.theoriacodex.sources.http.isCloudflareChallenge
import okhttp3.Interceptor
import okhttp3.Response

/**
 * The extensions' way through a Cloudflare browser check: [pass] tries it out of sight, as Mihon does, and a
 * challenge still standing after that is recorded in [outages] with the request and agent it refused, then fails the
 * request as [HostUnavailableException]. Extensions parse whatever answer they get, so a challenge page must never
 * reach them. The source then shows as needing browser verification, like any challenged source, and the app's check
 * page passes it with that agent. While the record lasts, [pass] tries no hidden browser for the host, and the record
 * is not renewed, so it still expires on time.
 */
class CloudflareInterceptor internal constructor(
    private val pass: ChallengePass,
    private val outages: HostOutageTracker,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = pass.intercept(chain)
        if (!response.isCloudflareChallenge()) return response
        response.close()
        val challenged = response.challengedRequest
        val url = challenged.url.toString()
        if (!outages.isChallenged(url)) {
            outages.record(url, HostOutageKind.CHALLENGE, RefusedRequest(url, challenged.header("User-Agent").orEmpty()))
        }
        throw HostUnavailableException(HostOutageTracker.normalizeHost(challenged.url.host), HostOutageKind.CHALLENGE)
    }
}
