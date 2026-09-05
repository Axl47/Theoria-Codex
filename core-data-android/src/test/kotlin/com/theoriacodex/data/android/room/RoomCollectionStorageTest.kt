package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomCollectionStorageTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TheoriaRoomDatabase::class.java,
    )
    private lateinit var database: TheoriaRoomDatabase
    private lateinit var repository: RoomCodexLikesRepository
    private var now = 100L

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), TheoriaRoomDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomCodexLikesRepository(database, clock = { now++ })
    }

    @After fun tearDown() = database.close()

    @Test fun `every sparse collection write preserves previously resolved shared media`() = runTest {
        for (operation in listOf("save", "like", "import", "update")) {
            val rich = post(operation)
            val sparse = rich.copy(
                full = null, media = emptyList(), mediaCount = null, durationMs = null,
                title = null, canonicalTags = emptyList(), rawTags = emptyList(), taxonomy = emptyList(),
            )
            repository.ensureCodex(operation, operation)
            repository.addItems(operation, listOf(rich))
            when (operation) {
                "save" -> repository.addItems(operation, listOf(sparse))
                "like" -> repository.toggleLikeAndSyncSystemCodex("profile", "likes", "Likes", sparse, emptyList())
                "import" -> repository.importCodex("imported", "Imported", listOf(sparse))
                "update" -> repository.updatePost(sparse)
            }
            assertEquals(operation, rich, repository.getPost(rich.id))
        }
        val absent = post("absent")
        repository.updatePost(absent)
        assertNull(repository.getPost(absent.id))
    }

    @Test fun `multi-save deduplicates counts and rolls back all writes when a later item fails`() = runTest {
        repository.ensureCodex("saved", "Saved")
        val existing = post("existing")
        val added = post("added")
        assertEquals(2, repository.addItems("saved", listOf(existing, added, existing)))
        assertEquals(0, repository.addItems("saved", listOf(existing, added)))
        assertTrue(runCatching { repository.addItems("missing", listOf(added)) }.exceptionOrNull() is IllegalStateException)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_second BEFORE INSERT ON codex_items " +
                "WHEN NEW.source_post_id = 'rejected' BEGIN SELECT RAISE(ABORT, 'test rejection'); END",
        )
        val failure = runCatching {
            repository.addItems("saved", listOf(existing.copy(title = "changed"), post("new"), post("rejected")))
        }
        assertTrue(failure.isFailure)
        assertEquals(existing, repository.getPost(existing.id))
        assertNull(repository.getPost(post("new").id))
        assertNull(repository.getPost(post("rejected").id))
        assertEquals(2, repository.observeCodexItems("saved").first().size)
    }

    @Test fun `summaries count all members but never decode old or unselected payloads`() = runTest {
        repository.ensureCodex("selected", "Selected")
        repository.ensureCodex("empty", "Empty")
        repository.ensureCodex("other-profile", "Other")
        val posts = (0..11).map { post("post-$it") }
        repository.addItems("selected", posts)
        repository.addItems("other-profile", listOf(post("hidden"), posts.last()))
        database.codexLikesDao().updatePost("PIXIV", "post-0", "invalid JSON outside cover limit")
        database.codexLikesDao().updatePost("PIXIV", "hidden", "invalid JSON outside selected profile")

        val summaries = repository.observeCodexSummaries(setOf("selected", "empty", "missing")).first()
        assertEquals(listOf("selected", "empty"), summaries.map { it.codexId })
        assertEquals(12, summaries.first().itemCount)
        assertEquals(posts.takeLast(8).asReversed(), summaries.first().coverPosts)
        assertEquals(0, summaries.last().itemCount)
        assertTrue(summaries.last().coverPosts.isEmpty())
        assertTrue(repository.observeCodexSummaries(setOf("selected"), 0).first().single().coverPosts.isEmpty())
        assertTrue(repository.observeCodexSummaries(emptySet()).first().isEmpty())
        assertEquals(posts.map(Post::id).toSet(), repository.observeSavedPostIds(setOf("selected")).first())
        assertEquals(13, repository.observeSavedPostIds(setOf("selected", "other-profile")).first().size)
        assertTrue(repository.observeSavedPostIds(emptySet()).first().isEmpty())
    }

    @Test fun `watched eviction cleans affected identities while preserving other memberships`() = runTest {
        val recents = RoomRecentsRepository(database, watchedLimit = 2, clock = { now++ })
        val shared = post("shared")
        val dao = database.codexLikesDao()
        dao.insertPost(PostEntity("PIXIV", "unrelated-orphan", "{}"))
        recents.recordWatchedPost(shared, ViewerStreamSource.SEARCH, null)
        recents.recordWatchedPost(shared, ViewerStreamSource.CODEX, null, RecentPostSection.CODEX)
        recents.recordWatchedPost(post("next"), ViewerStreamSource.SEARCH, null)
        assertEquals(shared, repository.getPost(shared.id))
        recents.recordWatchedPost(post("last"), ViewerStreamSource.SEARCH, null)
        assertNull(repository.getPost(shared.id))
        assertTrue(dao.post("PIXIV", "unrelated-orphan") != null)

        val saved = post("saved")
        repository.ensureCodex("saved", "Saved")
        repository.addItems("saved", listOf(saved))
        recents.recordWatchedPost(saved, ViewerStreamSource.SEARCH, null)
        val liked = post("liked")
        // Legacy Likes can exist without system-Codex membership; they still retain the Post.
        dao.insertLike(LikedPostEntity("legacy-profile", "PIXIV", "liked", now++, "[]"))
        recents.recordWatchedPost(liked, ViewerStreamSource.SEARCH, null)
        recents.clearAll()
        assertEquals(saved, repository.getPost(saved.id))
        assertEquals(liked, repository.getPost(liked.id))
        assertNull(repository.getPost(post("last").id))
    }

    @Test fun `migration eight to nine retains likes and indexes reverse membership lookups`() {
        val name = "collection-storage-migration-test"
        migrationHelper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO liked_posts(profile_id,source,source_post_id,liked_at_epoch_ms,tags_json) VALUES('profile','PIXIV','saved',12,'[]')")
            close()
        }
        val migrated = migrationHelper.runMigrationsAndValidate(name, 9, true, TheoriaRoomDatabase.MIGRATION_8_9)
        migrated.query("SELECT source_post_id FROM liked_posts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("saved", cursor.getString(0))
        }
        migrated.query("PRAGMA index_info(index_liked_posts_source_source_post_id)").use { cursor ->
            val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
            assertEquals(listOf("source", "source_post_id"), columns)
        }
        migrated.query("EXPLAIN QUERY PLAN SELECT 1 FROM liked_posts WHERE source='PIXIV' AND source_post_id='saved'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(3).contains("index_liked_posts_source_source_post_id"))
        }
        migrated.close()
    }

    private fun post(id: String): Post {
        val full = ImageRef("https://example.com/$id-full.jpg", null, "image/jpeg")
        return Post(
            id = PostId(SourceKey.PIXIV, id),
            preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
            full = full, media = listOf(full), pageUrl = null, width = null, height = null,
            canonicalTags = listOf("tag"), rawTags = listOf("tag"), authorName = null,
            createdAtEpochMs = null, title = id, mediaCount = 1, durationMs = 10_000,
        )
    }
}
