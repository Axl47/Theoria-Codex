package com.theoriacodex.sources.danbooru

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.withMediaTerms
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanbooruSourceAdapterTest {
    @Test fun `count capability uses authoritative exact autocomplete results`() = runTest {
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(200,
                """[{"name":"cat_neighbor","post_count":999},{"name":"cat","post_count":42}]""")
        }
        assertEquals(mapOf("cat" to 42), danbooru(http).fetchTagCounts(listOf("cat")))
        assertEquals("cat*", http.lastGet?.query?.get("search[name_matches]"))
    }

    @Test
    fun `score search spends a term on its order metatag and keeps numbered pages (ADR 0041, 0054)`() = runTest {
        val http = FakeHttpClient().apply { nextGetResponse = SourceHttpResponse(200, fullPage()) }

        val page = danbooru(http).search(query(SortMode.TOP), pageToken = "1")

        assertEquals(40, page.items.size)
        assertEquals("2", page.nextPageToken)
        assertEquals("https://danbooru.donmai.us/posts.json", http.lastGet?.url)
        assertEquals("landscape -comic order:score", http.lastGet?.query?.get("tags"))
        assertEquals(THEORIA_SOURCE_USER_AGENT, http.lastGet?.headers?.get("User-Agent"))
    }

    @Test
    fun `newest search sends only the typed tags and pages before the lowest post id (ADR 0041)`() = runTest {
        val http = FakeHttpClient().apply { nextGetResponse = SourceHttpResponse(200, fullPage()) }
        val adapter = danbooru(http)

        val first = adapter.search(query(SortMode.NEWEST, include = "blue hair"), pageToken = null)
        assertEquals("1", http.lastGet?.query?.get("page"))
        assertEquals("blue_hair -comic", http.lastGet?.query?.get("tags"))
        assertEquals("b1", first.nextPageToken)

        adapter.search(query(SortMode.NEWEST), pageToken = first.nextPageToken)
        assertEquals("b1", http.lastGet?.query?.get("page"))
    }

    @Test
    fun `post maps Danbooru timestamps artists samples and durations`() = runTest {
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(200, """
                {"id":7,"created_at":"2026-11-06T19:04:09.830-05:00","file_ext":"mp4","md5":"0123456789ABCDEF0123456789abcdef",
                 "pixiv_id":98214188,"source":"https://i.pximg.net/img-original/img/2022/05/08/23/40/37/98214188_p0.jpg",
                 "file_url":"$CDN/original/7.mp4","large_file_url":"$CDN/original/7.mp4",
                 "preview_file_url":"$CDN/180x180/7.jpg","tag_string":"1girl blue_hair",
                 "tag_string_artist":"some_artist other_artist",
                 "media_asset":{"duration":12.5,"variants":[
                   {"type":"180x180","url":"$CDN/180x180/7.jpg"},
                   {"type":"720x720","url":"$CDN/720x720/7.webp"}]}}
            """.trimIndent())
        }

        val post = requireNotNull(danbooru(http).resolvePost(PostId(SourceKey.DANBOORU, "7")))

        assertEquals(1_794_009_849_830L, post.createdAtEpochMs)
        assertEquals("some artist", post.authorName)
        assertEquals("some_artist", post.creatorProfile?.uploadsQuery)
        assertEquals("$CDN/720x720/7.webp", post.preview.url)
        assertEquals("$CDN/original/7.mp4", post.full?.url)
        assertEquals("video/mp4", post.full?.mime)
        assertEquals(emptyList<String>(), post.full?.progressiveUrls)
        assertEquals(12_500L, post.durationMs)
        assertEquals("https://danbooru.donmai.us/posts/7", post.pageUrl)
        assertEquals("0123456789abcdef0123456789abcdef", post.contentHash)
        assertEquals("https://www.pixiv.net/artworks/98214188", post.originUrl)
    }

    @Test
    fun `a card uses the 720 px variant rather than the 180 px default (ADR 0048)`() = runTest {
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(200, """
                {"id":7,"file_url":"$CDN/original/7.jpg","preview_file_url":"$CDN/180x180/7.jpg",
                 "media_asset":{"variants":[{"type":"180x180","url":"$CDN/180x180/7.jpg"},{"type":"360x360","url":"$CDN/360x360/7.jpg"},
                   {"type":"720x720","url":"$CDN/720x720/7.webp"},{"type":"sample","url":"$CDN/sample/7.jpg"}]}}
            """.trimIndent())
        }

        assertEquals("$CDN/720x720/7.webp", danbooru(http).resolvePost(PostId(SourceKey.DANBOORU, "7"))?.preview?.url)
    }

    @Test
    fun `provider media addresses are upgraded to HTTPS, plain or protocol-relative (ADR 0048)`() = runTest {
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(200, """
                {"id":7,"file_url":"http://cdn.donmai.us/original/7.jpg","large_file_url":"//cdn.donmai.us/sample/7.jpg",
                 "preview_file_url":"HTTP://cdn.donmai.us/180x180/7.jpg"}
            """.trimIndent())
        }

        val post = requireNotNull(danbooru(http).resolvePost(PostId(SourceKey.DANBOORU, "7")))

        assertEquals("https://cdn.donmai.us/original/7.jpg", post.full?.url)
        assertEquals(listOf("https://cdn.donmai.us/sample/7.jpg"), post.full?.progressiveUrls)
        assertEquals("https://cdn.donmai.us/180x180/7.jpg", post.preview.url)
    }

    @Test
    fun `a post whose files are withheld is an account failure, not a missing post (ADR 0041)`() = runTest {
        val http = FakeHttpClient().apply { nextGetResponse = SourceHttpResponse(200, """{"id":7,"file_ext":"jpg"}""") }

        val failure = runCatching { danbooru(http).resolvePost(PostId(SourceKey.DANBOORU, "7")) }.exceptionOrNull()

        assertEquals(SourceFailureReason.AUTH_REQUIRED, (failure as SourceAdapterException).reason)
    }

    @Test
    fun `ugoira plays its webm sample and posts without a file are left out (ADR 0041)`() = runTest {
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(200, """
                [{"id":1,"file_ext":"zip","file_url":"$CDN/original/1.zip","large_file_url":"$CDN/sample/1.webm"},
                 {"id":2,"file_ext":"jpg","tag_string":"restricted"},
                 {"id":3,"file_ext":"png","file_url":"$CDN/original/3.png","large_file_url":"$CDN/sample/3.jpg"}]
            """.trimIndent())
        }

        val posts = danbooru(http).search(query(SortMode.NEWEST), pageToken = null).items

        assertEquals(listOf("1", "3"), posts.map { it.id.sourcePostId })
        assertEquals("$CDN/sample/1.webm", posts[0].full?.url)
        assertEquals("video/webm", posts[0].full?.mime)
        assertEquals(listOf("$CDN/sample/3.jpg"), posts[1].full?.progressiveUrls)
    }

    @Test
    fun `rejected search reports the provider's own explanation (ADR 0041)`() = runTest {
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(422,
                """{"success":false,"message":"You cannot search for more than 2 tags at a time."}""")
        }

        val failure = runCatching { danbooru(http).search(query(SortMode.NEWEST), null) }.exceptionOrNull()

        assertEquals("You cannot search for more than 2 tags at a time.", (failure as SourceAdapterException).message)
    }

    @Test
    fun `creator posts search the artist tag newest first`() = runTest {
        val http = FakeHttpClient().apply { nextGetResponse = SourceHttpResponse(200, fullPage()) }
        val creator = CreatorProfile(
            source = SourceKey.DANBOORU, displayName = "some artist", profileId = "some_artist", uploadsQuery = "some_artist",
        )

        val page = danbooru(http).searchCreatorPosts(creator, pageToken = null)

        assertEquals("some_artist", http.lastGet?.query?.get("tags"))
        assertEquals("b1", page.nextPageToken)
    }

    @Test
    fun `search safely skips non-object records and ignores malformed optional fields`() = runTest {
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(200, """
                [null, "not-a-post",
                 {"id":"42","preview_file_url":{},"file_url":"$CDN/full.jpg","tag_string":[],
                  "image_width":"wide","created_at":"not-a-timestamp"}]
            """.trimIndent())
        }

        val post = danbooru(http).search(query(SortMode.NEWEST), pageToken = null).items.single()

        assertEquals("42", post.id.sourcePostId)
        assertNull(post.preview.url)
        assertNull(post.width)
        assertNull(post.createdAtEpochMs)
        assertEquals(emptyList<String>(), post.canonicalTags)
    }

    @Test
    fun `a file hash finds the post through an md5 search`() = runTest {
        val md5 = "0123456789abcdef0123456789abcdef"
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(200, """[{"id":7,"md5":"$md5","file_url":"$CDN/original/01/23/$md5.jpg"}]""")
        }

        val post = danbooru(http).findByFileHash(md5)

        assertEquals("7", post?.id?.sourcePostId)
        assertEquals("md5:$md5", http.lastGet?.query?.get("tags"))
        http.nextGetResponse = SourceHttpResponse(200, "[]")
        assertNull(danbooru(http).findByFileHash(md5))
    }

    @Test
    fun `guests keep two terms, a ranked sort spends one, and animated falls back to the free filetype term (ADR 0041)`() {
        val capabilities = danbooru(FakeHttpClient()).capabilities
        fun animated(vararg tags: String, sort: SortMode = SortMode.NEWEST) =
            Query(QueryMode.Source(SourceKey.DANBOORU), tags.toList(), emptyList(), sort, null, null)
                .copy(media = MediaFilter.ANIMATED).withMediaTerms(capabilities).includeTags

        assertEquals(listOf("cat", "animated"), animated("cat"))
        assertEquals(listOf("cat", "filetype:gif,mp4,webm,zip"), animated("cat", sort = SortMode.TOP))
        assertEquals(listOf("cat", "dog", "filetype:gif,mp4,webm,zip"), animated("cat", "dog"))
    }

    @Test
    fun `a full raw page with non-object entries still pages from the lowest object id (ADR 0045)`() = runTest {
        val records = (40 downTo 3).map { id -> """{"id":$id,"file_url":"$CDN/original/$id.jpg"}""" } + listOf("null", "\"gone\"")
        val http = FakeHttpClient().apply { nextGetResponse = SourceHttpResponse(200, records.joinToString(",", "[", "]")) }

        val page = danbooru(http).search(query(SortMode.NEWEST), pageToken = null)

        assertEquals(38, page.items.size)
        assertEquals("b3", page.nextPageToken)
    }

    private fun danbooru(http: FakeHttpClient) = DanbooruSourceAdapter(DanbooruSite.DANBOORU, http)

    private fun fullPage(): String = (40 downTo 1).joinToString(",", "[", "]") { id ->
        """{"id":$id,"file_url":"$CDN/original/$id.jpg","tag_string":"tag$id"}"""
    }

    private fun query(sort: SortMode, include: String = "landscape") = Query(
        mode = QueryMode.Source(SourceKey.DANBOORU),
        includeTags = listOf(include),
        excludeTags = listOf("comic"),
        sort = sort,
        dateRange = null,
        minScore = null,
    )

    private companion object {
        const val CDN = "https://cdn.donmai.us"
    }
}
