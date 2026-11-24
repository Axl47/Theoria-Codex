package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomLikeTagRewriterTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.codexLikesDao()

    private val merge = { stored: Post, tags: List<String> -> (tags + stored.canonicalTags).distinct() }

    @After fun close() = database.close()

    @Test fun `every profile's Likes get their stored post's tags, page by page, and a second pass changes nothing`() = runTest {
        store(post("1", "aardvark", "zebra"), post("2", "moth", "lamp"))
        like("main", "1", """["aardvark"]""")
        like("other", "1", """["aardvark","cat"]""")
        like("other", "2", """["moth","lamp"]""")
        val rewriter = RoomLikeTagRewriter(database, pageSize = 1)

        assertEquals(2, rewriter.rewriteLikeTags(merge))
        assertEquals(listOf("aardvark", "zebra"), tags("main", "1"))
        assertEquals(listOf("aardvark", "cat", "zebra"), tags("other", "1"))
        assertEquals(listOf("moth", "lamp"), tags("other", "2"))
        assertEquals(0, rewriter.rewriteLikeTags(merge))
    }

    @Test fun `a Like whose tags or post cannot be read, or that has no stored post, is left as it is`() = runTest {
        store(post("1", "zebra"))
        dao.insertPost(PostEntity(SourceKey.PIXIV.id, "broken", "{not a post"))
        like("main", "1", "not json")
        like("main", "broken", """["moth"]""")
        like("main", "missing", """["lamp"]""")

        assertEquals(0, RoomLikeTagRewriter(database).rewriteLikeTags(merge))
        assertEquals("not json", dao.likedPost("main", SourceKey.PIXIV.id, "1").tagsJson)
        assertEquals(listOf("moth"), tags("main", "broken"))
        assertEquals(listOf("lamp"), tags("main", "missing"))
    }

    private fun store(vararg posts: Post) = posts.forEach { post ->
        dao.insertPost(PostEntity(post.id.source.id, post.id.sourcePostId, LocalPostPayloadCodec.encode(post)))
    }

    private fun like(profile: String, post: String, tagsJson: String) {
        dao.insertLike(LikedPostEntity(profile, SourceKey.PIXIV.id, post, 1L, tagsJson))
    }

    private fun tags(profile: String, post: String) = LikedTagsCodec.decode(dao.likedPost(profile, SourceKey.PIXIV.id, post).tagsJson)

    private fun post(value: String, vararg tags: String) = Post(
        id = PostId(SourceKey.PIXIV, value), preview = ImageRef("https://example.com/$value.jpg", null, "image/jpeg"), full = null,
        pageUrl = null, width = null, height = null, canonicalTags = tags.toList(), rawTags = tags.toList(), authorName = null,
        createdAtEpochMs = null,
    )
}
