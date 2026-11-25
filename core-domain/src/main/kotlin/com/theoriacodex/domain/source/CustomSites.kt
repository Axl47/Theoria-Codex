package com.theoriacodex.domain.source

import java.net.IDN
import java.security.MessageDigest
import java.util.Locale

enum class ProtocolFamily(val displayName: String) {
    DANBOORU("Danbooru"),
    GELBOORU_02("Gelbooru 0.2"),
}

object CustomSourceIds {
    const val PREFIX = "custom_"

    const val MAX_LENGTH = 96

    private const val PATH_HASH_LENGTH = 8
    private const val LABEL = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
    private val ID_PATTERN = Regex("^${PREFIX}$LABEL(?:_$LABEL)*(?:__p[1-9][0-9]{0,4})?(?:__h[0-9a-f]{$PATH_HASH_LENGTH})?$")

    fun fromAddress(host: String, port: Int?, path: String): String = buildString {
        append(PREFIX)
        append(host.removePrefix("www.").replace('.', '_'))
        port?.let { append("__p").append(it) }
        if (path.isNotEmpty()) append("__h").append(sha256Hex(path).take(PATH_HASH_LENGTH))
    }

    fun isValid(id: String): Boolean = id.length <= MAX_LENGTH && ID_PATTERN.matches(id)

    fun hostLabel(id: String): String = id.removePrefix(PREFIX).substringBefore("__").replace('_', '.')

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .toHexString()
}

enum class SiteAddressError { MALFORMED, NOT_HTTPS, UNSUPPORTED_HOST, TOO_LONG }

sealed interface SiteAddressParse {
    data class Valid(val address: SiteAddress) : SiteAddressParse
    data class Invalid(val error: SiteAddressError) : SiteAddressParse
}

class SiteAddress private constructor(
    val baseUrl: String,
    val host: String,
    val port: Int?,
    val path: String,
) {
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

class SiteCredentials(val user: String, val apiKey: String) {
    init {
        require(user.isNotBlank() && apiKey.isNotBlank()) { "Site credentials need a user and an API key" }
    }

    override fun equals(other: Any?): Boolean = other is SiteCredentials && other.user == user && other.apiKey == apiKey
    override fun hashCode(): Int = 31 * user.hashCode() + apiKey.hashCode()
    override fun toString(): String = "SiteCredentials([REDACTED])"
}

data class SiteProfile(
    val sourceId: String,
    val address: SiteAddress,
    val label: String,
    val familyId: String,
    val searchTermLimit: Int? = null,
    val headers: SiteHeaders = SiteHeaders(),
    val credentialsRequired: Boolean = false,
    val addedAtEpochMs: Long,
    val verifiedAtEpochMs: Long? = null,
    val iconUrl: String? = null,
) {
    init {
        require(CustomSourceIds.isValid(sourceId)) { "Invalid custom source id" }
        require(label.isNotEmpty() && label == label.trim() && label.length <= MAX_LABEL_LENGTH) { "Invalid site label" }
        require(familyId.isNotBlank()) { "Missing protocol family" }
        require(searchTermLimit == null || searchTermLimit > 0) { "Search term limit must be positive" }
        require(addedAtEpochMs >= 0 && (verifiedAtEpochMs == null || verifiedAtEpochMs >= 0)) { "Invalid timestamp" }
    }

    val family: ProtocolFamily? get() = ProtocolFamily.entries.firstOrNull { it.name == familyId }

    companion object {
        const val MAX_CUSTOM_SITES = 32
        const val MAX_LABEL_LENGTH = 40
    }
}

enum class ProbeFailure { DNS, TLS, TIMEOUT, HTTP_ERROR, BLOCKED, RATE_LIMITED, INSECURE_REDIRECT, CROSS_HOST_REDIRECT }

sealed interface SiteProbeResult {
    data class Supported(
        val address: SiteAddress,
        val family: ProtocolFamily,
        val suggestedLabel: String,
        val searchTermLimit: Int?,
        val credentialsRequired: Boolean,
        val iconUrl: String? = null,
    ) : SiteProbeResult

    data class CredentialsRequired(val address: SiteAddress, val family: ProtocolFamily, val rejected: Boolean) : SiteProbeResult

    data class UnsupportedFamily(val address: SiteAddress, val familyName: String) : SiteProbeResult

    data class NotABooru(val address: SiteAddress) : SiteProbeResult

    data class AlreadyBuiltIn(val builtInSourceId: String) : SiteProbeResult

    data class Unreachable(val address: SiteAddress, val reason: ProbeFailure) : SiteProbeResult

    data class InvalidAddress(val error: SiteAddressError) : SiteProbeResult
}

val SiteProbeResult.clearedChallenge: Boolean
    get() = when (this) {
        is SiteProbeResult.Supported,
        is SiteProbeResult.CredentialsRequired,
        is SiteProbeResult.UnsupportedFamily,
        is SiteProbeResult.NotABooru,
        -> true
        is SiteProbeResult.AlreadyBuiltIn,
        is SiteProbeResult.Unreachable,
        is SiteProbeResult.InvalidAddress,
        -> false
    }
