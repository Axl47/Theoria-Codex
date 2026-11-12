package com.theoriacodex.sources.gelbooru

import com.google.gson.JsonParser
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.CommentsSourceAdapter
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.ScriptedHttpClient
import com.theoriacodex.sources.testing.commentTestPost
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GelbooruCommentsTest {
    private val http = ScriptedHttpClient()
    private val gelbooruPost = commentTestPost(SourceKey.GELBOORU, "77")
    private val api = "https://gelbooru.com/index.php"
    private val dapiQuery = mapOf("page" to "dapi", "s" to "comment", "q" to "index", "post_id" to "77")
    private val viewQuery = mapOf("page" to "post", "s" to "view", "id" to "77")

    private val safebooru = GelbooruSite(SourceKey.GELBOORU, "Safebooru", "https://safebooru.org")

    private fun gelbooru() = GelbooruSourceAdapter(GelbooruSite.GELBOORU, http)

    private val dapiXml = """
        <?xml version="1.0" encoding="UTF-8"?><comments type="array">
        <comment created_at="2026-01-02 03:04" post_id="77" body="a &amp; b &quot;c&quot;" creator="alice" id="5" creator_id="9"/>
        <comment created_at="2026-01-02 03:05:06" post_id="77" body="second" creator="bob" id="6" creator_id="10"/>
        </comments>
    """.trimIndent()

    private val gelbooruPostPage = """
        <div id="post-view"><h2>Comments:</h2><br />
        <div>
          <div class="commentAvatar"><div class="profileAvatar" style="background: #fff url('user_avatars/avatar_1.jpg') no-repeat center;"></div></div>
          <div class="commentBody ">
            <a href="index.php?page=account&amp;s=profile&amp;id=9"><b>alice</b></a> posted on 2026-01-02 03:04:05 &bull; #100<br /><br />first &amp; line<br />second line<br /><br />
            <span style="font-size: .8em;"><span class="info"><a href="#" onclick="vote()"><img src="x.svg" /></a> <span id="sc100">3</span> score</span>
            <span class="info"><a href="#"><img src="flag.svg" /> flag</a></span></span>
          </div>
        </div><div>
          <div class="commentAvatar"><div class="profileAvatar" style="background: #fff url('https://cdn.example/a.png') no-repeat"></div></div>
          <div class="commentBody ">
            <a href="index.php?page=account&amp;s=profile&amp;id=10"><b>bob</b></a> posted on 2026-01-02 03:06:07 &bull; #101<br /><br />reply<br /><br />
            <span><span id="sc101">0</span></span>
          </div>
        </div>
        <div id="paginator"><div class="pagination"> <b>1</b> <a href="?page=post&amp;s=view&amp;id=77&amp;pid=10">2</a> <a href="?page=post&amp;s=view&amp;id=77&amp;pid=20">3</a></div></div></div>
    """.trimIndent()

    @Test
    fun `DAPI comment XML maps unescaped text and both timestamp shapes without asking for JSON`() = runTest {
        http.enqueueGet(api, dapiQuery, body = dapiXml)

        val page = gelbooru().comments(gelbooruPost, pageToken = null)

        assertEquals(listOf("5", "6"), page.comments.map { it.id })
        assertEquals("a & b \"c\"", page.comments[0].body)
        assertEquals("alice", page.comments[0].authorName)
        assertEquals(
            CreatorProfile(
                SourceKey.GELBOORU, "alice", "9",
                "https://gelbooru.com/index.php?page=account&s=profile&id=9", "user:alice",
            ),
            page.comments[0].author,
        )
        assertNull(page.comments[0].authorAvatarUrl)
        assertEquals(1_767_323_040_000L, page.comments[0].createdAtEpochMs)
        assertEquals(1_767_323_106_000L, page.comments[1].createdAtEpochMs)
        assertEquals(2, page.total)
        assertNull(page.nextPageToken)
        http.assertExhausted()
    }

    @Test
    fun `an empty DAPI answer is zero comments without a fallback`() = runTest {
        http.enqueueGet(api, dapiQuery, body = """<?xml version="1.0" encoding="UTF-8"?><comments type="array"></comments>""")

        val page = gelbooru().comments(gelbooruPost, pageToken = null)

        assertEquals(0, page.total)
        http.assertExhausted()
    }

    @Test
    fun `a disabled DAPI falls back to the post page, remembers that, and pages by pid`() = runTest {
        val adapter = gelbooru()
        http.enqueueGet(api, dapiQuery, body = "Disabled due to abuse.")
        http.enqueueGet(api, viewQuery, body = gelbooruPostPage)

        val first = adapter.comments(gelbooruPost, pageToken = null)

        val (alice, bob) = first.comments
        assertEquals(listOf("100", "101"), first.comments.map { it.id })
        assertEquals("alice", alice.authorName)
        assertEquals("9", alice.author?.profileId)
        assertEquals("user:bob", bob.author?.uploadsQuery)
        assertEquals("first & line\nsecond line", alice.body)
        assertEquals("https://gelbooru.com/user_avatars/avatar_1.jpg", alice.authorAvatarUrl)
        assertEquals("https://cdn.example/a.png", bob.authorAvatarUrl)
        assertEquals("reply", bob.body)
        assertEquals(1_767_323_045_000L, alice.createdAtEpochMs)
        assertEquals("10", first.nextPageToken)
        assertNull(first.total)

        // The DAPI is not asked again once it has answered something other than comments.
        http.enqueueGet(api, viewQuery + ("pid" to "10"), body = gelbooruPostPage.replace("""<a href="?page=post&amp;s=view&amp;id=77&amp;pid=10">2</a>""", ""))
        val second = adapter.comments(gelbooruPost, pageToken = first.nextPageToken)
        assertEquals("20", second.nextPageToken)
        http.assertExhausted()
    }

    @Test
    fun `a post page without a paginator reports its comment count`() = runTest {
        val html = gelbooruPostPage.substringBefore("""<div id="paginator">""")
        http.enqueueGet(api, dapiQuery, body = "Disabled due to abuse.")
        http.enqueueGet(api, viewQuery, body = html)

        val page = gelbooru().comments(gelbooruPost, pageToken = null)

        assertEquals(2, page.total)
        assertNull(page.nextPageToken)
    }

    @Test
    fun `Safebooru's column layout is read too`() {
        val html = """
            <div id="comment-list">
              <div id="c57661"><div class="col1"><a href="index.php?page=account&amp;s=profile&amp;id=1">alice</a>
                <span>&bull; #57661</span><br /><b>posted on 2026-01-02 03:04:05 <a id="sc57661">0</a></b></div>
                <div class="col2">one<br />two &amp; three</div>
                <script>posts[1].comments[57661] = {};</script><hr class="light"/></div>
            </div>
        """.trimIndent()

        val page = parseGelbooruCommentsHtml(html, safebooru, currentPid = 0)

        val comment = page.comments.single()
        assertEquals("57661", comment.id)
        assertEquals("alice", comment.authorName)
        assertEquals("1", comment.author?.profileId)
        assertEquals("one\ntwo & three", comment.body)
        assertEquals(1_767_323_045_000L, comment.createdAtEpochMs)
        assertNull(comment.authorAvatarUrl)
        assertNull(page.nextPid)
    }

    @Test
    fun `an author link that names the user without an id still yields a searchable account`() {
        val html = """<div id="c5"><div class="col1"><a href="index.php?page=account&amp;s=profile&amp;uname=misasami">misasami</a>
            <b>posted on 2026-01-02 03:04:05</b></div><div class="col2">x</div></div>"""

        val author = parseGelbooruCommentsHtml(html, safebooru, currentPid = 0).comments.single().author

        assertEquals(CreatorProfile(SourceKey.GELBOORU, "misasami", null, null, "user:misasami"), author)
    }

    @Test
    fun `a comment record with neither name nor id has no author`() {
        val xml = """<comments type="array"><comment id="3" body="x" creator="" creator_id=""/></comments>"""

        assertNull(parseGelbooruCommentsXml(xml, safebooru)?.single()?.author)
    }

    @Test
    fun `native comment anchors follow the site's markup`() {
        assertNull(gelbooru().nativeCommentAnchor("100"))
        assertEquals("c100", GelbooruSourceAdapter(safebooru, http).nativeCommentAnchor("100"))
    }

    @Test
    fun `comment text that mentions keys is not mistaken for a key failure`() = runTest {
        http.enqueueGet(api, dapiQuery, body = dapiXml.replace("second", "invalid api key lol"))

        val page = gelbooru().comments(gelbooruPost, pageToken = null)

        assertEquals("invalid api key lol", page.comments[1].body)
    }

    @Test
    fun `a keyless refusal is an authentication failure, not a missing comment section`() = runTest {
        val credentials = FakeCredentialsProvider().apply { rule34XxxCredentials = Rule34XxxCredentials("1", "k") }
        val adapter = GelbooruSourceAdapter(GelbooruSite.RULE34XXX, http, credentials::getRule34XxxCredentials)
        http.enqueueGet(
            url = "https://api.rule34.xxx/index.php",
            query = dapiQuery + mapOf("user_id" to "1", "api_key" to "k"),
            body = "<?xml version=\"1.0\"?><error>Missing authentication. Go to api.rule34.xxx for more information</error>",
        )

        val failure = runCatching { adapter.comments(commentTestPost(SourceKey.RULE34XXX, "77"), pageToken = null) }.exceptionOrNull()

        assertEquals(SourceFailureReason.AUTH_REQUIRED, (failure as SourceAdapterException).reason)
    }

    @Test
    fun `every adapter the family builds can read comments`() {
        val site = GelbooruSite.GELBOORU
        listOf(site, site.copy(creatorPosts = false, relatedPosts = false), site.copy(relatedPosts = false), site.copy(creatorPosts = false))
            .forEach { assertTrue(GelbooruSourceAdapter(it, http) is CommentsSourceAdapter) }
    }

    @Test
    fun `listing hints prefer an exact count and read has_comments as a flag or a string`() {
        fun hint(json: String) = JsonParser.parseString(json).asJsonObject.gelbooruCommentHint()

        assertEquals(CommentHint(3), hint("""{"comment_count":3,"has_comments":"false"}"""))
        assertEquals(CommentHint.None, hint("""{"comment_count":"0"}"""))
        assertEquals(CommentHint.Some, hint("""{"has_comments":"true"}"""))
        assertEquals(CommentHint.None, hint("""{"has_comments":false}"""))
        assertNull(hint("""{"id":1}"""))
    }

    @Test
    fun `search results carry the hint`() = runTest {
        http.enqueueGet(
            url = api,
            query = mapOf("page" to "dapi", "json" to "1", "s" to "post", "q" to "index", "pid" to "0", "limit" to "40", "tags" to ""),
            body = """{"post":[{"id":5,"file_url":"https://img.example/5.jpg","tags":"a","has_comments":"true"}]}""",
        )

        val post = gelbooru().search(Query(
            mode = QueryMode.Source(SourceKey.GELBOORU),
            includeTags = emptyList(), excludeTags = emptyList(),
            sort = SortMode.NEWEST, dateRange = null, minScore = null,
        ), null).items.single()

        assertEquals(CommentHint.Some, post.commentHint)
    }
}
