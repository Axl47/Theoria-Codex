// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
package eu.kanade.tachiyomi.source.online

import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

internal fun sourceId(name: String, lang: String, versionId: Int): Long {
    val bytes = MessageDigest.getInstance("MD5").digest("${name.lowercase()}/$lang/$versionId".toByteArray())
    return (0 until Long.SIZE_BYTES).map { bytes[it].toLong() and BYTE_MASK shl Byte.SIZE_BITS * (Long.SIZE_BYTES - 1 - it) }
        .reduce(Long::or) and Long.MAX_VALUE
}

internal fun withoutDomain(original: String): String = try {
    val uri = URI(original.replace(" ", "%20"))
    buildString {
        append(uri.path)
        uri.query?.let { append('?').append(it) }
        uri.fragment?.let { append('#').append(it) }
    }
} catch (_: URISyntaxException) {
    original
}

private const val BYTE_MASK = 0xffL
