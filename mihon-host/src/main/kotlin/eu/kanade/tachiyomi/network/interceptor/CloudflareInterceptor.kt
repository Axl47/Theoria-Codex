// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// The simple class name is fixed by extension binaries, which look for it in the client they rebuild.
package eu.kanade.tachiyomi.network.interceptor

import com.theoriacodex.domain.adapter.BrowserCheck
import com.theoriacodex.mihonhost.BrowserCheckException
import com.theoriacodex.mihonhost.ChallengeBrowser
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.CLEARANCE_COOKIE
import com.theoriacodex.sources.http.HostOutageTracker
import com.theoriacodex.sources.http.cookieValue
import com.theoriacodex.sources.http.hasNewClearance
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Passes a Cloudflare browser check out of sight, as Mihon does: when Cloudflare answers with a challenge, [browser]
 * loads the challenged address in a browser no one sees, with the request's own agent, and once a clearance cookie
 * other than the refused one appears the request is sent once more, carrying it. A check that cannot be passed that
 * way ends in a [BrowserCheckException], which the Series screens offer to open in a visible browser. Every other
 * answer, a hard block or a maintenance page included, passes through untouched.
 *
 * A failed pass is remembered per host for [FAILURE_MEMORY_MS]: another challenge from that host then fails at once
 * instead of waiting again, so neither a screen of covers nor the background chapter check holds a thread per request
 * for the whole wait. Requests still go out meanwhile, so a check passed in the visible browser counts from the next
 * request, and any answer from the host that is not a challenge forgets the failure. One pass runs per host at a time;
 * a request challenged meanwhile waits for it and then uses the cookie it earned. Every wait gives up as soon as the
 * call is cancelled, and a call on the main thread, which the browser itself needs, never waits.
 */
class CloudflareInterceptor internal constructor(
    private val cookies: BrowserCookieStore,
    private val browser: ChallengeBrowser,
    private val now: () -> Long = System::currentTimeMillis,
) : Interceptor {
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val passing = ConcurrentHashMap<String, ReentrantLock>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isChallenge()) {
            if (failedAt.isNotEmpty()) failedAt.remove(HostOutageTracker.normalizeHost(response.request.url.host))
            return response
        }
        response.close()
        // The request the site actually challenged: after a redirect it can be another host's, and only it shows the
        // cookies that were sent.
        val challenged = response.networkResponse?.request ?: response.request
        val check = challenged.check()
        if (!browser.mayWait()) throw BrowserCheckException(check)
        val host = HostOutageTracker.normalizeHost(challenged.url.host)
        val url = challenged.url.toString()
        // The clearance this request carried. A pass that finished while its answer was on the way has stored another,
        // which must count as new rather than be taken for the refused one and deleted.
        val refused = cookieValue(challenged.header("Cookie"), CLEARANCE_COOKIE)
        val call = chain.call()
        val lock = passing.computeIfAbsent(host) { ReentrantLock() }
        while (!lock.tryLock(POLL_MS, TimeUnit.MILLISECONDS)) call.throwIfCanceled()
        try {
            if (!cookies.hasNewClearance(url, refused) && !passSilently(challenged, host, refused, call)) throw BrowserCheckException(check)
        } finally {
            lock.unlock()
        }
        val retried = chain.proceed(request)
        if (!retried.isChallenge()) return retried
        // The cookie did not satisfy the site, so a visible browser is the only way left.
        retried.close()
        failedAt[host] = now()
        throw BrowserCheckException(check)
    }

    /** Whether the hidden browser earned a clearance cookie other than [refused]. Not tried while a failure is remembered. */
    private fun passSilently(challenged: Request, host: String, refused: String?, call: Call): Boolean {
        val failed = failedAt[host]
        if (failed != null && now() - failed < FAILURE_MEMORY_MS) return false
        call.throwIfCanceled()
        val url = challenged.url.toString()
        // As in Mihon, the refused cookie goes first, so only one the browser earns now can count.
        runCatching { cookies.expire(url, CLEARANCE_COOKIE) }
        val passed = browser.pass(challenged, passed = { cookies.hasNewClearance(url, refused) }, cancelled = call::isCanceled)
        // A pass cut short by cancellation says nothing about the site, so it is not remembered as a failure.
        if (!passed) call.throwIfCanceled()
        if (passed) {
            failedAt.remove(host)
            // The WebView writes cookies to disk lazily; a clearance lost to the app being closed would mean another check.
            runCatching { cookies.flush() }
        } else {
            failedAt[host] = now()
        }
        return passed
    }

    /** Cloudflare's own challenge: its server, a refusal, and the header it marks challenges with, which blocks lack. */
    private fun Response.isChallenge(): Boolean =
        code in CHALLENGE_CODES && header("Server") in CLOUDFLARE_SERVERS && header("cf-mitigated").equals("challenge", ignoreCase = true)

    private fun Request.check() = BrowserCheck(url.toString(), header("User-Agent").orEmpty())

    private fun Call.throwIfCanceled() {
        if (isCanceled()) throw IOException("Canceled")
    }

    internal companion object {
        /** How long a host whose check could not be passed out of sight fails at once. */
        const val FAILURE_MEMORY_MS = 5 * 60 * 1000L
        private const val POLL_MS = 250L
        private val CHALLENGE_CODES = setOf(403, 503)
        private val CLOUDFLARE_SERVERS = setOf("cloudflare-nginx", "cloudflare")
    }
}
