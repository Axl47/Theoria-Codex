package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Golden files under `resources/golden/` were written by the Gson-based production encoders (no nulls,
 * `&`-style escapes for `& = < > '`). They stay frozen; later serializers must keep reading them.
 */
@RunWith(RobolectricTestRunner::class)
class RoomPayloadGoldenTest {
    private lateinit var database: TheoriaRoomDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        database.close()
    }

    // Pins: a posts.payload_json row as production wrote it decodes to the expected Post, nulls staying null.
    @Test fun `golden posts payload decodes to the original post`() {
        val entity = PostEntity("PIXIV", "gold-1", golden("post-payload.json"))

        assertEquals(goldenPost, LocalPostPayloadCodec(Gson()).decode(entity))
    }

    // Pins: every stored field, nested record, and escape the post encoder writes decodes back to the same Post.
    @Test fun `golden full posts payload decodes every field`() {
        val entity = PostEntity("GELBOORU", "gold-full", golden("post-payload-full.json"))

        assertEquals(goldenFullPost, LocalPostPayloadCodec(Gson()).decode(entity))
    }

    // Pins: liked_posts.tags_json as production wrote it decodes to the same tag list through the Likes read path.
    @Test fun `golden liked tags decode to the original tags`() = runTest {
        val dao = database.codexLikesDao()
        dao.insertPost(PostEntity("PIXIV", "gold-1", golden("post-payload.json")))
        dao.insertLike(LikedPostEntity("profile-main", "PIXIV", "gold-1", 1L, golden("liked-tags.json")))

        val liked = RoomCodexLikesRepository(database).observeLikes("profile-main").first().single()

        assertEquals(listOf("a&b", "c=d", "<x>", "it's", "q>r"), liked.tags)
    }

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name")) { "missing golden $name" }.readText()
}

/** Source of `golden/post-payload.json`: special characters in strings, several nullable fields left null. */
private val goldenPost = Post(
    id = PostId(SourceKey.PIXIV, "gold-1"),
    preview = ImageRef(
        "https://example.com/p.jpg?a=1&b=2",
        null,
        "image/jpeg",
        progressiveUrls = listOf("https://example.com/s.jpg?x=<y>"),
    ),
    full = null,
    pageUrl = "https://example.com/post?id=1&lang='en'",
    width = 640,
    height = null,
    canonicalTags = listOf("a&b", "c=d"),
    rawTags = listOf("it's", "<raw>"),
    authorName = null,
    createdAtEpochMs = 1_700_000_000_000L,
    title = "Tom & Jerry <3 = 'fun' > all",
    taxonomy = listOf(PostTaxonomyTerm(value = "a&b"), PostTaxonomyTerm(value = "c=d")),
)

private val goldenCreator = CreatorProfile(
    SourceKey.GELBOORU,
    "Tom & Jerry",
    "42",
    "https://g.example/u?id=42&x=<y>",
    "user:42 'q'",
)

private val goldenVideo = ImageRef(
    url = "https://cdn.example/v.mp4?sig=a&b",
    localPath = null,
    mime = "video/mp4",
    progressiveUrls = listOf("https://cdn.example/v-low.mp4"),
    isAnimated = true,
    videoVariants = listOf(
        VideoVariant("https://cdn.example/v-720.mp4", 720, mime = "video/mp4"),
        VideoVariant("https://cdn.example/v.mp4?sig=a&b", null, original = true),
    ),
    thumbnailUrl = "https://cdn.example/t.jpg?w=<1>",
    fallbackUrls = listOf("https://mirror.example/v.mp4"),
)

/** Source of `golden/post-payload-full.json`: every stored property set, special characters throughout. */
private val goldenFullPost = Post(
    id = PostId(SourceKey.GELBOORU, "gold-full"),
    preview = ImageRef(
        "https://cdn.example/p.jpg?a=1&b=2",
        "/data/preview.jpg",
        "image/jpeg",
        progressiveUrls = listOf("https://cdn.example/s.jpg"),
        fallbackUrls = listOf("https://mirror.example/p.jpg"),
    ),
    // The record stores no thumbnail for the full image, only for media entries.
    full = goldenVideo.copy(thumbnailUrl = null),
    media = listOf(goldenVideo, ImageRef("https://cdn.example/2.png", null, "image/png")),
    pageUrl = "https://g.example/post?id=1&lang='en'",
    width = 1920,
    height = 1080,
    canonicalTags = listOf("a&b", "c=d"),
    rawTags = listOf("it's", "<raw>"),
    authorName = "Tom & Jerry",
    createdAtEpochMs = 1_700_000_000_000L,
    title = "<b>Bold</b> & 'quoted' = yes",
    creatorProfile = goldenCreator,
    durationMs = 12_345L,
    mediaCount = 2,
    taxonomy = listOf(
        PostTaxonomyTerm("a&b", SearchFacet.ARTIST, "artist", 7, "A & B"),
        PostTaxonomyTerm("c=d"),
    ),
    creatorProfiles = listOf(goldenCreator, CreatorProfile(SourceKey.DANBOORU, "<other>")),
    contentHash = "0123456789abcdef0123456789abcdef",
)
