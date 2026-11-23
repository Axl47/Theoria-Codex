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

/** Why a host is considered down; [userMessage] is what a failed request reports. */
enum class HostOutageKind(val userMessage: String) {
    /** Maintenance or gateway errors: 502, 503, 504, and Cloudflare's 520–530. */
    MAINTENANCE("Down for maintenance. Try again later."),

    /** A browser challenge answered instead of the API. */
    CHALLENGE("Needs browser verification."),

    /** The domain doesn't resolve while the device is online. */
    UNRESOLVED("Couldn't find this site. If it moved, remove it and add its new address."),
}

/** [refused] is the challenged request, where the client that recorded the outage knows it. */
data class HostOutage(val kind: HostOutageKind, val untilEpochMs: Long, val refused: RefusedRequest? = null)

/**
 * A request a browser check refused: [url] and the [userAgent] that asked for it. A clearance cookie is bound to the
 * agent that earned it, so a browser that passes the check for this request must send the same one.
 */
data class RefusedRequest(val url: String, val userAgent: String)

/** A request refused without touching the network because its host is known to be down. */
class HostUnavailableException(val host: String, val kind: HostOutageKind) : IOException(kind.userMessage)

/**
 * A per-host circuit breaker shared by the source HTTP clients. Once a host answers like it is down,
 * further requests to it fail at once (as [HostUnavailableException]) until the outage expires after
 * [outageMs] or is [clear]ed, instead of every feature waiting on it in turn. In memory only.
 * [isOnline] keeps an offline device from marking every host unresolved.
 */
class HostOutageTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val isOnline: () -> Boolean = { true },
    private val outageMs: Long = DEFAULT_OUTAGE_MS,
) {
    private val state = MutableStateFlow<Map<String, HostOutage>>(emptyMap())

    /** Recorded outages by host (lowercase, without `www.`); expired ones linger until touched. */
    val outages: StateFlow<Map<String, HostOutage>> = state.asStateFlow()

    private val workingHosts = MutableStateFlow<Set<String>>(emptySet())

    /** Hosts proven to work since the app started, in the same form as [outages]; an outage removes one. */
    val working: StateFlow<Set<String>> = workingHosts.asStateFlow()

    private val passed = MutableSharedFlow<String>(extraBufferCapacity = PASS_BUFFER)

    /**
     * Hosts that just got through their browser check ([pass]), in the same form as [outages]. Unlike a challenge that
     * simply expired, which would fail again, a reason for a screen that was waiting on the check to load again.
     */
    val passes: SharedFlow<String> = passed.asSharedFlow()

    private val firstDnsMiss = ConcurrentHashMap<String, Long>()

    /** Throws if [url]'s host is in an active outage; drops an expired one. */
    fun ensureAvailable(url: String) {
        val host = hostKey(url) ?: return
        val outage = state.value[host] ?: return
        if (outage.untilEpochMs > clock()) throw HostUnavailableException(host, outage.kind)
        clear(host)
    }

    /** Records an outage when [statusCode] (and headers) say the host is down; returns its kind. */
    fun recordResponse(url: String, statusCode: Int, headers: Map<String, List<String>>): HostOutageKind? =
        outageKindOf(statusCode, headers)?.also { record(url, it) }

    /**
     * A transport failure: only an unresolvable domain while online is an outage, and only once a second
     * lookup has failed too. A network that is still coming up fails every lookup for a moment, and one
     * pass over the sources would otherwise mark them all missing.
     */
    fun recordFailure(url: String, error: IOException) {
        if (error !is UnknownHostException || !isOnline()) return
        val host = hostKey(url) ?: return
        if (confirmsDnsMiss(host)) record(url, HostOutageKind.UNRESOLVED)
    }

    /** True for a miss far enough after [host]'s first to be a separate failure, yet recent enough to relate to it. */
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

    fun record(url: String, kind: HostOutageKind, refused: RefusedRequest? = null) {
        val host = hostKey(url) ?: return
        workingHosts.update { it - host }
        state.update { it + (host to HostOutage(kind, clock() + outageMs, refused)) }
    }

    /**
     * A challenge that refused [refused]: recorded when [url]'s host has no active one, and otherwise not renewed, so it
     * still expires on time. An active one recorded without the refused request (by a client that doesn't know it) gains
     * it, since that is what the check page opens.
     */
    fun recordChallenge(url: String, refused: RefusedRequest) {
        val host = hostKey(url) ?: return
        val now = clock()
        val active = state.value[host]?.takeIf { it.kind == HostOutageKind.CHALLENGE && it.untilEpochMs > now }
        if (active == null) return record(url, HostOutageKind.CHALLENGE, refused)
        if (active.refused == null) state.update { recorded -> recorded + (host to active.copy(refused = refused)) }
    }

    /** Whether [url]'s host is in an active challenge outage: its browser check is the user's to pass now. */
    fun isChallenged(url: String): Boolean {
        val outage = hostKey(url)?.let(state.value::get) ?: return false
        return outage.kind == HostOutageKind.CHALLENGE && outage.untilEpochMs > clock()
    }

    /** A real answer from [url]'s host: success proves it works this session, a refusal of its keys takes that back. */
    fun recordAnswer(url: String, statusCode: Int) {
        when (statusCode) {
            in HTTP_SUCCESS -> setWorking(url, true)
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> setWorking(url, false)
        }
    }

    /** Records whether [url]'s host just proved it works, or stopped working, for the session's status marks. */
    fun setWorking(url: String, isWorking: Boolean) {
        val host = hostKey(url) ?: return
        workingHosts.update { if (isWorking) it + host else it - host }
    }

    /** [host] got through its browser check: forgets its outage and announces it on [passes]. */
    fun pass(host: String) {
        val key = normalizeHost(host)
        clear(key)
        passed.tryEmit(key)
    }

    /** Forgets [host]'s outage so the next request tries the network again. */
    fun clear(host: String) {
        val key = normalizeHost(host)
        state.update { if (key in it) it - key else it }
    }

    companion object {
        const val DEFAULT_OUTAGE_MS = 10 * 60 * 1000L
        private const val DNS_MISS_MIN_GAP_MS = 5_000L
        private const val DNS_MISS_WINDOW_MS = 2 * 60 * 1000L
        private const val PASS_BUFFER = 16

        /** The form outages are keyed by: lowercase, without `www.`. */
        fun normalizeHost(host: String): String = host.lowercase(Locale.ROOT).removePrefix("www.")

        /**
         * Whether [host] is [site] itself or a subdomain of it, such as a media CDN; both are normalized first.
         * Runs on every request that reaches the cookie bridge, so it builds no strings of its own.
         */
        fun hostBelongsToSite(host: String, site: String): Boolean {
            val candidate = normalizeHost(host)
            val root = normalizeHost(site)
            if (candidate == root) return true
            val dot = candidate.length - root.length - 1
            return dot >= 0 && candidate[dot] == '.' && candidate.endsWith(root)
        }

        fun hostKey(url: String): String? = runCatching { URI(url).host }.getOrNull()?.let(::normalizeHost)

        /** What an answer's status and headers say about the host; null when it isn't down. A challenge comes first. */
        fun outageKindOf(statusCode: Int, headers: Map<String, List<String>>): HostOutageKind? = when {
            isCloudflareChallenge(statusCode, headers::header) -> HostOutageKind.CHALLENGE
            statusCode in OUTAGE_STATUSES -> HostOutageKind.MAINTENANCE
            else -> null
        }

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private val HTTP_SUCCESS = 200..299
        private val OUTAGE_STATUSES = setOf(502, 503, 504) + (520..530)
    }
}
