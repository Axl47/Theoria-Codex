package com.theoriacodex.sources.http

import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Why a host is considered down; [userMessage] is what a failed request reports. */
enum class HostOutageKind(val userMessage: String) {
    /** Maintenance or gateway errors: 502, 503, 504, and Cloudflare's 520–530. */
    MAINTENANCE("Down for maintenance. Try again later."),

    /** A browser challenge answered instead of the API. */
    CHALLENGE("Needs browser verification. Open Settings → Sites."),

    /** The domain doesn't resolve while the device is online. */
    UNRESOLVED("Couldn't find this site. If it moved, remove it and add its new address."),
}

data class HostOutage(val kind: HostOutageKind, val untilEpochMs: Long)

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

    /** A transport failure: only an unresolvable domain while online is an outage. */
    fun recordFailure(url: String, error: IOException) {
        if (error is UnknownHostException && isOnline()) record(url, HostOutageKind.UNRESOLVED)
    }

    fun record(url: String, kind: HostOutageKind) {
        val host = hostKey(url) ?: return
        state.update { it + (host to HostOutage(kind, clock() + outageMs)) }
    }

    /** Forgets [host]'s outage so the next request tries the network again. */
    fun clear(host: String) {
        val key = normalizeHost(host)
        state.update { if (key in it) it - key else it }
    }

    companion object {
        const val DEFAULT_OUTAGE_MS = 10 * 60 * 1000L

        /** The form outages are keyed by: lowercase, without `www.`. */
        fun normalizeHost(host: String): String = host.lowercase(Locale.ROOT).removePrefix("www.")

        /** Whether [host] is [site] itself or a subdomain of it, such as a media CDN; both are normalized first. */
        fun hostBelongsToSite(host: String, site: String): Boolean {
            val candidate = normalizeHost(host)
            val root = normalizeHost(site)
            return candidate == root || candidate.endsWith(".$root")
        }

        fun hostKey(url: String): String? = runCatching { URI(url).host }.getOrNull()?.let(::normalizeHost)

        /** What an answer's status and headers say about the host; null when it isn't down. */
        fun outageKindOf(statusCode: Int, headers: Map<String, List<String>>): HostOutageKind? = when {
            statusCode in OUTAGE_STATUSES -> HostOutageKind.MAINTENANCE
            statusCode == HTTP_FORBIDDEN && headers.header("cf-mitigated").equals("challenge", ignoreCase = true) ->
                HostOutageKind.CHALLENGE
            else -> null
        }

        private const val HTTP_FORBIDDEN = 403
        private val OUTAGE_STATUSES = setOf(502, 503, 504) + (520..530)
    }
}

/** Whether a 5xx answer's Retry-After asks for a wait too long to spend inside one request. */
internal fun Map<String, List<String>>.retryAfterTooLong(): Boolean =
    header("Retry-After")?.trim()?.toLongOrNull()?.let { it > MAX_INLINE_RETRY_AFTER_SECONDS } ?: false

private fun Map<String, List<String>>.header(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

private const val MAX_INLINE_RETRY_AFTER_SECONDS = 3L
