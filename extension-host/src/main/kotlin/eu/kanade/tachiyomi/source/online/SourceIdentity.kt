// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
package eu.kanade.tachiyomi.source.online

import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/*
 * What Mihon's `HttpSource` and Aniyomi's `AnimeHttpSource` derive the same way. Host code only: extensions call the
 * members of those classes that use these.
 */

/** Stable across installs: the first eight bytes of MD5("name/lang/versionId"), as Mihon and Aniyomi derive it. */
internal fun sourceId(name: String, lang: String, versionId: Int): Long {
    val bytes = MessageDigest.getInstance("MD5").digest("${name.lowercase()}/$lang/$versionId".toByteArray())
    return (0 until Long.SIZE_BYTES).map { bytes[it].toLong() and BYTE_MASK shl Byte.SIZE_BITS * (Long.SIZE_BYTES - 1 - it) }
        .reduce(Long::or) and Long.MAX_VALUE
}

/** [original]'s path, query and fragment, as a source stores the addresses it lists; [original] when it is no URI. */
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
