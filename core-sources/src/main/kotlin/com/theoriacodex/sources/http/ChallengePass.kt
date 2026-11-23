package com.theoriacodex.sources.http

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Where [ChallengeBrowsers] pass a check out of sight; a port so its decisions are tested without a WebView. */
interface ChallengeBrowser {
    /** Whether the calling thread may wait for a pass: not the main thread, which the browser itself runs on. */
    fun mayWait(): Boolean

    /**
     * Loads [request] until [passed] answers true, the time runs out or [cancelled] answers true, and says whether it
     * passed. A missing browser, or one whose renderer died, is a failed pass, never an exception.
     */
    fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean): Boolean
}

/**
 * Cloudflare's own challenge: a refusal with the header only Cloudflare marks challenges with, which its blocks lack.
 * A 503 challenge is a challenge, never maintenance. [header] reads one of the answer's headers by name.
 */
fun isCloudflareChallenge(statusCode: Int, header: (String) -> String?): Boolean =
    statusCode in CHALLENGE_CODES && header("cf-mitigated").equals("challenge", ignoreCase = true)

fun Response.isCloudflareChallenge(): Boolean = isCloudflareChallenge(code) { header(it) }

/** The request a challenge answered: after a redirect it can be another host's, and only it shows the cookies sent. */
val Response.challengedRequest: Request get() = networkResponse?.request ?: request

/**
 * What a hidden browser may load for [this] request when it can carry secrets, such as an API key in its query: its
 * site's root, sending only its agent. The clearance cookie that page earns covers the whole host.
 */
fun Request.siteRoot(): Request = Request.Builder()
    .url(url.newBuilder().username("").password("").encodedPath("/").query(null).fragment(null).build())
    .apply { header("User-Agent")?.let { header("User-Agent", it) } }
    .build()

/**
 * The hidden browsers every client shares: one pass per host at a time and at most [MAX_BROWSERS] across hosts,
 * whichever client asked, since all of them write the same [cookies]. A request challenged while its host's pass runs
 * waits for it and then uses the cookie it earned. Every wait gives up as soon as the call is cancelled. No hidden
 * browser runs for the site whose check the user has on screen ([showCheck]).
 */
class ChallengeBrowsers(private val cookies: BrowserCookieStore, private val browser: ChallengeBrowser) {
    private val passing = ConcurrentHashMap<String, ReentrantLock>()
    private val browsers = Semaphore(MAX_BROWSERS)

    @Volatile
    private var shownSite: String? = null

    /**
     * The address of the browser check now on screen, or null once none is. A hidden pass for its site would first
     * expire the clearance cookie the visible browser just earned, then race it with a second browser.
     */
    fun showCheck(url: String?) {
        shownSite = url?.let { it.toHttpUrlOrNull()?.host }?.let(HostOutageTracker::normalizeHost)
    }

    /** Whether [host] belongs to the site whose browser check is on screen. */
    fun isShown(host: String): Boolean = shownSite?.let { HostOutageTracker.hostBelongsToSite(host, it) } == true

    /** Whether the calling thread may wait for a pass: not the main thread, which the browser itself needs. */
    fun mayWait(): Boolean = browser.mayWait()

    /**
     * Whether a clearance cookie other than the one [challenged] carried is in the store now: one earned meanwhile, or
     * one a hidden browser earns by loading [page] when [stillNeeded], asked once this holds the host's turn.
     */
    fun earn(challenged: Request, page: Request, call: Call, stillNeeded: () -> Boolean): Boolean {
        val url = challenged.url.toString()
        // A pass that finished while this request's answer was on the way has stored another clearance, which must
        // count as new rather than be taken for the refused one and deleted.
        val refused = cookieValue(challenged.header("Cookie"), CLEARANCE_COOKIE)
        val lock = passing.computeIfAbsent(HostOutageTracker.normalizeHost(challenged.url.host)) { ReentrantLock() }
        while (!lock.tryLock(POLL_MS, TimeUnit.MILLISECONDS)) call.throwIfCanceled()
        return try {
            cookies.hasNewClearance(url, refused) || stillNeeded() && !isShown(challenged.url.host) && passSilently(page, url, refused, call)
        } finally {
            lock.unlock()
        }
    }

    private fun passSilently(page: Request, url: String, refused: String?, call: Call): Boolean {
        while (!browsers.tryAcquire(POLL_MS, TimeUnit.MILLISECONDS)) call.throwIfCanceled()
        try {
            call.throwIfCanceled()
            // The wait for a browser can be long: the user may have opened this site's check, or passed it, meanwhile.
            if (cookies.hasNewClearance(url, refused)) return true
            if (isShown(page.url.host)) return false
            // As in Mihon, the refused cookie goes first, so only one the browser earns now can count.
            runCatching { cookies.expire(url, CLEARANCE_COOKIE) }
            val passed = browser.pass(page, passed = { cookies.hasNewClearance(url, refused) }, cancelled = call::isCanceled)
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
        /** Hidden browsers running at once, however many hosts and clients challenge together. */
        const val MAX_BROWSERS = 2
        private const val POLL_MS = 250L
    }
}

/**
 * Passes a Cloudflare browser check out of sight, as Mihon does: when Cloudflare answers with a challenge, one of the
 * shared [browsers] loads [page] (the challenged request itself unless it may carry secrets) with the request's own
 * agent, and once a clearance cookie other than the refused one appears the request is sent once more, carrying it.
 * Every other answer, a hard block or a maintenance page included, passes through untouched, and so does a challenge
 * no pass could clear; the client that carries this decides how that fails.
 *
 * No pass is tried for a host [mayPass] refuses, while [isChallenged] says the host's check is already the user's to
 * pass (so a screen of covers does not wait on a hidden browser again), while the user has that site's check on screen
 * ([ChallengeBrowsers.showCheck]), or from the main thread.
 */
class ChallengePass(
    private val browsers: ChallengeBrowsers,
    private val isChallenged: (url: String) -> Boolean,
    private val mayPass: (host: String) -> Boolean = { true },
    private val page: (challenged: Request) -> Request = { it },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isCloudflareChallenge()) return response
        val challenged = response.challengedRequest
        val url = challenged.url.toString()
        val host = HostOutageTracker.normalizeHost(challenged.url.host)
        if (!mayPass(host) || browsers.isShown(host) || isChallenged(url) || !browsers.mayWait()) return response
        // Kept in memory: the connection would otherwise sit open through the pass, and a failed one answers with it.
        val refusal = response.newBuilder().body(response.peekBody(CHALLENGE_BODY_LIMIT)).build()
        response.close()
        // A pass that failed while this request waited has left the check to the user.
        val earned = browsers.earn(challenged, page(challenged), chain.call()) { !isChallenged(url) }
        return if (earned) chain.proceed(request) else refusal
    }

    private companion object {
        const val CHALLENGE_BODY_LIMIT = 64 * 1024L
    }
}

private val CHALLENGE_CODES = setOf(403, 503)
