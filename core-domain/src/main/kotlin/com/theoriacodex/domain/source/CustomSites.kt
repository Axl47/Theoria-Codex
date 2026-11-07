package com.theoriacodex.domain.source

import java.net.IDN
import java.security.MessageDigest
import java.util.Locale

// Shared contracts for user-added booru sites. See .docs/exec/custom-booru-sources.html (C1, C3).
// Signatures here are frozen for the parallel step 3/4 threads; change them only through the plan.

/** A booru API dialect served by one adapter. Persisted by [name]; add families by adding entries. */
enum class ProtocolFamily(val displayName: String) {
    DANBOORU("Danbooru"),
    GELBOORU_02("Gelbooru 0.2"),
}

/**
 * Persisted ids of user-added sites: `custom_` + host with dots written as underscores, then
 * `__p<port>` for a non-default port and `__h<8 hex>` (SHA-256 of the path) for an install path.
 * Root installs keep the plain form, e.g. `custom_safebooru_org`. The alphabet avoids every
 * separator existing source-id encodings use (':', '.', '/', ',', '|'). A profile's stored id is
 * authoritative once created; [fromAddress] only names new profiles.
 */
object CustomSourceIds {
    const val PREFIX = "custom_"

    /** Keeps `custom_<id>_<postId>.<ext>` cache file names far below 255 bytes. */
    const val MAX_LENGTH = 96

    private const val PATH_HASH_LENGTH = 8
    private const val LABEL = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
    private val ID_PATTERN = Regex("^${PREFIX}$LABEL(?:_$LABEL)*(?:__p[1-9][0-9]{0,4})?(?:__h[0-9a-f]{$PATH_HASH_LENGTH})?$")

    /** [host] is lowercase ASCII DNS labels, [port] is null for 443, [path] is "" for a root install. */
    fun fromAddress(host: String, port: Int?, path: String): String = buildString {
        append(PREFIX)
        append(host.removePrefix("www.").replace('.', '_'))
        port?.let { append("__p").append(it) }
        if (path.isNotEmpty()) append("__h").append(sha256Hex(path).take(PATH_HASH_LENGTH))
    }

    fun isValid(id: String): Boolean = id.length <= MAX_LENGTH && ID_PATTERN.matches(id)

    /** `custom_safebooru_org__p8443` → `safebooru.org`: the readable host for a site with no profile. */
    fun hostLabel(id: String): String = id.removePrefix(PREFIX).substringBefore("__").replace('_', '.')

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}

enum class SiteAddressError { MALFORMED, NOT_HTTPS, UNSUPPORTED_HOST, TOO_LONG }

sealed interface SiteAddressParse {
    data class Valid(val address: SiteAddress) : SiteAddressParse
    data class Invalid(val error: SiteAddressError) : SiteAddressParse
}

/**
 * A validated HTTPS site root. [baseUrl] has no user-info, query, fragment or trailing '/'; its
 * path stops before the first booru page segment, so pasting a post or search URL finds the root.
 */
class SiteAddress private constructor(
    val baseUrl: String,
    /** Lowercase ASCII (IDNA) host. */
    val host: String,
    /** Null for the default HTTPS port. */
    val port: Int?,
    /** Install path without a trailing '/', or "" for a root install. Case is preserved. */
    val path: String,
) {
    /** Id a new profile for this address receives. Stored ids are authoritative afterwards. */
    val derivedSourceId: String get() = CustomSourceIds.fromAddress(host, port, path)

    override fun equals(other: Any?): Boolean = other is SiteAddress && other.baseUrl == baseUrl
    override fun hashCode(): Int = baseUrl.hashCode()
    override fun toString(): String = baseUrl

    companion object {
        private const val MAX_INPUT_LENGTH = 2_048
        private const val DEFAULT_HTTPS_PORT = 443
        private const val MAX_PORT = 65_535
        private const val MAX_HOST_LENGTH = 253
        private val HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val PAGE_SEGMENTS = setOf("index.php", "posts", "post", "tags", "pools", "wiki_pages", "artists", "comments")

        /** Schemeless input is treated as HTTPS; any other scheme is [SiteAddressError.NOT_HTTPS]. */
        fun parse(input: String): SiteAddressParse {
            val trimmed = input.trim()
            val error = when {
                trimmed.isEmpty() -> SiteAddressError.MALFORMED
                trimmed.length > MAX_INPUT_LENGTH -> SiteAddressError.TOO_LONG
                else -> null
            }
            return error?.let(SiteAddressParse::Invalid) ?: parseUrl(trimmed)
        }

        private fun parseUrl(input: String): SiteAddressParse {
            val schemeEnd = input.indexOf("://")
            val rest = if (schemeEnd < 0) input.trimStart('/') else input.substring(schemeEnd + 3)
            if (schemeEnd >= 0 && !input.substring(0, schemeEnd).equals("https", ignoreCase = true)) {
                return SiteAddressParse.Invalid(SiteAddressError.NOT_HTTPS)
            }
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd)
            val rawPath = rest.substring(authorityEnd).substringBefore('?').substringBefore('#')
            return parseAuthority(authority).fold(
                onSuccess = { (host, port) -> build(host, port, normalizePath(rawPath)) },
                onFailure = { SiteAddressParse.Invalid((it as? AddressException)?.error ?: SiteAddressError.MALFORMED) },
            )
        }

        private fun parseAuthority(authority: String): Result<Pair<String, Int?>> = runCatching {
            when {
                authority.isEmpty() || '@' in authority -> fail(SiteAddressError.MALFORMED)
                authority.startsWith('[') -> fail(SiteAddressError.UNSUPPORTED_HOST)
            }
            val portSeparator = authority.lastIndexOf(':')
            val rawHost = if (portSeparator < 0) authority else authority.substring(0, portSeparator)
            val port = if (portSeparator < 0) null else parsePort(authority.substring(portSeparator + 1))
            normalizeHost(rawHost) to port
        }

        private fun parsePort(raw: String): Int? {
            val port = raw.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: fail(SiteAddressError.MALFORMED)
            return port.takeUnless { it == DEFAULT_HTTPS_PORT }
        }

        private fun normalizeHost(raw: String): String {
            if (raw.isEmpty()) fail(SiteAddressError.MALFORMED)
            val ascii = runCatching { IDN.toASCII(raw) }.getOrNull()?.lowercase(Locale.ROOT)?.removeSuffix(".")
                ?: fail(SiteAddressError.UNSUPPORTED_HOST)
            val valid = ascii.isNotEmpty() && ascii.length <= MAX_HOST_LENGTH && ascii.split('.').all(HOST_LABEL::matches)
            return if (valid) ascii else fail(SiteAddressError.UNSUPPORTED_HOST)
        }

        /** Drops empty segments and everything from the first booru page segment on. */
        private fun normalizePath(rawPath: String): String {
            val segments = rawPath.split('/').filter(String::isNotEmpty)
                .takeWhile { segment -> segment.lowercase(Locale.ROOT) !in PAGE_SEGMENTS }
            return if (segments.isEmpty()) "" else segments.joinToString(separator = "/", prefix = "/")
        }

        private fun build(host: String, port: Int?, path: String): SiteAddressParse {
            val address = SiteAddress("https://$host${port?.let { ":$it" }.orEmpty()}$path", host, port, path)
            return if (address.derivedSourceId.length > CustomSourceIds.MAX_LENGTH) {
                SiteAddressParse.Invalid(SiteAddressError.TOO_LONG)
            } else {
                SiteAddressParse.Valid(address)
            }
        }

        private class AddressException(val error: SiteAddressError) : IllegalArgumentException(error.name)

        private fun fail(error: SiteAddressError): Nothing = throw AddressException(error)
    }
}

/**
 * Optional per-site request headers; null uses the defaults. Only these two are customizable.
 * Values must be single-line, so they can never inject further headers.
 */
data class SiteHeaders(val userAgent: String? = null, val referer: String? = null) {
    init {
        listOfNotNull(userAgent, referer).forEach { value ->
            require(value.isNotBlank() && value.length <= MAX_HEADER_LENGTH && value.none(Char::isISOControl)) {
                "Invalid site header value"
            }
        }
    }

    companion object {
        const val MAX_HEADER_LENGTH = 512
    }
}

/**
 * Danbooru `login` + `api_key`, or Gelbooru `user_id` + `api_key`. Kept in encrypted storage only;
 * never logged, persisted in clear, or backed up.
 */
class SiteCredentials(val user: String, val apiKey: String) {
    init {
        require(user.isNotBlank() && apiKey.isNotBlank()) { "Site credentials need a user and an API key" }
    }

    override fun equals(other: Any?): Boolean = other is SiteCredentials && other.user == user && other.apiKey == apiKey
    override fun hashCode(): Int = 31 * user.hashCode() + apiKey.hashCode()
    override fun toString(): String = "SiteCredentials([REDACTED])"
}

/** Data describing one user-added site. Credentials live separately, in encrypted storage. */
data class SiteProfile(
    /** Authoritative identity; equals the source id persisted with this site's posts. */
    val sourceId: String,
    val address: SiteAddress,
    val label: String,
    /** [ProtocolFamily.name], kept raw so a family added by a newer app version round-trips. */
    val familyId: String,
    /** Null when the site accepts any number of search terms. */
    val searchTermLimit: Int? = null,
    val headers: SiteHeaders = SiteHeaders(),
    /** The probe found anonymous access refused; without stored keys the site is unavailable. */
    val credentialsRequired: Boolean = false,
    val addedAtEpochMs: Long,
    val verifiedAtEpochMs: Long? = null,
) {
    init {
        require(CustomSourceIds.isValid(sourceId)) { "Invalid custom source id" }
        require(label.isNotEmpty() && label == label.trim() && label.length <= MAX_LABEL_LENGTH) { "Invalid site label" }
        require(familyId.isNotBlank()) { "Missing protocol family" }
        require(searchTermLimit == null || searchTermLimit > 0) { "Search term limit must be positive" }
        require(addedAtEpochMs >= 0 && (verifiedAtEpochMs == null || verifiedAtEpochMs >= 0)) { "Invalid timestamp" }
    }

    /** Null when this app version doesn't know the family: the profile is kept and the site is unavailable. */
    val family: ProtocolFamily? get() = ProtocolFamily.entries.firstOrNull { it.name == familyId }

    companion object {
        const val MAX_CUSTOM_SITES = 32
        const val MAX_LABEL_LENGTH = 40
    }
}

enum class ProbeFailure { DNS, TLS, TIMEOUT, HTTP_ERROR, BLOCKED, INSECURE_REDIRECT, CROSS_HOST_REDIRECT }

/** Outcome of probing an address for a supported protocol family. A probe never persists anything. */
sealed interface SiteProbeResult {
    /** [credentialsRequired]: anonymous access was refused and the supplied credentials worked. */
    data class Supported(
        val address: SiteAddress,
        val family: ProtocolFamily,
        val suggestedLabel: String,
        val searchTermLimit: Int?,
        val credentialsRequired: Boolean,
    ) : SiteProbeResult

    /** The family answered but refused this access; [rejected] when supplied credentials failed. */
    data class CredentialsRequired(val address: SiteAddress, val family: ProtocolFamily, val rejected: Boolean) : SiteProbeResult

    /** Recognized as a booru whose family is not supported yet, e.g. "Moebooru". */
    data class UnsupportedFamily(val address: SiteAddress, val familyName: String) : SiteProbeResult

    /** Every request got a well-formed answer and none matched a known booru API. */
    data class NotABooru(val address: SiteAddress) : SiteProbeResult

    /** The address belongs to a shipped source, which cannot be added a second time. */
    data class AlreadyBuiltIn(val builtInSourceId: String) : SiteProbeResult

    /** Inconclusive: at least one request failed before the site could answer. */
    data class Unreachable(val address: SiteAddress, val reason: ProbeFailure) : SiteProbeResult

    /** The input is not a usable HTTPS site address; nothing was requested. */
    data class InvalidAddress(val error: SiteAddressError) : SiteProbeResult
}
