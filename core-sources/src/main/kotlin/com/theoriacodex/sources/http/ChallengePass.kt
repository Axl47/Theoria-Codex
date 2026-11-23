package com.theoriacodex.sources.http

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Where [ChallengePass] passes a check out of sight; a port so its decisions are tested without a WebView. */
interface ChallengeBrowser {
    /** Whether the calling thread may wait for a pass: not the main thread, which the browser itself runs on. */
    fun mayWait(): Boolean

    /**
     * Loads [request] until [passed] answers true, the time runs out or [cancelled] answers true, and says whether it
     * passed. A missing browser, or one whose renderer died, is a failed pass, never an exception.
     */
    fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean): Boolean
}

/** Cloudflare's own challenge: its server, a refusal, and the header it marks challenges with, which blocks lack. */
fun Response.isCloudflareChallenge(): Boolean =
    code in CHALLENGE_CODES && header("Server") in CLOUDFLARE_SERVERS && header("cf-mitigated").equals("challenge", ignoreCase = true)

/** The request a challenge answered: after a redirect it can be another host's, and only it shows the cookies sent. */
val Response.challengedRequest: Request get() = networkResponse?.request ?: request

/**
 * Passes a Cloudflare browser check out of sight, as Mihon does: when Cloudflare answers with a challenge, [browser]
 * loads the challenged address in a browser no one sees, with the request's own agent, and once a clearance cookie
 * other than the refused one appears the request is sent once more, carrying it. Every other answer, a hard block or
 * a maintenance page included, passes through untouched, and so does a challenge no pass could clear; the client that
 * carries this decides how that fails.
 *
 * No pass is tried for a host [mayPass] refuses, while [isChallenged] says the host's check is already the user's to
 * pass (so neither a screen of covers nor the user's own check waits on a hidden browser again), or from the main
 * thread, which the browser itself needs. One pass runs per host at a time, and at most [MAX_BROWSERS] across hosts; a
 * request challenged meanwhile waits for it and then uses the cookie it earned. Every wait gives up as soon as the
 * call is cancelled.
 */
class ChallengePass(
    private val cookies: BrowserCookieStore,
    private val browser: ChallengeBrowser,
    private val isChallenged: (url: String) -> Boolean,
    private val mayPass: (host: String) -> Boolean = { true },
) : Interceptor {
    private val passing = ConcurrentHashMap<String, ReentrantLock>()
    private val browsers = Semaphore(MAX_BROWSERS)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isCloudflareChallenge()) return response
        val challenged = response.challengedRequest
        val url = challenged.url.toString()
        val host = HostOutageTracker.normalizeHost(challenged.url.host)
        if (!mayPass(host) || isChallenged(url) || !browser.mayWait()) return response
        // Kept in memory: the connection would otherwise sit open through the pass, and a failed one answers with it.
        val refusal = response.newBuilder().body(response.peekBody(CHALLENGE_BODY_LIMIT)).build()
        response.close()
        // The clearance this request carried. A pass that finished while its answer was on the way has stored another,
        // which must count as new rather than be taken for the refused one and deleted.
        val refused = cookieValue(challenged.header("Cookie"), CLEARANCE_COOKIE)
        val call = chain.call()
        val lock = passing.computeIfAbsent(host) { ReentrantLock() }
        while (!lock.tryLock(POLL_MS, TimeUnit.MILLISECONDS)) call.throwIfCanceled()
        val earned = try {
            // A pass that failed while this request waited has left the check to the user.
            cookies.hasNewClearance(url, refused) || !isChallenged(url) && passSilently(challenged, url, refused, call)
        } finally {
            lock.unlock()
        }
        return if (earned) chain.proceed(request) else refusal
    }

    /** Whether the hidden browser earned a clearance cookie other than [refused]. */
    private fun passSilently(challenged: Request, url: String, refused: String?, call: Call): Boolean {
        while (!browsers.tryAcquire(POLL_MS, TimeUnit.MILLISECONDS)) call.throwIfCanceled()
        try {
            call.throwIfCanceled()
            // As in Mihon, the refused cookie goes first, so only one the browser earns now can count.
            runCatching { cookies.expire(url, CLEARANCE_COOKIE) }
            val passed = browser.pass(challenged, passed = { cookies.hasNewClearance(url, refused) }, cancelled = call::isCanceled)
            // A pass cut short by cancellation says nothing about the site, so it must not be recorded as a failure.
            if (!passed) call.throwIfCanceled()
            // The WebView writes cookies to disk lazily; a clearance lost to the app being closed would mean another check.
            if (passed) runCatching { cookies.flush() }
            return passed
        } finally {
            browsers.release()
        }
    }

    private fun Call.throwIfCanceled() {
        if (isCanceled()) throw IOException("Canceled")
    }

    companion object {
        /** Hidden browsers running at once, however many hosts challenge together. */
        const val MAX_BROWSERS = 2
        private const val POLL_MS = 250L
        private const val CHALLENGE_BODY_LIMIT = 64 * 1024L
    }
}

private val CHALLENGE_CODES = setOf(403, 503)
private val CLOUDFLARE_SERVERS = setOf("cloudflare-nginx", "cloudflare")
