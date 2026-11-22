package com.theoriacodex.mihonhost

import com.theoriacodex.domain.series.SeriesEntry
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import okhttp3.Headers
import okhttp3.OkHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ExtensionImagesTest {
    private val images = ExtensionImages()
    private val source = ImageServerSource().also { images.publish(listOf(it)) }
    private val page = Page(0, url = "chapter-hash", imageUrl = "/data/1.jpg")

    @Test
    fun `a page gets the address the source builds for it, tagged with the source`() {
        assertEquals("https://cdn.test/data/chapter-hash/data/1.jpg#mihon=1", images.pageAddress(source, page))
    }

    @Test
    fun `a page is fetched by the source's client with the request the source builds`() {
        val address = images.pageAddress(source, page)!!

        assertEquals("source-token page-request", fetch(address))
    }

    @Test
    fun `a cover is fetched by the source's client at its untagged address`() {
        val address = images.coverAddress(source, "https://covers.test/1.jpg")

        assertEquals("https://covers.test/1.jpg#mihon=1", address)
        assertEquals("source-token https://covers.test/1.jpg", fetch(address))
    }

    @Test
    fun `a page saved in an earlier run is fetched by the source's client at its address`() {
        assertEquals("source-token https://cdn.test/old/2.jpg", fetch("https://cdn.test/old/2.jpg#mihon=1"))
    }

    @Test
    fun `an address tagged with a source that is no longer loaded passes down the chain`() {
        assertEquals("app https://cdn.test/old/2.jpg#mihon=7", fetch("https://cdn.test/old/2.jpg#mihon=7"))
    }

    @Test
    fun `an untagged address passes down the chain unchanged`() {
        assertEquals("app https://other.test/a.jpg", fetch("https://other.test/a.jpg"))
    }

    @Test
    fun `an extension failure that is not an IOException still reaches the caller as one`() {
        val address = images.pageAddress(source, page)!!
        val missingHostClass = NoClassDefFoundError("androidx/preference/PreferenceScreen")
        source.failure = missingHostClass

        val failure = assertFailsWith<IOException> { fetch(address) }

        assertSame(missingHostClass, failure.cause)
    }

    @Test
    fun `a loaded source answers without loading the extensions again`() {
        var loads = 0
        val loading = ExtensionImages { loads++ }.also { it.publish(listOf(source)) }

        assertEquals("source-token https://covers.test/1.jpg", fetch("https://covers.test/1.jpg#mihon=1", loading))
        assertEquals(0, loads)
    }

    @Test
    fun `a request for a source that has not loaded yet loads the extensions first`() {
        lateinit var loading: ExtensionImages
        loading = ExtensionImages { loading.publish(listOf(source)) }

        assertEquals("source-token https://covers.test/1.jpg", fetch("https://covers.test/1.jpg#mihon=1", loading))
    }

    @Test
    fun `a chapter's pages are handed out without asking where each image is`() = runTest {
        val reader = PagePerImageSource().also { images.publish(listOf(it)) }

        val pages = MihonSeriesSource(reader, images).pages(SeriesEntry("/c/1", "Chapter 1"))

        assertEquals(listOf("https://reader.test/p/1#mihon-page=2", "https://reader.test/p/broken#mihon-page=2", "https://reader.test/p/3#mihon-page=2"), pages.map { it.url })
        assertEquals(emptyList<String>(), reader.asked)
    }

    @Test
    fun `an image's address is asked for when the image is, and one page failing leaves the others`() = runTest {
        val reader = PagePerImageSource().also { images.publish(listOf(it)) }
        val (first, broken, third) = MihonSeriesSource(reader, images).pages(SeriesEntry("/c/1", "Chapter 1")).map { it.url!! }

        assertEquals("image https://img.test/3.jpg", fetch(third))
        assertFailsWith<IOException> { fetch(broken) }
        assertEquals("image https://img.test/1.jpg", fetch(first))
        fetch(first)

        // Asked once per page that answered, in the order the images were requested.
        assertEquals(listOf("/p/3", "/p/broken", "/p/1"), reader.asked)
    }

    @Test
    fun `a page saved before its image was named is asked about by its address`() {
        val reader = PagePerImageSource().also { images.publish(listOf(it)) }

        assertEquals("image https://img.test/9.jpg", fetch("https://reader.test/p/9#mihon-page=2"))
        assertEquals(listOf("/p/9"), reader.asked)
    }

    private fun fetch(address: String, images: ExtensionImages = this.images): String =
        appClient(images).newCall(Request.Builder().url(address).build()).execute().body.string()

    /** The app's media client: the interceptor under test, then a network that echoes the address it was asked for. */
    private fun appClient(images: ExtensionImages) = OkHttpClient.Builder()
        .addInterceptor(images)
        .addInterceptor(answering { request -> cannedResponse(request, body = "app ${request.url}") })
        .build()

    /** Names each image only on a page of its own, which [asked] records; the page `broken` names none. */
    private class PagePerImageSource : HttpSource() {
        override val id = 2L
        override val name = "Reader"
        override val lang = "en"
        override val baseUrl = "https://reader.test"
        override val supportsLatest = false
        val asked = mutableListOf<String>()

        override val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(answering { request -> cannedResponse(request, body = "image ${request.url}") }).build()

        override fun headersBuilder() = Headers.Builder()

        override suspend fun getPageList(chapter: SChapter) = listOf("1", "broken", "3").mapIndexed { index, page -> Page(index, "$baseUrl/p/$page") }

        override fun imageUrlParse(response: Response): String {
            val path = response.request.url.encodedPath
            asked += path
            check(!path.endsWith("broken")) { "No image on this page" }
            return "https://img.test/${path.substringAfterLast('/')}.jpg"
        }
    }

    /** Builds its image address from `page.url` and `page.imageUrl`, as MangaDex does, and holds a token only its client adds. */
    private class ImageServerSource : HttpSource() {
        override val id = 1L
        override val name = "Fake"
        override val lang = "en"
        override val baseUrl = "https://cdn.test"
        override val supportsLatest = false

        override val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header(TOKEN_HEADER, "source-token").build()) }
            .addInterceptor(
                answering { request ->
                    cannedResponse(request, body = "${request.header(TOKEN_HEADER)} ${request.header(PAGE_HEADER) ?: request.url}")
                },
            )
            .build()

        // The default asks the host for its browser agent string, which this test does not install.
        override fun headersBuilder() = Headers.Builder()

        /** Thrown in place of the next page request. */
        var failure: Throwable? = null

        override fun imageRequest(page: Page): Request {
            failure?.let { throw it }
            return Request.Builder().url("$baseUrl/data/${page.url}${page.imageUrl}").header(PAGE_HEADER, "page-request").build()
        }

        private companion object {
            const val TOKEN_HEADER = "X-Source-Token"
            const val PAGE_HEADER = "X-Page"
        }
    }
}
