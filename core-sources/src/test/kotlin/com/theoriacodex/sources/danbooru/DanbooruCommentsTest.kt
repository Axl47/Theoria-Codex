package com.theoriacodex.sources.danbooru

import com.google.gson.JsonParser
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.testing.ScriptedHttpClient
import com.theoriacodex.sources.testing.commentTestPost
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanbooruCommentsTest {
    private val http = ScriptedHttpClient()
    private val adapter = DanbooruSourceAdapter(DanbooruSite.DANBOORU, http)
    private val post = commentTestPost(SourceKey.DANBOORU, "42")
    private val url = "https://danbooru.donmai.us/comments.json"

    private fun query(page: Int) = mapOf(
        "group_by" to "comment",
        "search[post_id]" to "42",
        "search[is_deleted]" to "false",
        "limit" to "100",
        "page" to page.toString(),
        "only" to "id,body,created_at,is_deleted,creator",
    )

    @Test
    fun `DText becomes plain text`() {
        val dtext = """[quote]quoted [b]reply[/b][/quote]
            |Hello [b]bold[/b] [i]it[/i] [u]u[/u] [s]s[/s] [spoiler]boo[/spoiler] [tn]note[/tn]
            |see "the page":https://example.com/a?b=1 and "local":/wiki_pages/help, [[keep]] *this*""".trimMargin()

        assertEquals(
            "quoted reply\nHello bold it u s boo note\nsee the page and local, [[keep]] *this*",
            danbooruDTextToPlain(dtext),
        )
    }

    @Test
    fun `a quote-only comment keeps its text`() {
        assertEquals("a b c", danbooruDTextToPlain("[quote]a [quote]b[/quote] c[/quote]"))
    }

    @Test
    fun `a short page maps live comments and knows its total`() = runTest {
        http.enqueueGet(
            url, query(1),
            body = """[
              {"id":3,"body":"[b]hi[/b]","created_at":"2026-01-02T03:04:05.000-05:00","is_deleted":false,"creator":{"id":11,"name":"alice"}},
              {"id":2,"body":"gone","created_at":"2026-01-02T03:04:05.000-05:00","is_deleted":true,"creator":{"name":"bob"}}
            ]""",
        )

        val page = adapter.comments(post, pageToken = null)

        val comment = page.comments.single()
        assertEquals("3", comment.id)
        assertEquals("hi", comment.body)
        assertEquals("alice", comment.authorName)
        assertNull(comment.authorAvatarUrl)
        assertEquals(
            CreatorProfile(SourceKey.DANBOORU, "alice", "11", "https://danbooru.donmai.us/users/11", "user:alice"),
            comment.author,
        )
        assertEquals(1_767_341_045_000L, comment.createdAtEpochMs)
        assertEquals(1, page.total)
        assertNull(page.nextPageToken)
    }

    @Test
    fun `a commenter without an id has no account, and AIBooru builds its own profile links`() {
        fun parse(json: String, source: SourceKey = SourceKey.DANBOORU, baseUrl: String = "https://danbooru.donmai.us") =
            parseDanbooruComment(JsonParser.parseString(json), source, baseUrl)

        assertNull(parse("""{"id":1,"body":"c","creator":{"name":"alice"}}""")?.author)
        assertEquals("alice", parse("""{"id":1,"body":"c","creator":{"name":"alice"}}""")?.authorName)
        assertEquals(
            "https://aibooru.online/users/5",
            parse("""{"id":1,"body":"c","creator":{"id":5,"name":"eve"}}""", SourceKey.AIBOORU, "https://aibooru.online")?.author?.profileUrl,
        )
    }

    @Test
    fun `comments are anchored as comment_ id on the post page`() {
        assertEquals("comment_3", adapter.nativeCommentAnchor("3"))
    }

    @Test
    fun `a full page points at the next one and hides the total`() = runTest {
        val full = (1..100).joinToString(",", "[", "]") { """{"id":$it,"body":"c","is_deleted":false,"creator":{"name":"a"}}""" }
        http.enqueueGet(url, query(2), body = full)

        val page = adapter.comments(post, pageToken = "2")

        assertEquals(100, page.comments.size)
        assertEquals("3", page.nextPageToken)
        assertNull(page.total)
    }

    @Test
    fun `listing hints follow last_commented_at`() = runTest {
        fun hint(json: String) = JsonParser.parseString(json).asJsonObject.danbooruCommentHint()
        assertEquals(CommentHint.Some, hint("""{"last_commented_at":"2026-01-02T03:04:05.000-05:00"}"""))
        assertEquals(CommentHint.None, hint("""{"last_commented_at":null}"""))
        assertNull(hint("""{"id":1}"""))

        http.enqueueGet(
            "https://danbooru.donmai.us/posts/42.json", emptyMap(),
            body = """{"id":42,"file_url":"https://cdn.donmai.us/42.jpg","file_ext":"jpg","last_commented_at":"2026-01-02T03:04:05.000-05:00"}""",
        )
        assertEquals(CommentHint.Some, adapter.resolvePost(PostId(SourceKey.DANBOORU, "42"))?.commentHint)
    }
}
