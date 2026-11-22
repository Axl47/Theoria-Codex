// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// The simple class name is fixed by extension binaries, which look for it in the client they rebuild.
package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.domain.adapter.BrowserCheck
import com.theoriacodex.mihonhost.BrowserCheckException
import com.theoriacodex.mihonhost.ChallengeBrowser
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.CLEARANCE_COOKIE
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.cookie
import com.theoriacodex.sources.http.hasNewClearance
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Passes a Cloudflare browser check out of sight, as Mihon does: when Cloudflare's server answers 403 or 503, [browser]
 * loads the request in a browser no one sees, with the request's own agent, and once a clearance cookie other than the
 * refused one appears the request is sent once more, carrying it. A check that cannot be passed that way ends in a
 * [BrowserCheckException], which the Series screens offer to open in a visible browser.
 *
 * A failed pass is remembered per host for [FAILURE_MEMORY_MS]: another challenge from that host then fails at once
 * instead of waiting again, so neither a screen of covers nor the background chapter check holds a thread per request
 * for the whole wait. Requests still go out meanwhile, so a check passed in the visible browser counts from the next
 * request, and any answer from the host that is not a challenge forgets the failure. One pass runs per host at a time;
 * a request challenged meanwhile waits for it and then uses the cookie it earned.
 */
class CloudflareInterceptor internal constructor(
    private val cookies: BrowserCookieStore,
    private val browser: ChallengeBrowser,
    private val now: () -> Long = System::currentTimeMillis,
) : Interceptor {
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val passing = ConcurrentHashMap<String, Any>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val host = HostOutageTracker.normalizeHost(request.url.host)
        if (!response.isChallenge()) {
            if (failedAt.isNotEmpty()) failedAt.remove(host)
            return response
        }
        response.close()
        val url = request.url.toString()
        val refused = cookies.cookie(url, CLEARANCE_COOKIE)
        synchronized(passing.computeIfAbsent(host) { Any() }) {
            // A request that waited here for another one's pass finds a new cookie and only needs to send again.
            if (!cookies.hasNewClearance(url, refused) && !passSilently(request, host, refused)) throw BrowserCheckException(request.check())
        }
        val retried = chain.proceed(request)
        if (!retried.isChallenge()) return retried
        // The cookie did not satisfy the site, so a visible browser is the only way left.
        retried.close()
        failedAt[host] = now()
        throw BrowserCheckException(request.check())
    }

    /** Whether the hidden browser earned a clearance cookie other than [refused]. Not tried while a failure is remembered. */
    private fun passSilently(request: Request, host: String, refused: String?): Boolean {
        val failed = failedAt[host]
        if (failed != null && now() - failed < FAILURE_MEMORY_MS) return false
        val url = request.url.toString()
        // As in Mihon, the refused cookie goes first, so only one the browser earns now can count.
        runCatching { cookies.expire(url, CLEARANCE_COOKIE) }
        val passed = browser.pass(request) { cookies.hasNewClearance(url, refused) }
        if (passed) failedAt.remove(host) else failedAt[host] = now()
        return passed
    }

    private fun Response.isChallenge(): Boolean = code in CHALLENGE_CODES && header("Server") in CLOUDFLARE_SERVERS

    private fun Request.check() = BrowserCheck(url.toString(), header("User-Agent").orEmpty())

    internal companion object {
        /** How long a host whose check could not be passed out of sight fails at once. */
        const val FAILURE_MEMORY_MS = 5 * 60 * 1000L
        private val CHALLENGE_CODES = setOf(403, 503)
        private val CLOUDFLARE_SERVERS = setOf("cloudflare-nginx", "cloudflare")
    }
}
