package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.AccountDetails
import com.theoriacodex.domain.model.AccountFeed
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.testing.ScriptedHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GelbooruAccountsTest {
    private val http = ScriptedHttpClient()
    private val index = "https://gelbooru.com/index.php"

    private fun gelbooru() = GelbooruSourceAdapter(GelbooruSite.GELBOORU, http)

    private val account = GelbooruSite.GELBOORU.creatorProfile("alice", "9")!!

    private val profileHtml = """
        <div class="profileAvatar" style="background: #fff url('user_avatars/avatar_9.jpg') no-repeat center;"></div>
        <div class="statistics" id="statistics">
          <div class="row profileStatSeparator"><div class="col-md-9">Joined</div><div class="col-md-3"><small>8/7/07</small></div></div>
          <div class="row profileStatSeparator"><div class="col-md-9">Posts</div><div class="col-md-3"><a href="#">1,234</a></div></div>
          <div class="row profileStatSeparator"><div class="col-md-9">Deleted Posts</div><div class="col-md-3">3</div></div>
          <div class="row profileStatSeparator"><div class="col-md-9">Favorites</div><div class="col-md-3"><a href="#">56</a></div></div>
        </div>
        <div class="row profileStatSeparator"><div class="col-md-9">Comments</div><div class="col-md-3"><a href="#">7</a></div></div>
        <div class="row"><div class="col-xs-12"><span class="profileSectionTitle">About <b>Myself:</b></span><br /><br />line one<br />a &amp; b<br /><br /><b>bold</b></div></div>
    """.trimIndent()

    private fun postListHtml(vararg ids: Int, paginator: String = "") = ids.joinToString("") { id ->
        """<article class="thumbnail-preview"><a id="p$id" href="https://gelbooru.com/index.php?page=post&amp;s=view&amp;id=$id&tags=fav%3A9"><img src="https://img.example/t.jpg"></a></article>"""
    } + """<div id="paginator">$paginator</div>"""

    private fun postJson(id: Int) = """{"post":[{"id":$id,"file_url":"https://img.example/$id.jpg","tags":"a b"}]}"""

    private fun resolveQuery(id: Int) =
        mapOf("page" to "dapi", "json" to "1", "s" to "post", "q" to "index", "id" to id.toString(), "limit" to "1")

    @Test
    fun `profile statistics, avatar and bio are read from the page`() {
        assertEquals(
            AccountDetails(
                avatarUrl = "https://gelbooru.com/user_avatars/avatar_9.jpg",
                bio = "line one\na & b\n\nbold",
                joinedAtEpochMs = 1_186_444_800_000L,
                uploadCount = 1234,
                favoriteCount = 56,
            ),
            parseGelbooruAccountProfile(profileHtml, "https://gelbooru.com"),
        )
    }

    @Test
    fun `a joined date of N-A and a missing bio stay empty, and a page with nothing is no profile`() {
        val html = """<div class="row profileStatSeparator"><div class="col-md-9">Joined</div><div class="col-md-3"><small>N/A</small></div></div>
            <div class="row profileStatSeparator"><div class="col-md-9">Posts</div><div class="col-md-3">0</div></div>
            <div class="row"><div class="col-xs-12"><span class="profileSectionTitle">About <b>Myself:</b></span><br /><br /> </div></div>"""

        assertEquals(AccountDetails(uploadCount = 0), parseGelbooruAccountProfile(html, "https://gelbooru.com"))
        assertNull(parseGelbooruAccountProfile("<html><body>login</body></html>", "https://gelbooru.com"))
    }

    @Test
    fun `a post list gives its ids in order and the next offset from the paginator`() {
        val html = postListHtml(
            5, 3, 5,
            paginator = """<b>1</b> <a href="?page=post&amp;s=list&amp;tags=fav%3A9&amp;pid=42">2</a> <a href="?page=post&amp;s=list&amp;pid=84">3</a>""",
        )

        assertEquals(GelbooruPostList(listOf("5", "3"), nextPid = 42), parseGelbooruPostList(html, "https://gelbooru.com", 0))
        assertNull(parseGelbooruPostList(postListHtml(1, paginator = """<a href="?pid=42">2</a>"""), "https://gelbooru.com", 42).nextPid)
    }

    @Test
    fun `feeds follow the profile shape and the site`() {
        val gelbooru = gelbooru()
        val named = CreatorProfile(SourceKey.GELBOORU, "alice", uploadsQuery = "user:alice")
        val idOnly = CreatorProfile(SourceKey.GELBOORU, "9", profileId = "9")

        assertEquals(listOf(AccountFeed.UPLOADS, AccountFeed.FAVORITES), gelbooru.accountFeeds(account))
        assertEquals(listOf(AccountFeed.UPLOADS), gelbooru.accountFeeds(named))
        assertEquals(listOf(AccountFeed.FAVORITES), gelbooru.accountFeeds(idOnly))
        assertEquals(emptyList<AccountFeed>(), gelbooru.accountFeeds(account.copy(source = SourceKey.DANBOORU)))

        val rule34 = GelbooruSourceAdapter(GelbooruSite.RULE34XXX, http)
        assertEquals(listOf(AccountFeed.UPLOADS), rule34.accountFeeds(GelbooruSite.RULE34XXX.creatorProfile("bob", "4")!!.copy(source = SourceKey.RULE34XXX)))
    }

    @Test
    fun `a site without public profiles has no details and makes no request`() = runTest {
        val rule34 = GelbooruSourceAdapter(GelbooruSite.RULE34XXX, http)

        assertNull(rule34.accountDetails(GelbooruSite.RULE34XXX.creatorProfile("bob", "4")!!))
        http.assertExhausted()
    }

    @Test
    fun `details request the profile page by account id`() = runTest {
        http.enqueueGet(index, mapOf("page" to "account", "s" to "profile", "id" to "9"), body = profileHtml)

        assertEquals(1234, gelbooru().accountDetails(account)?.uploadCount)
        http.assertExhausted()
    }

    @Test
    fun `favorites list the account's ids by offset and resolve them in order`() = runTest {
        val paginator = """<a href="?page=post&amp;s=list&amp;tags=fav%3A9&amp;pid=42">2</a>"""
        http.enqueueGet(index, mapOf("page" to "post", "s" to "list", "tags" to "fav:9"), body = postListHtml(7, 3, paginator = paginator))
        http.enqueueGet(index, resolveQuery(7), body = postJson(7))
        http.enqueueGet(index, resolveQuery(3), body = postJson(3))

        val page = gelbooru().accountPosts(account, AccountFeed.FAVORITES, pageToken = null)

        assertEquals(listOf("7", "3"), page.items.map { it.id.sourcePostId })
        assertEquals("42", page.nextPageToken)
        http.assertExhausted()
    }

    @Test
    fun `a later favorites page sends its offset and the last page ends paging`() = runTest {
        http.enqueueGet(index, mapOf("page" to "post", "s" to "list", "tags" to "fav:9", "pid" to "42"), body = postListHtml(4))
        http.enqueueGet(index, resolveQuery(4), body = postJson(4))

        val page = gelbooru().accountPosts(account, AccountFeed.FAVORITES, pageToken = "42")

        assertEquals(listOf("4"), page.items.map { it.id.sourcePostId })
        assertNull(page.nextPageToken)
    }

    @Test
    fun `favorites whose every post fails to resolve throw instead of looking empty`() = runTest {
        http.enqueueGet(index, mapOf("page" to "post", "s" to "list", "tags" to "fav:9"), body = postListHtml(7))
        http.enqueueGet(index, resolveQuery(7), statusCode = 401)

        val failure = runCatching { gelbooru().accountPosts(account, AccountFeed.FAVORITES, pageToken = null) }.exceptionOrNull()

        assertEquals(SourceFailureReason.AUTH_REQUIRED, (failure as SourceAdapterException).reason)
    }

    @Test
    fun `uploads are the creator uploads search`() = runTest {
        http.enqueueGet(index, mapOf("page" to "dapi", "json" to "1", "s" to "post", "q" to "index", "pid" to "0", "limit" to "40", "tags" to "user:alice"), body = "[]")

        assertEquals(emptyList<Any>(), gelbooru().accountPosts(account, AccountFeed.UPLOADS, pageToken = null).items)
        http.assertExhausted()
    }
}
