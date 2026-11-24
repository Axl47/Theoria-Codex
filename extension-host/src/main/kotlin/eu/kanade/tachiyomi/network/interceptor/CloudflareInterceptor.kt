// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
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
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Response

class CloudflareInterceptor internal constructor(
    private val pass: ChallengePass,
    private val outages: HostOutageTracker,
) : Interceptor {
    private val refusals = ConcurrentHashMap.newKeySet<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = pass.intercept(chain)
        if (!response.isCloudflareChallenge()) return response.also(::clearRecovered).also(::reportRefusal)
        response.close()
        val challenged = response.challengedRequest
        val url = challenged.url.toString()
        outages.recordChallenge(url, RefusedRequest(url, challenged.header("User-Agent").orEmpty()))
        throw HostUnavailableException(HostOutageTracker.normalizeHost(challenged.url.host), HostOutageKind.CHALLENGE)
    }

    private fun reportRefusal(response: Response) {
        if (response.code != HTTP_FORBIDDEN && response.code != HTTP_UNAVAILABLE) return
        val sent = response.challengedRequest
        if (!refusals.add("${sent.url.host} ${response.code}")) return
        val clearance = if (cookieValue(sent.header("Cookie"), CLEARANCE_COOKIE) != null) "with" else "without"
        val server = if (response.header("Server").equals("cloudflare", ignoreCase = true)) "Cloudflare" else "another server"
        Diagnostics.report("extension-network", "${sent.url.host} refused ${response.code} $clearance a clearance, no challenge, from $server")
    }

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
