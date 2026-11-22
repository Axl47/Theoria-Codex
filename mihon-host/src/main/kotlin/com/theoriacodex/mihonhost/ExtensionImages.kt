package com.theoriacodex.mihonhost

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.runBlocking
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
 * Every address handed to the app ends in a fragment naming its source. A page whose source has not named its image
 * yet is handed out as the address of the page that names it, marked as such; the image's address is asked for here,
 * when that image is requested. The app requests it like any other image;
 * this interceptor, placed on the app's media client, answers tagged addresses through that source and passes every
 * other request on. The tag travels with the address, so a cover the app stored still loads after a restart.
 *
 * Only the sources last handed to [publish] answer: the ones whose extension loaded completely and whose extension
 * and language are on. [ensureLoaded] loads the device's extensions, which publishes their sources, and returns once
 * that is done; it must not throw. It runs on an HTTP client's thread, the first time a tagged address names a source
 * that is not published: a stored cover can be asked for before anything else has needed the extensions.
 */
class ExtensionImages(private val ensureLoaded: () -> Unit = {}) : Interceptor {
    @Volatile
    private var sources: Map<Long, HttpSource> = emptyMap()

    /** Pages whose request only the source can build; a page from an earlier run is fetched by its address alone. */
    private val pages = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Page>): Boolean = size > MAX_PAGES
        },
    )

    /** Makes [active] the only sources that fetch, all at once: one whose extension or language was turned off stops answering. */
    internal fun publish(active: List<Source>) {
        sources = active.filterIsInstance<HttpSource>().associateBy { it.id }
    }

    /** The source once the extensions have loaded; null when its extension is not on the device. */
    private fun loaded(sourceId: Long): HttpSource? {
        ensureLoaded()
        return sources[sourceId]
    }

    /**
     * The tagged address the source would request for [page]; null when the page names neither an image nor where to
     * find one. A page's own `imageUrl` is not always that address: MangaDex keeps a bare path there and adds the
     * image server per request. Makes no request.
     */
    internal fun pageAddress(source: HttpSource, page: Page): String? {
        val address = if (page.imageUrl != null) {
            source.imageCall(page).request().url.tagged(source)
        } else {
            val names = page.url.takeIf(String::isNotBlank)?.let { it.toHttpUrlOrNull() ?: source.baseUrl.toHttpUrlOrNull()?.resolve(it) }
            names?.tagged(source, UNRESOLVED_TAG) ?: return null
        }
        pages[address.toString()] = page
        return address.toString()
    }

    internal fun coverAddress(source: HttpSource, address: String): String =
        address.toHttpUrlOrNull()?.tagged(source)?.toString() ?: address

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val fragment = url.fragment.orEmpty()
        val unresolved = fragment.startsWith(UNRESOLVED_TAG)
        val sourceId = fragment.takeIf { unresolved || it.startsWith(TAG) }?.substringAfter('=')?.toLongOrNull()
            ?: return chain.proceed(chain.request())
        val source = sources[sourceId] ?: loaded(sourceId) ?: return chain.proceed(chain.request())
        val bare = url.newBuilder().fragment(null).build().toString()
        // A page from an earlier run whose image was never named is asked about again by its address.
        val page = pages[url.toString()] ?: if (unresolved) Page(0, bare).also { pages[url.toString()] = it } else null
        return failingAsIo {
            val call = if (page != null) source.imageCall(page.named(source)) else source.imageCall(bare)
            call.execute()
        }
    }

    /** This page once the source has named its image, asking it the first time; a failure is this page's alone and is asked again next time. */
    private fun Page.named(source: HttpSource): Page = synchronized(this) {
        if (imageUrl == null) imageUrl = runBlocking { source.getImageUrl(this@named) }
        this
    }

    private fun HttpUrl.tagged(source: HttpSource, tag: String = TAG): HttpUrl = newBuilder().fragment("$tag${source.id}").build()

    internal companion object {
        private const val TAG = "mihon="

        /** Marks the address of a page that names an image, in place of the image's own. */
        private const val UNRESOLVED_TAG = "mihon-page="

        /** Where the tag starts in a tagged address. */
        const val FRAGMENT = "#$TAG"

        /** A few hundred chapters' worth. */
        private const val MAX_PAGES = 8_192
    }
}
