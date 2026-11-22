package com.theoriacodex.mihonhost

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Runs extension code on an HTTP client's thread. OkHttp reports only an [IOException] to the caller and lets anything
 * else end the process, and an extension can fail in other ways: its own bugs, or a part of Mihon's host API this
 * module lacks, which surfaces as a [LinkageError].
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

/**
 * Fetches extension covers and page images the way Mihon does: through the source's own client, which adds the
 * headers, access tokens and descrambling that source needs. MangaDex, for one, answers any other client's cover
 * request with a bot check.
 *
 * Every address handed to the app ends in a fragment naming its source. The app requests it like any other image;
 * this interceptor, placed on the app's media client, answers tagged addresses through that source and passes every
 * other request on. The tag travels with the address, so a cover the app stored still loads after a restart.
 *
 * [ensureLoaded] loads the device's extensions, attaching their sources, and returns once that is done; it must not
 * throw. It runs on an HTTP client's thread, the first time a tagged address names a source that is not attached:
 * a stored cover can be asked for before anything else has needed the extensions.
 */
class ExtensionImages(private val ensureLoaded: () -> Unit = {}) : Interceptor {
    private val sources = ConcurrentHashMap<Long, HttpSource>()

    /** Pages whose request only the source can build; a page from an earlier run is fetched by its address alone. */
    private val pages = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Page>): Boolean = size > MAX_PAGES
        },
    )

    internal fun attach(source: HttpSource) {
        sources[source.id] = source
    }

    /** The source once the extensions have loaded; null when its extension is not on the device. */
    private fun loaded(sourceId: Long): HttpSource? {
        ensureLoaded()
        return sources[sourceId]
    }

    /**
     * The tagged address the source would request for [page]. A page's own `imageUrl` is not always that address:
     * MangaDex keeps a bare path there and adds the image server per request.
     */
    internal fun pageAddress(source: HttpSource, page: Page): String {
        val address = source.imageCall(page).request().url.tagged(source).toString()
        pages[address] = page
        return address
    }

    internal fun coverAddress(source: HttpSource, address: String): String =
        address.toHttpUrlOrNull()?.tagged(source)?.toString() ?: address

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val sourceId = url.fragment?.takeIf { it.startsWith(TAG) }?.removePrefix(TAG)?.toLongOrNull()
            ?: return chain.proceed(chain.request())
        val source = sources[sourceId] ?: loaded(sourceId) ?: return chain.proceed(chain.request())
        val page = pages[url.toString()]
        return failingAsIo {
            val call = if (page != null) source.imageCall(page) else source.imageCall(url.newBuilder().fragment(null).build().toString())
            call.execute()
        }
    }

    private fun HttpUrl.tagged(source: HttpSource): HttpUrl = newBuilder().fragment("$TAG${source.id}").build()

    internal companion object {
        private const val TAG = "mihon="

        /** Where the tag starts in a tagged address. */
        const val FRAGMENT = "#$TAG"

        /** A few hundred chapters' worth. */
        private const val MAX_PAGES = 8_192
    }
}
