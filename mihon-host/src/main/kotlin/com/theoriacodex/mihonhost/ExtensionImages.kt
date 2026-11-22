package com.theoriacodex.mihonhost

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Fetches extension covers and page images the way Mihon does: through the source's own client, which adds the
 * headers, access tokens and descrambling that source needs. MangaDex, for one, answers any other client's cover
 * request with a bot check.
 *
 * Every address handed to the app ends in a fragment naming its source. The app requests it like any other image;
 * this interceptor, placed on the app's media client, answers tagged addresses through that source and passes every
 * other request on. The tag travels with the address, so a post the app saved still loads after a restart.
 */
class ExtensionImages(private val loadWaitMs: Long = LOAD_WAIT_MS) : Interceptor {
    private val sources = ConcurrentHashMap<Long, HttpSource>()
    private val loaded = CountDownLatch(1)

    /** Pages whose request only the source can build; a page from an earlier run is fetched by its address alone. */
    private val pages = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Page>): Boolean = size > MAX_PAGES
        },
    )

    internal fun attach(source: HttpSource) {
        sources[source.id] = source
    }

    /** Call once every extension has been loaded; until then a tagged request waits for its source. */
    fun finishedLoading() = loaded.countDown()

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
        // A feed restored at launch can ask for its covers before the extensions have loaded.
        loaded.await(loadWaitMs, TimeUnit.MILLISECONDS)
        val source = sources[sourceId] ?: return chain.proceed(chain.request())
        val page = pages[url.toString()]
        val call = if (page != null) source.imageCall(page) else source.imageCall(url.newBuilder().fragment(null).build().toString())
        return call.execute()
    }

    private fun HttpUrl.tagged(source: HttpSource): HttpUrl = newBuilder().fragment("$TAG${source.id}").build()

    private companion object {
        const val TAG = "mihon="

        /** A few hundred chapters' worth. */
        const val MAX_PAGES = 8_192
        const val LOAD_WAIT_MS = 10_000L
    }
}
