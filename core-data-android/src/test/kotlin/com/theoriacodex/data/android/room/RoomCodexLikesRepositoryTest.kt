package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomCodexLikesRepositoryTest {
    private lateinit var database: TheoriaRoomDatabase
    private var now = 100L
    private var id = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TheoriaRoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `codex contract preserves naming order membership and post hydration`() = runTest {
        val repository = repository()
        val first = repository.createCodex(" Favorites ")
        val second = repository.createCodex("favorites")
        val third = repository.createCodex("  ")
        val sparse = post("pixiv", SourceKey.PIXIV).copy(full = null, title = null)
        val ai = post("ai", SourceKey.AIBOORU)

        repository.addItem(first.codexId, sparse)
        repository.addItem(first.codexId, sparse)
        repository.addItem(first.codexId, ai)
        repository.reorderCodex(third.codexId, -10)

        assertEquals("Favorites", first.name)
        assertEquals("favorites 2", second.name)
        assertEquals("Codex", third.name)
        assertEquals(
            listOf(third.codexId, first.codexId, second.codexId),
            repository.observeCodices().first().map { codex -> codex.codexId },
        )
        assertEquals(2, repository.observeCodexItems(first.codexId).first().size)
        assertEquals(
            listOf(SourceKey.AIBOORU, SourceKey.PIXIV),
            repository.observeCodexPosts(first.codexId, CodexSortMode.BY_SOURCE)
                .first()
                .map { value -> value.id.source },
        )

        val hydrated = sparse.copy(
            title = "Hydrated",
            full = ImageRef("https://example.com/full/pixiv.jpg", null, "image/jpeg"),
        )
        repository.updatePost(hydrated)
        assertEquals(hydrated, repository.getPost(hydrated.id))
    }

    @Test
    fun `bulk membership restore preserves timestamps without replacing newer membership`() = runTest {
        val repository = repository()
        val codex = repository.createCodex("Saved")
        val first = post("first")
        val second = post("second")
        repository.addItem(codex.codexId, first)
        repository.addItem(codex.codexId, second)
        val snapshots = repository.observeCodexItems(codex.codexId).first()

        repository.removeItems(codex.codexId, setOf(first.id, second.id))
        assertTrue(repository.observeCodexItems(codex.codexId).first().isEmpty())

        repository.restoreItems(snapshots, listOf(first, second))
        assertEquals(snapshots, repository.observeCodexItems(codex.codexId).first())

        repository.removeItem(codex.codexId, first.id.source, first.id.sourcePostId)
        repository.addItem(codex.codexId, first)
        val newerFirst = repository.observeCodexItems(codex.codexId).first().single { it.postId == first.id }
        repository.restoreItems(snapshots.filter { it.postId == first.id }, listOf(first))
        assertEquals(
            newerFirst,
            repository.observeCodexItems(codex.codexId).first().single { it.postId == first.id },
        )
    }

    @Test
    fun `bulk import commits one deduplicated membership set`() = runTest {
        val repository = repository()
        val first = post("1")
        val second = post("2")

        val result = repository.importCodex(
            codexId = "imported",
            name = "Imported",
            posts = listOf(first, first.copy(title = "newer"), second),
        )

        assertEquals("imported", result.codex.codexId)
        assertEquals(2, result.acceptedPosts)
        assertEquals(2, result.insertedMemberships)
        assertEquals(2, repository.observeCodexItems("imported").first().size)
    }

    @Test
    fun `bulk reorder accepts only one complete Codex order`() = runTest {
        val repository = repository()
        val first = repository.createCodex("First")
        val second = repository.createCodex("Second")
        val third = repository.createCodex("Third")

        val rejected = repository.reorderCodices(listOf(first.codexId, first.codexId, third.codexId))

        assertFalse(rejected.applied)
        assertEquals(0, rejected.movedCodices)
        assertEquals(
            listOf(first.codexId, second.codexId, third.codexId),
            repository.observeCodices().first().map { codex -> codex.codexId },
        )

        val applied = repository.reorderCodices(listOf(third.codexId, first.codexId, second.codexId))

        assertTrue(applied.applied)
        assertEquals(3, applied.movedCodices)
        assertEquals(
            listOf(third.codexId, first.codexId, second.codexId),
            repository.observeCodices().first().map { codex -> codex.codexId },
        )
    }

    @Test
    fun `like and system Codex membership toggle and clear atomically`() = runTest {
        val repository = repository()
        val post = post("liked")

        assertTrue(
            repository.toggleLikeAndSyncSystemCodex(
                profileId = " profile-main ",
                systemCodexId = "system_likes_codex",
                systemCodexName = "Likes",
                post = post,
                tags = listOf(" Tag ", "tag", "Other"),
            ).nowLiked
        )
        assertEquals(listOf("Tag", "Other"), repository.observeLikes("profile-main").first().single().tags)
        assertEquals(listOf(post), repository.observeCodexPosts("system_likes_codex", CodexSortMode.NEWEST_SAVED).first())

        assertFalse(
            repository.toggleLikeAndSyncSystemCodex(
                profileId = "profile-main",
                systemCodexId = "system_likes_codex",
                systemCodexName = "Likes",
                post = post,
                tags = emptyList(),
            ).nowLiked
        )
        assertTrue(repository.observeLikes("profile-main").first().isEmpty())
        assertTrue(repository.observeCodexItems("system_likes_codex").first().isEmpty())

        repository.toggleLikeAndSyncSystemCodex(
            profileId = "profile-main",
            systemCodexId = "system_likes_codex",
            systemCodexName = "Likes",
            post = post,
            tags = listOf("again"),
        )
        val cleared = repository.clearLikesAndLikedMemberships(
            "profile-main",
            "system_likes_codex",
        )
        assertTrue(repository.observeLikes("profile-main").first().isEmpty())
        assertTrue(repository.observeCodexItems("system_likes_codex").first().isEmpty())
        assertEquals(1, cleared.clearedLikes)
        assertEquals(1, cleared.removedMemberships)
    }

    @Test
    fun `matching like adds profile eligible automatic memberships and unlike preserves them`() = runTest {
        val repository = repository()
        val matching = repository.createCodex("Matching")
        val nonmatching = repository.createCodex("Nonmatching")
        val excludedProfile = repository.createCodex("Other profile")
        repository.setAutomaticTag(
            matching.codexId,
            CodexAutomaticTag(SourceKey.PIXIV, "landscape"),
            enabled = true,
        )
        repository.setAutomaticTag(
            nonmatching.codexId,
            CodexAutomaticTag(SourceKey.PIXIV, "portrait"),
            enabled = true,
        )
        repository.setAutomaticTag(
            excludedProfile.codexId,
            CodexAutomaticTag(SourceKey.PIXIV, "landscape"),
            enabled = true,
        )
        val likedPost = post("automatic")

        val liked = repository.toggleLikeAndSyncSystemCodex(
            profileId = "profile-main",
            systemCodexId = "system_likes_codex",
            systemCodexName = "Likes",
            post = likedPost,
            tags = likedPost.canonicalTags,
            eligibleAutomaticCodexIds = setOf(matching.codexId, nonmatching.codexId),
        )

        assertTrue(liked.nowLiked)
        assertEquals(1, liked.automaticMembershipsAdded)
        assertEquals(listOf(likedPost.id), repository.observeCodexItems(matching.codexId).first().map { it.postId })
        assertTrue(repository.observeCodexItems(nonmatching.codexId).first().isEmpty())
        assertTrue(repository.observeCodexItems(excludedProfile.codexId).first().isEmpty())

        val unliked = repository.toggleLikeAndSyncSystemCodex(
            profileId = "profile-main",
            systemCodexId = "system_likes_codex",
            systemCodexName = "Likes",
            post = likedPost,
            tags = emptyList(),
            eligibleAutomaticCodexIds = setOf(matching.codexId),
        )

        assertFalse(unliked.nowLiked)
        assertEquals(0, unliked.automaticMembershipsAdded)
        assertEquals(listOf(likedPost.id), repository.observeCodexItems(matching.codexId).first().map { it.postId })
        assertTrue(repository.observeCodexItems("system_likes_codex").first().isEmpty())
    }

    @Test
    fun `automatic tags round trip with source aware identity`() = runTest {
        val repository = repository()
        val codex = repository.createCodex("Automatic")

        repository.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue sky"),
            enabled = true,
        )
        repository.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue_sky"),
            enabled = true,
        )

        assertEquals(
            listOf(CodexAutomaticTag(SourceKey.GELBOORU, "blue sky")),
            repository.observeCodex(codex.codexId).first()?.automaticTags,
        )
        repository.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue_sky"),
            enabled = false,
        )
        assertTrue(repository.observeCodex(codex.codexId).first()?.automaticTags.isNullOrEmpty())
    }

    @Test
    fun `ordinary Likes clear removes only pre-clear liked memberships`() = runTest {
        val repository = repository()
        val liked = post("liked")
        val manuallySaved = post("manual")
        repository.toggleLikeAndSyncSystemCodex(
            profileId = "profile-main",
            systemCodexId = "system_likes_codex",
            systemCodexName = "Likes",
            post = liked,
            tags = listOf("tag"),
        )
        repository.addItem("system_likes_codex", manuallySaved)

        val cleared = repository.clearLikesAndLikedMemberships(
            "profile-main",
            "system_likes_codex",
        )

        assertTrue(repository.observeLikes("profile-main").first().isEmpty())
        assertEquals(
            listOf(manuallySaved),
            repository.observeCodexPosts("system_likes_codex", CodexSortMode.NEWEST_SAVED).first(),
        )
        assertNull(repository.getPost(liked.id))
        assertEquals(manuallySaved, repository.getPost(manuallySaved.id))
        assertEquals(1, cleared.clearedLikes)
        assertEquals(1, cleared.removedMemberships)
    }

    @Test
    fun `profile delete removes the whole system Codex and cleans orphan posts`() = runTest {
        val repository = repository()
        val liked = post("liked")
        val manuallySaved = post("manual")
        repository.toggleLikeAndSyncSystemCodex(
            profileId = "profile-main",
            systemCodexId = "system_likes_codex",
            systemCodexName = "Likes",
            post = liked,
            tags = listOf("tag"),
        )
        repository.addItem("system_likes_codex", manuallySaved)

        val deleted = repository.clearLikesAndDeleteSystemCodex(
            "profile-main",
            "system_likes_codex",
        )

        assertNull(repository.observeCodex("system_likes_codex").first())
        assertNull(repository.getPost(liked.id))
        assertNull(repository.getPost(manuallySaved.id))
        assertEquals(1, deleted.clearedLikes)
        assertTrue(deleted.systemCodexDeleted)
    }

    @Test
    fun `versioned local Post payload round trips every field`() = runTest {
        val repository = repository()
        val creator = CreatorProfile(
            source = SourceKey.PIXIV,
            displayName = "Primary Artist",
            profileId = "primary-id",
            profileUrl = "https://example.com/primary",
            uploadsQuery = "user:primary",
        )
        val collaborator = CreatorProfile(
            source = SourceKey.GELBOORU,
            displayName = "Collaborator",
            profileId = "collaborator-id",
            profileUrl = null,
            uploadsQuery = "artist:collaborator",
        )
        val complete = Post(
            id = PostId(SourceKey.PIXIV, "complete"),
            preview = ImageRef(
                url = "https://example.com/preview.webp",
                localPath = "/cache/preview.webp",
                mime = "image/webp",
                progressiveUrls = listOf("https://example.com/preview-small.webp"),
                isAnimated = true,
            ),
            full = ImageRef(
                url = "https://example.com/full.png",
                localPath = "/cache/full.png",
                mime = "image/png",
                progressiveUrls = listOf("https://example.com/full-medium.png"),
                isAnimated = false,
            ),
            media = listOf(
                ImageRef("https://example.com/page-1.jpg", null, "image/jpeg"),
                ImageRef(null, "/cache/page-2.jpg", "image/jpeg", isAnimated = false),
            ),
            pageUrl = "https://example.com/posts/complete",
            width = 2048,
            height = 3072,
            canonicalTags = listOf("blue_hair", "night"),
            rawTags = listOf("Blue Hair", "Night"),
            authorName = "Primary Artist",
            createdAtEpochMs = 123456789L,
            title = "Complete Post",
            creatorProfile = creator,
            durationMs = 42_000L,
            mediaCount = 2,
            taxonomy = listOf(
                PostTaxonomyTerm("blue_hair", SearchFacet.TAG, "pixiv"),
                PostTaxonomyTerm("Primary Artist", SearchFacet.ARTIST, "pixiv"),
            ),
            creatorProfiles = listOf(creator, collaborator),
        )
        val codex = repository.createCodex("Round trip")

        repository.addItem(codex.codexId, complete)

        assertEquals(complete, repository.getPost(complete.id))
    }

    @Test
    fun `unsupported and malformed local Post payloads fail explicitly`() = runTest {
        val repository = repository()
        database.codexLikesDao().insertPost(
            PostEntity("PIXIV", "unsupported", """{"schemaVersion":99}""")
        )
        database.codexLikesDao().insertPost(
            PostEntity(
                "PIXIV",
                "malformed",
                """{"schemaVersion":1,"source":"PIXIV","sourcePostId":"malformed"}""",
            )
        )

        assertTrue(
            runCatching { repository.getPost(PostId(SourceKey.PIXIV, "unsupported")) }
                .exceptionOrNull() is UnsupportedLocalPostPayloadVersionException
        )
        assertTrue(
            runCatching { repository.getPost(PostId(SourceKey.PIXIV, "malformed")) }
                .exceptionOrNull() is MalformedLocalPostPayloadException
        )
    }

    @Test
    fun `database failure rolls back both like and system Codex`() = runTest {
        val repository = repository()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_codex_item
            BEFORE INSERT ON codex_items
            BEGIN
              SELECT RAISE(ABORT, 'test rejection');
            END
            """.trimIndent()
        )

        val failure = runCatching {
            repository.toggleLikeAndSyncSystemCodex(
                profileId = "profile-main",
                systemCodexId = "system_likes_codex",
                systemCodexName = "Likes",
                post = post("rollback"),
                tags = listOf("tag"),
            )
        }

        assertTrue(failure.isFailure)
        assertTrue(repository.observeLikes("profile-main").first().isEmpty())
        assertNull(repository.observeCodex("system_likes_codex").first())
    }

    private fun repository(): RoomCodexLikesRepository {
        return RoomCodexLikesRepository(
            database = database,
            clock = { now++ },
            newId = { "codex-${id++}" },
        )
    }
}

private fun post(
    id: String,
    source: SourceKey = SourceKey.PIXIV,
): Post {
    val creator = CreatorProfile(
        source = source,
        displayName = "artist",
        profileId = "profile-$id",
        profileUrl = "https://example.com/creator/$id",
        uploadsQuery = "uploads-$id",
    )
    return Post(
        id = PostId(source, id),
        preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = ImageRef("https://example.com/full/$id.jpg", null, "image/jpeg"),
        pageUrl = "https://example.com/post/$id",
        width = 100,
        height = 100,
        canonicalTags = listOf("landscape"),
        rawTags = listOf("landscape"),
        authorName = "artist",
        createdAtEpochMs = 1L,
        title = "Post $id",
        creatorProfile = creator,
    )
}
