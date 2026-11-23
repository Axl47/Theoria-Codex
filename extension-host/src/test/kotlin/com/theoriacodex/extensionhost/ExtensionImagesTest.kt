package com.theoriacodex.extensionhost

import com.theoriacodex.domain.series.EntryMedia
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import okhttp3.Headers
import okhttp3.OkHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionImagesTest {
    // One install's key, as the host keeps it across restarts.
    private val key = lazyOf(ByteArray(ExtensionImages.KEY_BYTES) { 7 })
    private val images = ExtensionImages(key)
    private val source = ImageServerSource()
    private val server = mihon(source).also { images.publish(listOf(it)) }
    private val serverImages = server.images as MihonImages
    private val page = Page(0, url = "chapter-hash", imageUrl = "/data/1.jpg")
    private val chapter = SChapter.create().apply { url = "/c/1" }

    @Test
    fun `a page gets the address the source builds for it, tagged with the source`() {
        assertTrue(Regex("https://cdn\\.test/data/chapter-hash/data/1\\.jpg#mihon=custom_mihon_1\\.[0-9a-f]{32}").matches(serverImages.pageAddress(chapter, page)!!))
    }

    @Test
    fun `a page is fetched by the source's client with the request the source builds`() {
        val address = serverImages.pageAddress(chapter, page)!!

        assertEquals("source-token page-request", fetch(address))
    }

    @Test
    fun `a cover is fetched by the source's client at its untagged address`() {
        val address = images.cover(server.key, "https://covers.test/1.jpg")

        assertTrue(address.startsWith("https://covers.test/1.jpg#mihon=custom_mihon_1."))
        assertEquals("source-token https://covers.test/1.jpg", fetch(address))
    }

    @Test
    fun `a stored cover is tagged from its series' source key without loading the extension`() {
        val asked = mutableListOf<SourceKey>()
        lateinit var cold: ExtensionImages
        cold = ExtensionImages(key) { source ->
            asked += source
            cold.publish(listOf(mihon(this.source, cold)))
        }
        val stored = Series(SeriesId(server.key, "/s"), "Series", coverUrl = "https://covers.test/1.jpg")

        val address = checkNotNull(cold.address(stored))
        assertTrue(asked.isEmpty())
        // The same address a loaded host gives, and the first request for it loads that source's extension.
        assertEquals(images.address(stored), address)
        assertEquals("source-token https://covers.test/1.jpg", fetch(address, cold))
        assertEquals(listOf(server.key), asked)
        // A cover that names another source, as an earlier build stored it or a backup could: its own source still fetches it.
        val named = stored.copy(coverUrl = "https://covers.test/1.jpg#mihon=7.00000000000000000000000000000000")
        assertEquals(address, cold.address(named))
        assertEquals(null, cold.address(stored.copy(coverUrl = null)))
    }

    @Test
    fun `a cover of a source that is no extension's is handed over as stored`() {
        val custom = Series(SeriesId(SourceKey.parseOrThrow("custom_site_example"), "/s"), "Series", coverUrl = "https://site.test/c.jpg#mihon=1")

        assertEquals("https://site.test/c.jpg#mihon=1", images.address(custom))
    }

    @Test
    fun `a page saved in an earlier run is fetched by the source's client at its address`() {
        val saved = images.cover(server.key, "https://cdn.test/old/2.jpg")

        assertEquals("source-token https://cdn.test/old/2.jpg", fetch(saved, ExtensionImages(key).also { it.publish(listOf(mihon(source, it))) }))
    }

    @Test
    fun `a tag this host did not issue is not sent through an extension and is taken off`() {
        var loads = 0
        lateinit var checking: ExtensionImages
        checking = ExtensionImages(key) {
            loads += 1
            checking.publish(listOf(server))
        }
        val issued = checking.cover(server.key, "https://covers.test/1.jpg")
        // What a Custom Site could put on a thumbnail: a bare tag, a made-up code, and a real code moved to its own host.
        val forged = listOf(
            "https://custom-site.test/thumb.jpg#mihon=custom_mihon_1",
            "https://custom-site.test/thumb.jpg#mihon=custom_mihon_1.00000000000000000000000000000000",
            "https://custom-site.test/thumb.jpg#" + issued.substringAfter('#'),
            "https://custom-site.test/p/1#mihon=custom_mihon_1.00000000000000000000000000000000/0//c/1",
        )

        forged.forEach { assertEquals("app " + it.substringBefore('#'), fetch(it, checking)) }
        // Nor does a forged tag load the extensions.
        assertEquals(0, loads)
        assertEquals("source-token https://covers.test/1.jpg", fetch(issued, checking))
        // Another install's key issues nothing this one honors.
        assertEquals("app https://covers.test/1.jpg", fetch(ExtensionImages().cover(server.key, "https://covers.test/1.jpg"), checking))
    }

    @Test
    fun `an address tagged with a source that is no longer loaded passes down the chain`() {
        val gone = SourceKey.parseOrThrow("custom_mihon_7")

        assertEquals("app https://cdn.test/old/2.jpg", fetch(images.cover(gone, "https://cdn.test/old/2.jpg")))
    }

    @Test
    fun `covers of two kinds' sources with the same numeric id are fetched by their own source`() {
        val anime = HostedSource(
            series = object : SeriesSource by server.series {
                override val key = SourceKey.parseOrThrow("custom_aniyomi_1")
            },
            name = "Anime",
            language = "en",
            siteAddress = { null },
            settings = null,
            images = SourceImages { _, url, _ -> cannedResponse(Request.Builder().url(url).build(), body = "anime $url") },
        )
        images.publish(listOf(server, anime))

        assertEquals("source-token https://covers.test/1.jpg", fetch(images.cover(server.key, "https://covers.test/1.jpg")))
        assertEquals("anime https://covers.test/1.jpg", fetch(images.cover(anime.key, "https://covers.test/1.jpg")))
    }

    @Test
    fun `an untagged address passes down the chain unchanged`() {
        assertEquals("app https://other.test/a.jpg", fetch("https://other.test/a.jpg"))
    }

    @Test
    fun `an extension failure that is not an IOException still reaches the caller as one`() {
        val address = serverImages.pageAddress(chapter, page)!!
        val missingHostClass = NoClassDefFoundError("androidx/preference/PreferenceScreen")
        source.failure = missingHostClass

        val failure = assertFailsWith<IOException> { fetch(address) }

        assertSame(missingHostClass, failure.cause)
    }

    @Test
    fun `a source turned off or removed stops answering once the sources are published again`() {
        val address = images.cover(server.key, "https://covers.test/1.jpg")
        assertEquals("source-token https://covers.test/1.jpg", fetch(address))

        images.publish(emptyList())

        assertEquals("app https://covers.test/1.jpg", fetch(address))
    }

    @Test
    fun `a request loads only its own source's extension, and only while that source is not published`() {
        val asked = mutableListOf<SourceKey>()
        lateinit var loading: ExtensionImages
        loading = ExtensionImages(key) { source ->
            asked += source
            loading.publish(listOf(server))
        }
        val address = images.cover(server.key, "https://covers.test/1.jpg")

        repeat(3) { assertEquals("source-token https://covers.test/1.jpg", fetch(address, loading)) }
        assertEquals(listOf(server.key), asked)
    }

    @Test
    fun `a source its load does not publish is asked for once per publish`() {
        var loads = 0
        val off = ExtensionImages(key) { loads += 1 }
        val address = images.cover(server.key, "https://covers.test/1.jpg")

        repeat(3) { assertEquals("app https://covers.test/1.jpg", fetch(address, off)) }
        assertEquals(1, loads)
        off.publish(emptyList())
        assertEquals("app https://covers.test/1.jpg", fetch(address, off))
        assertEquals(2, loads)
    }

    @Test
    fun `a load that fails is not retried per image and the request goes out as an ordinary one`() {
        var loads = 0
        val failing = ExtensionImages(key) {
            loads += 1
            error("The device could not be listed")
        }
        val address = images.cover(server.key, "https://covers.test/1.jpg")

        repeat(2) { assertEquals("app https://covers.test/1.jpg", fetch(address, failing)) }
        assertEquals(1, loads)
        // The next listing that succeeds publishes as usual.
        failing.publish(listOf(server))
        assertEquals("source-token https://covers.test/1.jpg", fetch(address, failing))
    }

    @Test
    fun `a chapter's pages are handed out without asking where each image is`() = runTest {
        val reader = PagePerImageSource()

        val pages = mihon(reader).also { images.publish(listOf(it)) }.series.entryPages(SeriesEntry("/c/1", "Chapter 1"))

        val tagged = listOf("1", "broken", "3").mapIndexed { index, page -> Regex("https://reader\\.test/p/$page#mihon=custom_mihon_2\\.[0-9a-f]{32}/$index//c/1") }
        assertTrue(tagged.zip(pages.map { it.url!! }).all { (expected, address) -> expected.matches(address) })
        assertEquals(emptyList<String>(), reader.asked)
    }

    @Test
    fun `an image's address is asked for when the image is, and one page failing leaves the others`() = runTest {
        val reader = PagePerImageSource()
        val (first, broken, third) = mihon(reader).also { images.publish(listOf(it)) }.series.entryPages(SeriesEntry("/c/1", "Chapter 1")).map { it.url!! }

        assertEquals("image https://img.test/3-2.jpg", fetch(third))
        assertFailsWith<IOException> { fetch(broken) }
        assertEquals("image https://img.test/1-0.jpg", fetch(first))
        fetch(first)

        // Asked once per page that answered, in the order the images were requested.
        assertEquals(listOf("/p/3", "/p/broken", "/p/1"), reader.asked)
    }

    @Test
    fun `pages that share an address are told apart, also once they are no longer held`() = runTest {
        val reader = PagePerImageSource(pages = listOf("viewer", "viewer"))
        val (first, second) = mihon(reader).also { images.publish(listOf(it)) }.series.entryPages(SeriesEntry("/c/1", "Chapter 1")).map { it.url!! }
        val restarted = ExtensionImages(key).also { it.publish(listOf(mihon(reader, it))) }

        assertTrue(second.startsWith("https://reader.test/p/viewer#mihon=custom_mihon_2.") && second.endsWith("/1//c/1") && first != second)
        assertEquals(listOf("image https://img.test/viewer-1.jpg", "image https://img.test/viewer-0.jpg"), listOf(fetch(second), fetch(first)))
        // Nothing holds the page after a restart: its chapter is listed again and the page at the same place taken.
        assertEquals("image https://img.test/viewer-1.jpg", fetch(second, restarted))
        assertEquals(listOf("/c/1", "/c/1"), reader.listed)
    }

    @Test
    fun `an image address that did not give the image is asked for again`() = runTest {
        val reader = PagePerImageSource()
        val first = mihon(reader).also { images.publish(listOf(it)) }.series.entryPages(SeriesEntry("/c/1", "Chapter 1")).first().url!!

        reader.expired = true
        assertEquals("expired", fetch(first))
        reader.expired = false
        assertEquals("image https://img.test/1-0.jpg", fetch(first))
        fetch(first)

        assertEquals(listOf("/p/1", "/p/1"), reader.asked)
    }

    /** [source] as the host holds it, its addresses tagged by [images]. */
    private fun mihon(source: HttpSource, images: ExtensionImages = this.images) = MihonKind.hosted(source, HostServices(images))

    /** The address this host hands out for a series of [source] whose stored cover is [url]. */
    private fun ExtensionImages.cover(source: SourceKey, url: String): String =
        checkNotNull(address(Series(SeriesId(source, "/s"), "Series", coverUrl = url)))

    private fun fetch(address: String, images: ExtensionImages = this.images): String =
        appClient(images).newCall(Request.Builder().url(address).build()).execute().body.string()

    /** The app's media client: the interceptor under test, then a network that echoes the address it was asked for. */
    private fun appClient(images: ExtensionImages) = OkHttpClient.Builder()
        .addInterceptor(images)
        .addInterceptor(answering { request -> cannedResponse(request, body = "app ${request.url}") })
        .build()

    /**
     * Names each image only on a page of its own, by that page and its place in the chapter; [asked] records each
     * time. The page `broken` names none, and while [expired] the image server turns every image down.
     */
    private class PagePerImageSource(private val pages: List<String> = listOf("1", "broken", "3")) : HttpSource() {
        override val id = 2L
        override val name = "Reader"
        override val lang = "en"
        override val baseUrl = "https://reader.test"
        override val supportsLatest = false
        val asked = mutableListOf<String>()
        val listed = mutableListOf<String>()
        var expired = false

        override val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                answering { request ->
                    val image = request.url.host == "img.test"
                    if (image && expired) cannedResponse(request, body = "expired", code = 403) else cannedResponse(request, body = "image ${request.url}")
                },
            )
            .build()

        override fun headersBuilder() = Headers.Builder()

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            listed += chapter.url
            return pages.mapIndexed { index, page -> Page(index, "$baseUrl/p/$page") }
        }

        override suspend fun getImageUrl(page: Page): String {
            val path = page.url.removePrefix(baseUrl)
            asked += path
            check(!path.endsWith("broken")) { "No image on this page" }
            return "https://img.test/${path.substringAfterLast('/')}-${page.index}.jpg"
        }
    }

    /** Builds its image address from `page.url` and `page.imageUrl`, as some sources do, and holds a token only its client adds. */
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

/** The pages a manga source hands out for [entry]; a source that answers with streams fails the test. */
private suspend fun SeriesSource.entryPages(entry: SeriesEntry): List<ImageRef> = (media(entry) as EntryMedia.Pages).pages
