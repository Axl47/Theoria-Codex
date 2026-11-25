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

interface ChallengeBrowser {
    fun mayWait(): Boolean

    fun pass(request: Request, passed: () -> Boolean, cancelled: () -> Boolean): Boolean
}

fun isCloudflareChallenge(statusCode: Int, header: (String) -> String?): Boolean =
    statusCode in CHALLENGE_CODES && header("cf-mitigated").equals("challenge", ignoreCase = true)

fun Response.isCloudflareChallenge(): Boolean = isCloudflareChallenge(code) { header(it) }

val Response.challengedRequest: Request get() = networkResponse?.request ?: request

fun Request.siteRoot(): Request = Request.Builder()
    .url(url.newBuilder().username("").password("").encodedPath("/").query(null).fragment(null).build())
    .apply { header("User-Agent")?.let { header("User-Agent", it) } }
    .build()

class ChallengeBrowsers(private val cookies: BrowserCookieStore, private val browser: ChallengeBrowser) {
    private val passing = ConcurrentHashMap<String, ReentrantLock>()
    private val browsers = Semaphore(MAX_BROWSERS)

    private val failed = ConcurrentHashMap<String, Long>()

    @Volatile
    private var shownSite: String? = null

    fun showCheck(url: String?) {
        shownSite = url?.let { it.toHttpUrlOrNull()?.host }?.let(HostOutageTracker::normalizeHost)
    }

    fun isShown(host: String): Boolean = shownSite?.let { HostOutageTracker.hostBelongsToSite(host, it) } == true

    fun mayWait(): Boolean = browser.mayWait()

    fun earn(challenged: Request, page: Request, call: Call, stillNeeded: () -> Boolean): Boolean {
        val url = challenged.url.toString()
        val refused = cookieValue(challenged.header("Cookie"), CLEARANCE_COOKIE)
        val host = HostOutageTracker.normalizeHost(challenged.url.host)
        val asked = System.nanoTime()
        val lock = passing.computeIfAbsent(host) { ReentrantLock() }
        while (!lock.tryLock(POLL_MS, TimeUnit.MILLISECONDS)) call.throwIfCanceled()
        return try {
            when {
                cookies.hasNewClearance(url, refused) -> true
                failed[host]?.let { it - asked > 0 } == true -> false
                !stillNeeded() || isShown(challenged.url.host) -> false
                else -> passSilently(page, url, refused, call, stillNeeded).also { if (!it) failed[host] = System.nanoTime() }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun passSilently(page: Request, url: String, refused: String?, call: Call, stillNeeded: () -> Boolean): Boolean {
        while (!browsers.tryAcquire(POLL_MS, TimeUnit.MILLISECONDS)) call.throwIfCanceled()
        try {
            call.throwIfCanceled()
            if (cookies.hasNewClearance(url, refused)) return true
            if (isShown(page.url.host) || !stillNeeded()) return false
            runCatching { cookies.expire(url, CLEARANCE_COOKIE) }
            val passed = browser.pass(page, passed = { cookies.hasNewClearance(url, refused) }, cancelled = call::isCanceled)
            if (!passed) call.throwIfCanceled()
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
        const val MAX_BROWSERS = 2
        private const val POLL_MS = 250L
    }
}

class ChallengePass(
    private val browsers: ChallengeBrowsers,
    private val heldOff: (url: String) -> Boolean,
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
        if (!mayPass(host) || browsers.isShown(host) || heldOff(url) || !browsers.mayWait()) return response
        val refusal = response.newBuilder().body(response.peekBody(CHALLENGE_BODY_LIMIT)).build()
        response.close()
        val earned = browsers.earn(challenged, page(challenged), chain.call()) { !heldOff(url) }
        return if (earned && !heldOff(url)) chain.proceed(request) else refusal
    }

    private companion object {
        const val CHALLENGE_BODY_LIMIT = 64 * 1024L
    }
}

private val CHALLENGE_CODES = setOf(403, 503)
