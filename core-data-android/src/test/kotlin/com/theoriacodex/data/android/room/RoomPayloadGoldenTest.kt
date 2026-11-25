package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.assertStoredFormat
import com.theoriacodex.domain.model.CommentHint
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

    @Test fun `golden posts payload decodes to the original post`() {
        val entity = PostEntity("PIXIV", "gold-1", golden("post-payload.json"))

        assertEquals(goldenPost, LocalPostPayloadCodec.decode(entity))
    }

    @Test fun `golden full posts payload decodes every field`() {
        val entity = PostEntity("GELBOORU", "gold-full", golden("post-payload-full.json"))

        assertEquals(goldenFullPost, LocalPostPayloadCodec.decode(entity))
    }

    @Test fun `golden liked tags decode to the original tags`() = runTest {
        val dao = database.codexLikesDao()
        dao.insertPost(PostEntity("PIXIV", "gold-1", golden("post-payload.json")))
        dao.insertLike(LikedPostEntity("profile-main", "PIXIV", "gold-1", 1L, golden("liked-tags.json")))

        val liked = RoomCodexLikesRepository(database).observeLikes("profile-main").first().single()

        assertEquals(listOf("a&b", "c=d", "<x>", "it's", "q>r"), liked.tags)
    }

    @Test fun `upserting an unchanged post keeps the Gson-written row (ADR 0038)`() {
        val dao = database.codexLikesDao()
        val golden = golden("post-payload-full.json")
        dao.insertPost(PostEntity("GELBOORU", "gold-full", golden))

        SharedPosts(dao).upsert(goldenFullPost.copy(commentHint = CommentHint(3)))

        assertEquals(golden, dao.post("GELBOORU", "gold-full")?.payloadJson)
    }

    @Test fun `upserting a post with sparser tags and creators keeps the row`() {
        val dao = database.codexLikesDao()
        val golden = golden("post-payload-full.json")
        dao.insertPost(PostEntity("GELBOORU", "gold-full", golden))
        val sparse = goldenFullPost.copy(
            taxonomy = goldenFullPost.taxonomy.map { it.copy(count = null, translation = null) },
            creatorProfiles = goldenFullPost.creatorProfiles.map { it.copy(profileUrl = null, uploadsQuery = null) },
        )

        SharedPosts(dao).upsert(sparse)

        assertEquals(golden, dao.post("GELBOORU", "gold-full")?.payloadJson)
    }

    @Test fun `upserting a changed post rewrites the row`() {
        val dao = database.codexLikesDao()
        dao.insertPost(PostEntity("GELBOORU", "gold-full", golden("post-payload-full.json")))

        SharedPosts(dao).upsert(goldenFullPost.copy(title = "Renamed & more"))

        val stored = checkNotNull(dao.post("GELBOORU", "gold-full"))
        assertEquals(goldenFullPost.copy(title = "Renamed & more"), LocalPostPayloadCodec.decode(stored))
    }

    @Test fun `golden posts payloads hold every stored key`() {
        val offline = PostStorageCodec.encode(goldenOfflinePost)
        assertStoredFormat(
            golden("post-payload-full.json") to PostStorageCodec.encode(goldenFullPost),
            golden("post-payload-offline.json") to offline,
            golden("post-payload-gallery.json") to PostStorageCodec.encode(goldenGalleryPost),
        )
    }


    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name")) { "missing golden $name" }.readText()
}

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

private val goldenFullPost = Post(
    id = PostId(SourceKey.GELBOORU, "gold-full"),
    preview = ImageRef(
        "https://cdn.example/p.jpg?a=1&b=2",
        "/data/preview.jpg",
        "image/jpeg",
        progressiveUrls = listOf("https://cdn.example/s.jpg"),
        fallbackUrls = listOf("https://mirror.example/p.jpg"),
    ),
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

private val goldenOfflinePost = goldenFullPost.copy(
    full = goldenFullPost.full?.copy(localPath = "/data/offline/full.mp4"),
    media = goldenFullPost.media.mapIndexed { index, media -> media.copy(localPath = "/data/offline/$index") },
)

private val goldenGalleryPost = Post(
    id = PostId(SourceKey.NHENTAI, "gold-gallery"),
    preview = ImageRef("https://example.com/thumb.jpg", null, "image/jpeg"),
    full = null, media = emptyList(), pageUrl = "https://example.com/g/1", width = null, height = null,
    canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
    title = "Gallery", galleryLengthUnknown = true,
)
