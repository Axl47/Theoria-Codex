package com.theoriacodex.extensionhost

import com.theoriacodex.domain.coroutines.SharedFlights
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

internal class MihonImages(
    private val source: HttpSource,
    private val key: SourceKey,
    private val images: ExtensionImages,
    private val chapter: (String) -> SChapter? = { null },
) : SourceImages {
    private val pages = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>() {
            override fun removeEldestEntry(eldest: Map.Entry<String, Page>): Boolean = size > MAX_PAGES
        },
    )
    private val listings = SharedFlights<String, List<Page>>()

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

    override fun fetch(address: String, url: HttpUrl, data: String?, caller: Call): Response {
        if (data == null) {
            val page = pages[address]
            return (if (page != null) source.imageCall(page) else source.imageCall(url.toString())).executeFor(caller)
        }
        val page = unresolvedPage(address, data)
        val (call, named) = page.imageCall(caller)
        return fetched(call, caller) { synchronized(page) { if (page.imageUrl == named) page.imageUrl = null } }
    }

    private fun unresolvedPage(address: String, data: String): Page = pages[address] ?: run {
        val (index, chapterUrl) = data.split('/', limit = 2).let { it[0].toIntOrNull() to it.getOrNull(1).orEmpty() }
        val chapter = chapter(chapterUrl) ?: SChapter.create().apply {
            url = chapterUrl
            name = ""
        }
        val listed = runBlocking { listings.share(chapterUrl) { source.getPageList(chapter).onEach { pageAddress(chapter, it) } } }
        pages[address] ?: listed.firstOrNull { it.index == index } ?: throw IOException("The chapter no longer has this page")
    }

    private fun Page.imageCall(caller: Call): Pair<Call, String?> = synchronized(this) {
        if (imageUrl == null) imageUrl = caller.blockingFor { source.getImageUrl(this@imageCall) }
        source.imageCall(this) to imageUrl
    }

    private inline fun fetched(call: Call, caller: Call, forget: () -> Unit): Response {
        val response = try {
            call.executeFor(caller)
        } catch (failure: IOException) {
            forget()
            throw failure
        }
        if (!response.isSuccessful) forget()
        return response
    }

    private companion object {
        const val MAX_PAGES = 8_192
    }
}
