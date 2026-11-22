package com.theoriacodex.mihonhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MihonSourceAdapterTest {
    private val source = FakeSource()
    private val adapter = MihonSourceAdapter(MihonSourceAdapter.sourceKey(source), source, ExtensionImages())

    @Test
    fun `no terms with the popular sort lists popular series`() = runTest {
        adapter.search(query(SortMode.POPULAR), pageToken = null)

        assertEquals(listOf("popular 1"), source.calls)
    }

    @Test
    fun `newest lists the latest series when the source has them`() = runTest {
        adapter.search(query(SortMode.NEWEST), pageToken = null)

        assertEquals(listOf("latest 1"), source.calls)
    }

    @Test
    fun `newest falls back to popular when the source has no latest`() = runTest {
        val noLatest = FakeSource(supportsLatest = false)
        val adapter = MihonSourceAdapter(MihonSourceAdapter.sourceKey(noLatest), noLatest, ExtensionImages())

        adapter.search(query(SortMode.NEWEST), pageToken = null)

        assertEquals(listOf("popular 1"), noLatest.calls)
        assertFalse(adapter.capabilities.supportsSortNewest)
    }

    @Test
    fun `include tags become one search text with spaces for underscores`() = runTest {
        adapter.search(query(SortMode.POPULAR, "one_punch", "man"), pageToken = null)

        assertEquals(listOf("search 1 'one punch man'"), source.calls)
    }

    @Test
    fun `the page token is the page number and the next one appears only with more pages`() = runTest {
        source.listing = MangasPage(emptyList(), hasNextPage = true)
        val first = adapter.search(query(SortMode.POPULAR), pageToken = null)
        assertEquals("2", first.nextPageToken)

        source.listing = MangasPage(emptyList(), hasNextPage = false)
        val last = adapter.search(query(SortMode.POPULAR), pageToken = first.nextPageToken)

        assertEquals(listOf("popular 1", "popular 2"), source.calls)
        assertNull(last.nextPageToken)
    }

    @Test
    fun `a listed series becomes a post that resolvePost can open`() = runTest {
        source.listing = MangasPage(listOf(manga("/series/a/b", "Series", cover = "https://cdn.test/c.jpg")), hasNextPage = false)

        val post = adapter.search(query(SortMode.POPULAR), pageToken = null).items.single()

        assertEquals("/series/a/b", String(Base64.getUrlDecoder().decode(post.id.sourcePostId)))
        assertEquals("https://cdn.test/c.jpg", post.preview.url)
        assertTrue(post.galleryLengthUnknown)
        assertEquals("/series/a/b", MihonSourceAdapter.mangaUrl(post.id))
        source.chapters = listOf(chapter("Chapter 1", "/c/1"))
        source.pages = listOf(Page(0, imageUrl = "https://cdn.test/1.jpg"))
        assertEquals("Series · Chapter 1", adapter.resolvePost(post.id)?.title)
    }

    @Test
    fun `resolvePost returns the first chapter's pages`() = runTest {
        source.listing = MangasPage(listOf(manga("/s", "Series")), hasNextPage = false)
        source.chapters = listOf(chapter("Chapter 2", "/c/2"), chapter("Chapter 1", "/c/1"))
        source.pages = listOf(Page(0, imageUrl = "https://cdn.test/1.jpg"), Page(1, imageUrl = "https://cdn.test/2.jpg"))
        val listed = adapter.search(query(SortMode.POPULAR), pageToken = null).items.single()

        val post = requireNotNull(adapter.resolvePost(listed.id))

        assertEquals(listOf("/c/1"), source.pageListRequests)
        assertEquals("Series · Chapter 1", post.title)
        assertEquals(listOf("https://cdn.test/1.jpg", "https://cdn.test/2.jpg"), post.media.map { it.url })
        assertEquals("https://cdn.test/1.jpg", post.full?.url)
        assertEquals(2, post.mediaCount)
        assertFalse(post.galleryLengthUnknown)
    }

    @Test
    fun `details that name only what they add keep the listed title and cover`() = runTest {
        source.listing = MangasPage(listOf(manga("/s", "Series", cover = "https://cdn.test/c.jpg")), hasNextPage = false)
        source.details = SManga.create().also { it.description = "Only a description" }
        source.chapters = listOf(chapter("Chapter 1", "/c/1"))
        source.pages = listOf(Page(0, imageUrl = "https://cdn.test/1.jpg"))
        val listed = adapter.search(query(SortMode.POPULAR), pageToken = null).items.single()

        val post = requireNotNull(adapter.resolvePost(listed.id))

        assertEquals("Series · Chapter 1", post.title)
        assertEquals("https://cdn.test/c.jpg", post.preview.url)
    }

    @Test
    fun `resolvePost returns null when there is no chapter or no page`() = runTest {
        val id = PostId(adapter.sourceKey, encoded("/s"))

        source.chapters = emptyList()
        assertNull(adapter.resolvePost(id))

        source.chapters = listOf(chapter("Chapter 1", "/c/1"))
        source.pages = emptyList()
        assertNull(adapter.resolvePost(id))
    }

    @Test
    fun `an id that was never listed resolves from its address alone`() = runTest {
        source.chapters = listOf(chapter("Chapter 1", "/c/1"))
        source.pages = listOf(Page(0, imageUrl = "https://cdn.test/1.jpg"))

        val post = adapter.resolvePost(PostId(adapter.sourceKey, encoded("/series/x")))

        assertEquals(listOf("/series/x"), source.detailRequests)
        assertEquals("Chapter 1", post?.title)
    }

    @Test
    fun `failures map to the reasons built-in sources report`() = runTest {
        assertEquals(SourceFailureReason.RATE_LIMITED, failureOf(HttpException(429)))
        assertEquals(SourceFailureReason.NETWORK, failureOf(HttpException(403)))
        assertEquals(SourceFailureReason.NETWORK, failureOf(HttpException(500)))
        assertEquals(SourceFailureReason.NETWORK, failureOf(IOException("offline")))
        assertEquals(SourceFailureReason.UNKNOWN, failureOf(NoSuchMethodError("missing")))
        assertEquals(SourceFailureReason.PARSE, failureOf(IllegalStateException("bad html")))
    }

    @Test
    fun `cancellation passes through untouched`() = runTest {
        source.failure = CancellationException("cancelled")

        assertFailsWith<CancellationException> { adapter.search(query(SortMode.POPULAR), pageToken = null) }
    }

    private suspend fun failureOf(error: Throwable): SourceFailureReason {
        source.failure = error
        return assertFailsWith<SourceAdapterException> { adapter.search(query(SortMode.POPULAR), pageToken = null) }.reason
    }

    private fun query(sort: SortMode, vararg terms: String) = Query(
        mode = QueryMode.Source(adapter.sourceKey),
        includeTags = terms.toList(),
        excludeTags = emptyList(),
        sort = sort,
        dateRange = null,
        minScore = null,
    )

    private fun encoded(url: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray())

    private fun manga(url: String, title: String, cover: String? = null): SManga = SManga.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = cover
    }

    private fun chapter(name: String, url: String): SChapter = SChapter.create().also {
        it.name = name
        it.url = url
    }

    private class FakeSource(override val supportsLatest: Boolean = true) : CatalogueSource {
        override val id = 7L
        override val name = "Fake"
        override val lang = "en"

        var listing = MangasPage(emptyList(), hasNextPage = false)
        var chapters = emptyList<SChapter>()

        /** What the details page returns; the series it was asked about when null. */
        var details: SManga? = null
        var pages = emptyList<Page>()
        var failure: Throwable? = null

        val calls = mutableListOf<String>()
        val detailRequests = mutableListOf<String>()
        val pageListRequests = mutableListOf<String>()

        override suspend fun getPopularManga(page: Int): MangasPage = answer("popular $page")

        override suspend fun getLatestUpdates(page: Int): MangasPage = answer("latest $page")

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = answer("search $page '$query'")

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate {
            detailRequests += manga.url
            return SMangaUpdate(details ?: manga, this.chapters)
        }

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            pageListRequests += chapter.url
            return pages
        }

        private fun answer(call: String): MangasPage {
            calls += call
            failure?.let { throw it }
            return listing
        }
    }
}
