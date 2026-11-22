package com.theoriacodex.mihonhost

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ExtensionImagesTest {
    private val images = ExtensionImages()
    private val source = ImageServerSource().also(images::attach)
    private val page = Page(0, url = "chapter-hash", imageUrl = "/data/1.jpg")

    @Test
    fun `a page gets the address the source builds for it, tagged with the source`() {
        assertEquals("https://cdn.test/data/chapter-hash/data/1.jpg#mihon=1", images.pageAddress(source, page))
    }

    @Test
    fun `a page is fetched by the source's client with the request the source builds`() {
        val address = images.pageAddress(source, page)

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
        val address = images.pageAddress(source, page)
        val missingHostClass = NoClassDefFoundError("androidx/preference/PreferenceScreen")
        source.failure = missingHostClass

        val failure = assertFailsWith<IOException> { fetch(address) }

        assertSame(missingHostClass, failure.cause)
    }

    @Test
    fun `a loaded source answers without loading the extensions again`() {
        var loads = 0
        val loading = ExtensionImages { loads++ }.also { it.attach(source) }

        assertEquals("source-token https://covers.test/1.jpg", fetch("https://covers.test/1.jpg#mihon=1", loading))
        assertEquals(0, loads)
    }

    @Test
    fun `a request for a source that has not loaded yet loads the extensions first`() {
        lateinit var loading: ExtensionImages
        loading = ExtensionImages { loading.attach(source) }

        assertEquals("source-token https://covers.test/1.jpg", fetch("https://covers.test/1.jpg#mihon=1", loading))
    }

    private fun fetch(address: String, images: ExtensionImages = this.images): String =
        appClient(images).newCall(Request.Builder().url(address).build()).execute().body.string()

    /** The app's media client: the interceptor under test, then a network that echoes the address it was asked for. */
    private fun appClient(images: ExtensionImages) = OkHttpClient.Builder()
        .addInterceptor(images)
        .addInterceptor(answering { request -> cannedResponse(request, body = "app ${request.url}") })
        .build()

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
