package com.theoriacodex.extensionhost

import com.theoriacodex.domain.model.SourceKey
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

/**
 * One Mihon source's covers and page images, fetched as Mihon fetches them: through the source's `imageCall`, which
 * adds the headers, access tokens and descrambling the source needs. [images] tags the addresses handed out as [key]'s.
 *
 * A page whose source has not named its image yet is handed out as the address of the page that names it, tagged with
 * its place and its chapter, so two pages never share an address. The image's address is asked for when that image is
 * requested, and asked for again after a fetch of it failed, since such addresses expire.
 */
internal class MihonImages(
    private val source: HttpSource,
    private val key: SourceKey,
    private val images: ExtensionImages,
    /** The chapter at an address as the source last saw it this run, memo included; null when none is held. */
    private val chapter: (String) -> SChapter? = { null },
) : SourceImages {
    /** Pages whose request only the source can build, by the address handed out for them. */
    private val pages = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Page>): Boolean = size > MAX_PAGES
        },
    )

    /**
     * The tagged address the source would request for [page] of [chapter]; null when the page names neither an image
     * nor where to find one. A page's own `imageUrl` is not always that address: some sources keep a bare path there and
     * adds the image server per request. Makes no request.
     */
    fun pageAddress(chapter: SChapter, page: Page): String? {
        val address = if (page.imageUrl != null) {
            images.tag(key, source.imageCall(page).request().url.toString())
        } else {
            val names = page.url.takeIf(String::isNotBlank)?.let { it.toHttpUrlOrNull() ?: source.baseUrl.toHttpUrlOrNull()?.resolve(it) }
            names?.let { images.tag(key, it.toString(), "${page.index}/${chapter.url}") } ?: return null
        }
        pages[address] = page
        return address
    }

    /** [data] is set only for a page whose image is not named yet: its index and its chapter's address. */
    override fun fetch(address: String, url: HttpUrl, data: String?): Response {
        if (data == null) {
            val page = pages[address]
            return (if (page != null) source.imageCall(page) else source.imageCall(url.toString())).execute()
        }
        val page = unresolvedPage(address, data)
        val (call, named) = page.imageCall()
        // Another request may have asked again since: only the address this fetch used is dropped.
        return fetched(call) { synchronized(page) { if (page.imageUrl == named) page.imageUrl = null } }
    }

    /**
     * The page handed out as [address]. When it is no longer held, after a restart or once enough newer pages pushed
     * it out, its chapter's pages are listed again and the one at the same place is taken. The chapter is the one the
     * source last saw, when held, so it keeps the source's memo.
     */
    private fun unresolvedPage(address: String, data: String): Page = pages[address] ?: run {
        val (index, chapterUrl) = data.split('/', limit = 2).let { it[0].toIntOrNull() to it.getOrNull(1).orEmpty() }
        val chapter = chapter(chapterUrl) ?: SChapter.create().apply {
            url = chapterUrl
            name = ""
        }
        val listed = runBlocking { source.getPageList(chapter) }
        listed.forEach { pageAddress(chapter, it) }
        pages[address] ?: listed.firstOrNull { it.index == index } ?: throw IOException("The chapter no longer has this page")
    }

    /**
     * The request for this page's image with the address it uses, asking the source to name the image when no address
     * is held. Under one lock per page, so the request is never built while another drops the address; a failure
     * to name it is this page's alone and is asked again next time.
     */
    private fun Page.imageCall(): Pair<Call, String?> = synchronized(this) {
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

    private companion object {
        /** A few hundred chapters' worth. */
        const val MAX_PAGES = 8_192
    }
}
