package com.theoriacodex.extensionhost

import com.theoriacodex.domain.model.SourceKey
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Runs extension code on an HTTP client's thread. OkHttp reports only an [IOException] to the caller and lets anything
 * else end the process, and an extension can fail in other ways: its own bugs, or a part of the host API this module
 * lacks, which surfaces as a [LinkageError].
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> failingAsIo(block: () -> T): T = try {
    block()
} catch (failure: IOException) {
    throw failure
} catch (failure: Exception) {
    throw IOException(failure)
} catch (failure: LinkageError) {
    throw IOException(failure)
}

/** Fetches one hosted source's images through its own client, which adds the headers and tokens that source needs. */
fun interface SourceImages {
    /**
     * Fetches the image handed out as [address], which carries the tag [ExtensionImages.tag] issued for this source.
     * [url] is that address without its tag, and [data] what the source put in the tag, if anything. Runs on an HTTP
     * client's thread and may run extension code; whatever it throws reaches the caller as an [IOException].
     */
    fun fetch(address: String, url: HttpUrl, data: String?): Response
}

/**
 * Fetches extension covers and page images the way Mihon does: through the source's own client ([SourceImages]).
 * MangaDex, for one, answers any other client's cover request with a bot check.
 *
 * Every address handed to the app ends in a fragment naming its source by its full key, so sources of two kinds that
 * compute the same numeric id stay apart. The app requests it like any other image; this interceptor, placed on the
 * app's media client, answers tagged addresses through that source and passes every other request on. The tag travels
 * with the address, so a cover the app stored still loads after a restart.
 *
 * The app's media client also loads addresses that other parties supply, such as a Custom Site's thumbnails, and a
 * fragment is theirs to write. So a tag is honored only with a code only this host can compute: an HMAC, under
 * [key], of the address and what the tag says. A tag without the right code is not this host's: its request goes out
 * as an ordinary one, without the fragment, and no extension is loaded or asked. The code covers the address, so one
 * that leaks lets nobody tag another address.
 *
 * Only the sources last handed to [publish] answer: the ones whose extension loaded completely and whose extension
 * and language are on. [refresh] brings that up to date with the device, loading the extensions the first time, and
 * returns once that is done; it must not throw. It runs on an HTTP client's thread before every tagged request, so an
 * image of an extension removed since stops being fetched, and it must cost little while nothing changed.
 */
class ExtensionImages(
    /** Kept for the install, where Android does not back it up; read on first use, off the main thread. */
    private val key: Lazy<ByteArray> = lazy { ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes) },
    private val refresh: () -> Unit = {},
) : Interceptor {
    @Volatile
    private var sources: Map<SourceKey, SourceImages> = emptyMap()

    /** Makes [active] the only sources that fetch, all at once: one whose extension or language was turned off stops answering. */
    internal fun publish(active: List<HostedSource>) {
        // Read here, off the main thread: covers are tagged where screens draw them.
        key.value
        sources = active.mapNotNull { source -> source.images?.let { source.key to it } }.toMap()
    }

    /**
     * [address] tagged as [source]'s, with [data] the source wants back when it is fetched (such as which page of
     * which chapter an address names); [address] itself when it is not an HTTP address. Makes no request.
     */
    fun tag(source: SourceKey, address: String, data: String? = null): String {
        val url = address.toHttpUrlOrNull() ?: return address
        return url.newBuilder().fragment(marker(source, url, data)).build().toString()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val fragment = url.fragment.orEmpty()
        if (!fragment.startsWith(TAG)) return chain.proceed(chain.request())
        val untagged = url.newBuilder().fragment(null).build()
        // A tag this host did not issue, or one for a source that is not on: an ordinary request, without the tag.
        val (source, data) = issued(untagged, fragment) ?: return chain.proceed(chain.request().newBuilder().url(untagged).build())
        return failingAsIo { source.fetch(url.toString(), untagged, data) }
    }

    /**
     * The source [fragment] names, with the data it carries, when this host issued that fragment for [url] and the
     * source is on. The code is checked before anything else, so a forged tag does not load extensions either.
     */
    private fun issued(url: HttpUrl, fragment: String): Pair<SourceImages, String?>? {
        val parts = fragment.removePrefix(TAG).split('/', limit = 2)
        val source = SourceKey.parse(parts[0].substringBeforeLast('.')) ?: return null
        val data = parts.getOrNull(1)
        if (!MessageDigest.isEqual(marker(source, url, data).toByteArray(), fragment.toByteArray())) return null
        refresh()
        return sources[source]?.let { it to data }
    }

    /** The fragment for [url]: the source, this host's code for all of it, then [data]. */
    private fun marker(source: SourceKey, url: HttpUrl, data: String?): String {
        val signed = "$TAG${source.id}/${data.orEmpty()}\n${url.newBuilder().fragment(null).build()}"
        val code = Mac.getInstance(MAC).apply { init(SecretKeySpec(key.value, MAC)) }.doFinal(signed.toByteArray())
        val shown = code.take(CODE_BYTES).joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }
        return "$TAG${source.id}.$shown" + if (data == null) "" else "/$data"
    }

    internal companion object {
        /**
         * Named when Mihon's were the only extensions hosted. Kept for every kind because covers stored by builds before
         * 2026-11-22 still carry it, and [FRAGMENT] finds those to replace.
         */
        private const val TAG = "mihon="

        /** Where the tag starts in a tagged address. */
        const val FRAGMENT = "#$TAG"

        const val KEY_BYTES = 32
        private const val MAC = "HmacSHA256"
        private const val CODE_BYTES = 16
        private const val BYTE = 0xff
        private const val HEX = 16
    }
}
