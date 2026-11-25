package com.theoriacodex.sources.http

import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class HostOutageKind(val userMessage: String) {
    MAINTENANCE("Down for maintenance. Try again later."),

    CHALLENGE("Needs browser verification."),

    UNRESOLVED("Couldn't find this site. If it moved, remove it and add its new address."),

    RATE_LIMITED("Too many requests. Try again later."),
}

data class HostOutage(val kind: HostOutageKind, val untilEpochMs: Long, val refused: RefusedRequest? = null)

data class RefusedRequest(val url: String, val userAgent: String)

class HostUnavailableException(val host: String, val kind: HostOutageKind) : IOException(kind.userMessage)

class HostOutageTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val isOnline: () -> Boolean = { true },
    private val outageMs: Long = DEFAULT_OUTAGE_MS,
) {
    private val state = MutableStateFlow<Map<String, HostOutage>>(emptyMap())

    val outages: StateFlow<Map<String, HostOutage>> = state.asStateFlow()

    private val workingHosts = MutableStateFlow<Set<String>>(emptySet())

    val working: StateFlow<Set<String>> = workingHosts.asStateFlow()

    private val passed = MutableSharedFlow<String>(extraBufferCapacity = PASS_BUFFER)

    val passes: SharedFlow<String> = passed.asSharedFlow()

    private val firstDnsMiss = ConcurrentHashMap<String, Long>()

    fun ensureAvailable(url: String) {
        val host = hostKey(url) ?: return
        rateLimitingSite(host)?.let { throw HostUnavailableException(it, HostOutageKind.RATE_LIMITED) }
        val outage = state.value[host] ?: return
        if (outage.untilEpochMs > clock()) throw HostUnavailableException(host, outage.kind)
        clear(host)
    }

    fun isRateLimited(url: String): Boolean = hostKey(url)?.let(::rateLimitingSite) != null

    fun isSiteRateLimited(url: String): Boolean {
        val site = hostKey(url) ?: return false
        val now = clock()
        return state.value.any { (host, outage) ->
            outage.isActiveRateLimit(now) && (hostBelongsToSite(host, site) || hostBelongsToSite(site, host))
        }
    }

    fun holdsOffChallengePass(url: String): Boolean = isChallenged(url) || isRateLimited(url)

    private fun rateLimitingSite(host: String): String? {
        val now = clock()
        return state.value.entries.firstOrNull { (site, outage) ->
            outage.kind == HostOutageKind.RATE_LIMITED && outage.untilEpochMs > now && hostBelongsToSite(host, site)
        }?.key
    }

    fun recordResponse(url: String, statusCode: Int, headers: Map<String, List<String>>): HostOutageKind? =
        outageKindOf(statusCode, headers)?.also { kind ->
            val longestMs = maxOf(outageMs, MAX_RATE_LIMIT_MS)
            val retryAfterMs = headers.header("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(0L, longestMs / 1_000L)?.times(1_000L)
            val holdMs = if (kind == HostOutageKind.RATE_LIMITED) maxOf(outageMs, retryAfterMs ?: 0L) else outageMs
            record(url, kind, holdMs = holdMs)
        }

    fun recordFailure(url: String, error: IOException) {
        if (error !is UnknownHostException || !isOnline()) return
        val host = hostKey(url) ?: return
        if (confirmsDnsMiss(host)) record(url, HostOutageKind.UNRESOLVED)
    }

    private fun confirmsDnsMiss(host: String): Boolean {
        val now = clock()
        val first = firstDnsMiss[host]
        if (first == null || now - first > DNS_MISS_WINDOW_MS) {
            firstDnsMiss[host] = now
            return false
        }
        if (now - first < DNS_MISS_MIN_GAP_MS) return false
        firstDnsMiss.remove(host)
        return true
    }

    fun record(url: String, kind: HostOutageKind, refused: RefusedRequest? = null, holdMs: Long = outageMs) {
        val host = hostKey(url) ?: return
        workingHosts.update { it - host }
        val now = clock()
        state.update { recorded ->
            val limit = recorded[host]?.takeIf { it.isActiveRateLimit(now) }
            when {
                limit == null -> recorded + (host to HostOutage(kind, now + holdMs, refused))
                kind == HostOutageKind.RATE_LIMITED -> recorded + (host to limit.copy(untilEpochMs = maxOf(limit.untilEpochMs, now + holdMs)))
                else -> recorded
            }
        }
    }

    private fun HostOutage.isActiveRateLimit(now: Long) = kind == HostOutageKind.RATE_LIMITED && untilEpochMs > now

    fun recordChallenge(url: String, refused: RefusedRequest) {
        val host = hostKey(url) ?: return
        workingHosts.update { it - host }
        val now = clock()
        state.update { recorded ->
            val current = recorded[host]?.takeIf { it.untilEpochMs > now }
            when {
                current == null -> recorded + (host to HostOutage(HostOutageKind.CHALLENGE, now + outageMs, refused))
                current.kind == HostOutageKind.CHALLENGE && current.refused == null -> recorded + (host to current.copy(refused = refused))
                current.kind == HostOutageKind.CHALLENGE || current.kind == HostOutageKind.RATE_LIMITED -> recorded
                else -> recorded + (host to HostOutage(HostOutageKind.CHALLENGE, now + outageMs, refused))
            }
        }
    }

    fun isChallenged(url: String): Boolean {
        val outage = hostKey(url)?.let(state.value::get) ?: return false
        return outage.kind == HostOutageKind.CHALLENGE && outage.untilEpochMs > clock()
    }

    fun recordAnswer(url: String, statusCode: Int) {
        when (statusCode) {
            in HTTP_SUCCESS -> setWorking(url, true)
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> setWorking(url, false)
        }
    }

    fun setWorking(url: String, isWorking: Boolean) {
        val host = hostKey(url) ?: return
        workingHosts.update { if (isWorking) it + host else it - host }
    }

    fun pass(host: String) {
        val key = normalizeHost(host)
        clear(key)
        passed.tryEmit(key)
    }

    fun clear(host: String) {
        val key = normalizeHost(host)
        val now = clock()
        state.update { recorded ->
            val outage = recorded[key]
            if (outage == null || outage.isActiveRateLimit(now)) recorded else recorded - key
        }
    }

    companion object {
        const val DEFAULT_OUTAGE_MS = 10 * 60 * 1000L
        private const val MAX_RATE_LIMIT_MS = 60 * 60 * 1000L
        private const val DNS_MISS_MIN_GAP_MS = 5_000L
        private const val DNS_MISS_WINDOW_MS = 2 * 60 * 1000L
        private const val PASS_BUFFER = 16

        fun normalizeHost(host: String): String = host.lowercase(Locale.ROOT).removePrefix("www.")

        fun hostBelongsToSite(host: String, site: String): Boolean {
            val candidate = normalizeHost(host)
            val root = normalizeHost(site)
            if (candidate == root) return true
            val dot = candidate.length - root.length - 1
            return dot >= 0 && candidate[dot] == '.' && candidate.endsWith(root)
        }

        fun hostKey(url: String): String? = runCatching { URI(url).host }.getOrNull()?.let(::normalizeHost)

        fun outageKindOf(statusCode: Int, headers: Map<String, List<String>>): HostOutageKind? = when {
            isCloudflareChallenge(statusCode, headers::header) -> HostOutageKind.CHALLENGE
            statusCode == HTTP_TOO_MANY_REQUESTS -> HostOutageKind.RATE_LIMITED
            statusCode in OUTAGE_STATUSES -> HostOutageKind.MAINTENANCE
            else -> null
        }

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_SUCCESS = 200..299
        private val OUTAGE_STATUSES = setOf(502, 503, 504) + (520..530)
    }
}
