package com.theoriacodex.mihonhost

import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
 * other request on. A tag needs only the source's id and [key], so a stored cover is tagged from its series' source
 * key ([address]) without loading anything; its extension loads when the image is first requested.
 *
 * The app's media client also loads addresses that other parties supply, such as a Custom Site's thumbnails, and a
 * fragment is theirs to write. So a tag is honored only with a code only this host can compute: an HMAC, under
 * [key], of the address and what the tag says. A tag without the right code is not this host's: its request goes out
 * as an ordinary one, without the fragment, and no extension is loaded or asked. The code covers the address, so one
 * that leaks lets nobody tag another address.
 *
 * A page whose source has not named its image yet is handed out as the address of the page that names it, with a
 * fragment that also names its chapter and its place in it, so two pages never share an address. The image's address
 * is asked for here, when that image is requested, and asked for again after a fetch of it failed, since such
 * addresses expire.
 *
 * Only the sources last handed to [publish] answer: the ones whose extension loaded completely and whose extension
 * and language are on. Whoever publishes keeps that table current as extensions change ([ExtensionRegistry.recheck]);
 * a request only reads it, with no system call or lock. A tagged request for a source not in the table calls [load]
 * with its id, which loads that source's extension when it is on and publishes, and returns once that is done. A load
 * that fails is not tried again for later images until the next publish.
 */
class ExtensionImages(
    /** Kept for the install, where Android does not back it up; read on first use, best warmed off the main thread. */
    private val key: Lazy<ByteArray> = lazy { ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes) },
    private val load: (sourceId: Long) -> Unit = {},
) : Interceptor, SeriesCovers {
    @Volatile
    private var sources: Map<Long, HttpSource> = emptyMap()

    /** Set when [load] failed, until the next [publish]: images then read the table without loading. */
    @Volatile
    private var loadFailed = false

    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** Pages whose request only the source can build, by the address handed out for them. */
    private val pages = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Page>): Boolean = size > MAX_PAGES
        },
    )

    /** Makes [active] the only sources that fetch, all at once: one whose extension or language was turned off stops answering. */
    @Synchronized
    internal fun publish(active: List<Source>) {
        sources = active.filterIsInstance<HttpSource>().associateBy { it.id }
        loadFailed = false
    }

    /** The published source [sourceId] names, loading its extension first when it is not published. */
    @Suppress("TooGenericExceptionCaught")
    private fun published(sourceId: Long): HttpSource? = sources[sourceId] ?: run {
        if (loadFailed) return null
        val before = sources
        try {
            load(sourceId)
        } catch (failure: Exception) {
            reportOnce("extensions failed to load for an image: ${failure.javaClass.simpleName}")
            // Unless a publish landed meanwhile.
            synchronized(this) { if (sources === before) loadFailed = true }
        }
        sources[sourceId]
    }

    /**
     * The tagged address the source would request for [page] of [chapter]; null when the page names neither an image
     * nor where to find one. A page's own `imageUrl` is not always that address: MangaDex keeps a bare path there and
     * adds the image server per request. Makes no request.
     */
    internal fun pageAddress(source: HttpSource, chapter: SChapter, page: Page): String? {
        val address = if (page.imageUrl != null) {
            source.imageCall(page).request().url.tagged(source.id)
        } else {
            val names = page.url.takeIf(String::isNotBlank)?.let { it.toHttpUrlOrNull() ?: source.baseUrl.toHttpUrlOrNull()?.resolve(it) }
            names?.newBuilder()?.fragment(marker(UNRESOLVED_TAG, source.id, names, "${page.index}/${chapter.url}"))?.build() ?: return null
        }
        pages[address.toString()] = page
        return address.toString()
    }

    /**
     * [series]' cover, tagged for its source when that is an extension's, whether or not the extension is loaded or
     * on: one that is not answers as an ordinary request. Covers stored by earlier builds carry a tag of their own,
     * which is replaced.
     */
    override fun address(series: Series): String? {
        val sourceId = MihonSeriesSource.sourceId(series.id.source) ?: return series.coverUrl
        val stored = series.coverUrl?.substringBefore(FRAGMENT) ?: return null
        return stored.toHttpUrlOrNull()?.tagged(sourceId)?.toString() ?: stored
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val fragment = url.fragment.orEmpty()
        val unresolved = fragment.startsWith(UNRESOLVED_TAG)
        if (!unresolved && !fragment.startsWith(TAG)) return chain.proceed(chain.request())
        val tag = fragment.substringAfter('=').split('/', limit = UNRESOLVED_PARTS)
        // A tag this host did not issue, or one for a source that is not on: an ordinary request, without the tag.
        val source = issuedSource(url, fragment, tag)
            ?: return chain.proceed(chain.request().newBuilder().url(url.newBuilder().fragment(null).build()).build())
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
     * The source [fragment] names, when this host issued that fragment for [url] and the source is on. The code is
     * checked before anything else, so a forged tag does not load extensions either.
     */
    private fun issuedSource(url: HttpUrl, fragment: String, tag: List<String>): HttpSource? {
        val sourceId = tag[0].substringBefore('.').toLongOrNull() ?: return null
        val kind = if (fragment.startsWith(UNRESOLVED_TAG)) UNRESOLVED_TAG else TAG
        val issued = marker(kind, sourceId, url, tag.drop(1).joinToString("/"))
        if (!MessageDigest.isEqual(issued.toByteArray(), fragment.toByteArray())) {
            // The source a forged or stale tag names is unverified, so only the host is told.
            reportOnce("${url.host} image sent without its source: tag not issued here")
            return null
        }
        val source = published(sourceId)
        if (source == null) reportOnce("${url.host} image of source $sourceId sent without its source: source not on")
        return source
    }

    /** Logs [message] the first time only: a screen of stale covers would otherwise fill the log. */
    private fun reportOnce(message: String) {
        if (reported.add(message)) Diagnostics.report(LOG_AREA, message)
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

    private fun HttpUrl.tagged(sourceId: Long): HttpUrl = newBuilder().fragment(marker(TAG, sourceId, this)).build()

    /** The fragment for [url] under [kind]: the source, this host's code for all of it, then [rest]. */
    private fun marker(kind: String, sourceId: Long, url: HttpUrl, rest: String = ""): String {
        val signed = "$kind$sourceId/$rest\n${url.newBuilder().fragment(null).build()}"
        val code = Mac.getInstance(MAC).apply { init(SecretKeySpec(key.value, MAC)) }.doFinal(signed.toByteArray())
        val shown = code.take(CODE_BYTES).joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }
        return "$kind$sourceId.$shown" + if (rest.isEmpty()) "" else "/$rest"
    }

    internal companion object {
        private const val TAG = "mihon="

        /** Marks the address of a page that names an image, in place of the image's own; then the source, the page's index and its chapter. */
        private const val UNRESOLVED_TAG = "mihon-page="
        private const val UNRESOLVED_PARTS = 3
        private const val LOG_AREA = "extension-image"

        /** Where the tag starts in a tagged address. */
        const val FRAGMENT = "#$TAG"

        const val KEY_BYTES = 32
        private const val MAC = "HmacSHA256"
        private const val CODE_BYTES = 16
        private const val BYTE = 0xff
        private const val HEX = 16

        /** A few hundred chapters' worth. */
        private const val MAX_PAGES = 8_192
    }
}
