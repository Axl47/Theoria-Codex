package com.theoriacodex.mihonhost

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.Call
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
 * A page whose source has not named its image yet is handed out as the address of the page that names it, with a
 * fragment that also names its chapter and its place in it, so two pages never share an address. The image's address
 * is asked for here, when that image is requested, and asked for again after a fetch of it failed, since such
 * addresses expire.
 *
 * Only the sources last handed to [publish] answer: the ones whose extension loaded completely and whose extension
 * and language are on. [refresh] brings that up to date with the device, loading the extensions the first time, and
 * returns once that is done; it must not throw. It runs on an HTTP client's thread before every tagged request, so an
 * image of an extension removed since stops being fetched, and it must cost little while nothing changed.
 */
class ExtensionImages(private val refresh: () -> Unit = {}) : Interceptor {
    @Volatile
    private var sources: Map<Long, HttpSource> = emptyMap()

    /** Pages whose request only the source can build, by the address handed out for them. */
    private val pages = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Page>): Boolean = size > MAX_PAGES
        },
    )

    /** Makes [active] the only sources that fetch, all at once: one whose extension or language was turned off stops answering. */
    internal fun publish(active: List<Source>) {
        sources = active.filterIsInstance<HttpSource>().associateBy { it.id }
    }

    /**
     * The tagged address the source would request for [page] of [chapter]; null when the page names neither an image
     * nor where to find one. A page's own `imageUrl` is not always that address: MangaDex keeps a bare path there and
     * adds the image server per request. Makes no request.
     */
    internal fun pageAddress(source: HttpSource, chapter: SChapter, page: Page): String? {
        val address = if (page.imageUrl != null) {
            source.imageCall(page).request().url.tagged(source)
        } else {
            val names = page.url.takeIf(String::isNotBlank)?.let { it.toHttpUrlOrNull() ?: source.baseUrl.toHttpUrlOrNull()?.resolve(it) }
            names?.newBuilder()?.fragment("$UNRESOLVED_TAG${source.id}/${page.index}/${chapter.url}")?.build() ?: return null
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
        if (!unresolved && !fragment.startsWith(TAG)) return chain.proceed(chain.request())
        val tag = fragment.substringAfter('=').split('/', limit = UNRESOLVED_PARTS)
        val sourceId = tag[0].toLongOrNull() ?: return chain.proceed(chain.request())
        refresh()
        val source = sources[sourceId] ?: return chain.proceed(chain.request())
        return failingAsIo {
            if (unresolved) {
                val page = unresolvedPage(source, url.toString(), tag)
                val (call, named) = page.imageCall(source)
                // Another request may have asked again since: only the address this fetch used is dropped.
                fetched(call) { synchronized(page) { if (page.imageUrl == named) page.imageUrl = null } }
            } else {
                val page = pages[url.toString()]
                (if (page != null) source.imageCall(page) else source.imageCall(url.newBuilder().fragment(null).build().toString())).execute()
            }
        }
    }

    /**
     * The page handed out as [address]. When it is no longer held, after a restart or once enough newer pages pushed
     * it out, its chapter's pages are listed again and the one at the same place is taken.
     */
    private fun unresolvedPage(source: HttpSource, address: String, tag: List<String>): Page = pages[address] ?: run {
        val index = tag.getOrNull(1)?.toIntOrNull()
        val chapter = SChapter.create().apply {
            url = tag.getOrNull(2).orEmpty()
            name = ""
        }
        val listed = runBlocking { source.getPageList(chapter) }
        listed.forEach { pageAddress(source, chapter, it) }
        pages[address] ?: listed.firstOrNull { it.index == index } ?: throw IOException("The chapter no longer has this page")
    }

    /**
     * The request for this page's image with the address it uses, asking the source to name the image when no address
     * is held. Under one lock per page, so the request is never built while another drops the address; a failure
     * to name it is this page's alone and is asked again next time.
     */
    private fun Page.imageCall(source: HttpSource): Pair<Call, String?> = synchronized(this) {
        if (imageUrl == null) imageUrl = runBlocking { source.getImageUrl(this@imageCall) }
        source.imageCall(this) to imageUrl
    }

    /** Runs [call]; when the image does not arrive, [forget] drops what led to it, so the next request starts over. */
    private inline fun fetched(call: Call, forget: () -> Unit): Response {
        val response = try {
            call.execute()
        } catch (failure: IOException) {
            forget()
            throw failure
        }
        if (!response.isSuccessful) forget()
        return response
    }

    private fun HttpUrl.tagged(source: HttpSource): HttpUrl = newBuilder().fragment("$TAG${source.id}").build()

    internal companion object {
        private const val TAG = "mihon="

        /** Marks the address of a page that names an image, in place of the image's own; then the source, the page's index and its chapter. */
        private const val UNRESOLVED_TAG = "mihon-page="
        private const val UNRESOLVED_PARTS = 3

        /** Where the tag starts in a tagged address. */
        const val FRAGMENT = "#$TAG"

        /** A few hundred chapters' worth. */
        private const val MAX_PAGES = 8_192
    }
}
