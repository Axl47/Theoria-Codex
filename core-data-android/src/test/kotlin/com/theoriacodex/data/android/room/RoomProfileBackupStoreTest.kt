package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.BackupLibrary
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomProfileBackupStoreTest {
    private lateinit var database: TheoriaRoomDatabase
    private lateinit var content: RoomCodexLikesRepository
    private lateinit var recents: RoomRecentsRepository
    private lateinit var backups: RoomProfileBackupStore

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java).allowMainThreadQueries().build()
        content = RoomCodexLikesRepository(database, clock = { 10L })
        recents = RoomRecentsRepository(database, clock = { 20L })
        backups = RoomProfileBackupStore(database)
    }

    @After fun tearDown() = database.close()

    @Test fun `logical snapshot preserves order rules likes timestamps and independent recents memberships`() = runTest {
        val first = content.ensureCodex("a", "First")
        val second = content.ensureCodex("b", "Second")
        content.reorderCodex(second.codexId, 0)
        content.setAutomaticTag(first.codexId, CodexAutomaticTag(SourceKey.PIXIV, "cat"), true)
        val post = post("42")
        content.addItems(first.codexId, listOf(post))
        content.toggleLikeAndSyncSystemCodex("profile-main", ProfileLibraryIds.likes("profile-main"), "Likes", post, listOf("cat"))
        recents.recordWatchedPost(post, ViewerStreamSource.SEARCH, "search", RecentPostSection.WATCHED, 3)
        recents.recordWatchedPost(post, ViewerStreamSource.CODEX, "codex", RecentPostSection.CODEX)
        recents.recordSearch(query(), "search")
        val snapshot = backups.snapshot(true)
        assertEquals(listOf("b", "a", "system_likes_codex"), snapshot.codices.map { it.codexId })
        assertEquals(listOf(CodexAutomaticTag(SourceKey.PIXIV, "cat")), snapshot.codices[1].automaticTags)
        assertEquals(setOf(RecentPostSection.WATCHED, RecentPostSection.CODEX), snapshot.watched.map { it.section }.toSet())
        assertEquals(3, snapshot.watched.single { it.section == RecentPostSection.WATCHED }.maxViewedMediaNumber)
        assertEquals(10L, snapshot.items.first().savedAtEpochMs)
        assertEquals(1, snapshot.posts.size)
        assertEquals(1, snapshot.likes.size)
        assertEquals(query(), snapshot.searches.single().query)
        assertTrue(backups.snapshot(false).watched.isEmpty())
        assertTrue(backups.snapshot(false).searches.isEmpty())
    }

    @Test fun `replaying an import keeps existing media and history while repairing Likes membership`() = runTest {
        val post = post("42")
        content.ensureCodex("old", "Existing")
        content.addItem("old", post)
        recents.recordWatchedPost(post, ViewerStreamSource.SEARCH, "current", viewedMediaNumber = 5)
        recents.recordSearch(query(), "current-query")
        val old = backups.snapshot(true)
        val imported = old.copy(
            codices = old.codices.map { it.copy(codexId = "new", name = "Imported") },
            items = old.items.map { it.copy(codexId = "new") },
            posts = listOf(post.copy(preview = post.preview.copy(localPath = null), title = "Older title")),
            likes = listOf(com.theoriacodex.data.repository.LikedPost("restored-p", post.id, 2L, listOf("cat"))),
            watched = old.watched.map { it.copy(viewedAtEpochMs = 1L, maxViewedMediaNumber = 3) },
            searches = old.searches.map { it.copy(searchedAtEpochMs = 1L) },
        )
        backups.merge(imported)
        backups.merge(imported)
        assertEquals(post, content.getPost(post.id))
        assertEquals(3, content.observeCodices().first().size)
        assertEquals(listOf(post), content.observeCodexPosts("new", CodexSortMode.NEWEST_SAVED).first())
        assertEquals(1, content.observeLikes("restored-p").first().size)
        assertEquals(1, content.observeCodexItems(ProfileLibraryIds.likes("restored-p")).first().size)
        assertEquals(5, recents.observeWatchedPosts().first().single().maxViewedMediaNumber)
        assertEquals(20L, recents.observeSearches().first().single().searchedAtEpochMs)
    }

    @Test fun `deleting a Codex for Undo restores its place rules and otherwise orphaned posts`() = runTest {
        content.ensureCodex("a", "First")
        content.ensureCodex("b", "Second")
        content.setAutomaticTag("a", CodexAutomaticTag(SourceKey.PIXIV, "cat"), true)
        val post = post("42")
        content.addItems("a", listOf(post))

        val snapshot = requireNotNull(content.deleteCodexForUndo("a"))
        assertEquals(null, content.getPost(post.id))
        assertEquals(listOf("b"), content.observeCodices().first().map { it.codexId })

        content.ensureCodex("c", "First")
        content.restoreDeletedCodex(snapshot)

        val restored = content.observeCodices().first()
        assertEquals(listOf("a", "b", "c"), restored.map { it.codexId })
        assertEquals("First 2", restored.first().name)
        assertEquals(listOf(CodexAutomaticTag(SourceKey.PIXIV, "cat")), restored.first().automaticTags)
        assertEquals(listOf(post.id), content.observeCodexItems("a").first().map { it.postId })
        assertEquals(post.id, content.getPost(post.id)?.id)
        assertEquals(null, content.deleteCodexForUndo("missing"))
    }

    @Test fun `clearing Likes returns a snapshot that restores them and their membership`() = runTest {
        val likesCodex = ProfileLibraryIds.likes("profile-main")
        val post = post("42")
        content.toggleLikeAndSyncSystemCodex("profile-main", likesCodex, "Likes", post, listOf("cat"))

        val cleared = content.clearLikesAndLikedMemberships("profile-main", likesCodex)
        assertEquals(1, cleared.clearedLikes)
        assertTrue(content.observeLikedPostIds("profile-main").first().isEmpty())
        assertEquals(null, content.clearLikesAndLikedMemberships("profile-main", likesCodex).undo)

        content.restoreClearedLikes(requireNotNull(cleared.undo))

        assertEquals(setOf(post.id), content.observeLikedPostIds("profile-main").first())
        assertEquals(listOf("cat"), content.observeLikes("profile-main").first().single().tags)
        assertEquals(listOf(post.id), content.observeCodexItems(likesCodex).first().map { it.postId })
    }

    @Test fun `invalid membership rolls back every content write in an import`() = runTest {
        val post = post("new")
        val invalid = BackupLibrary(emptyList(), listOf(CodexItem("missing", post.id, 1L)), listOf(post), emptyList())
        assertTrue(runCatching { backups.merge(invalid) }.isFailure)
        assertEquals(null, content.getPost(post.id))
        assertFalse(content.observeCodices().first().isNotEmpty())
    }

    @Test fun `legacy Likes without snapshots and independently saved Likes members are preserved`() = runTest {
        database.codexLikesDao().insertLike(LikedPostEntity("profile-main", "PIXIV", "legacy", 1L, "[\"cat\"]"))
        val systemId = ProfileLibraryIds.likes("profile-main")
        content.ensureCodex(systemId, "Likes")
        content.addItem(systemId, post("independent"))
        val snapshot = backups.snapshot(false)
        assertEquals(setOf("legacy", "independent"), snapshot.posts.map { it.id.sourcePostId }.toSet())
        backups.merge(snapshot)
        assertEquals(setOf("legacy", "independent"), content.observeCodexItems(systemId).first().map { it.postId.sourcePostId }.toSet())
        assertEquals(listOf("legacy"), content.observeLikes("profile-main").first().map { it.postId.sourcePostId })
    }
}

private fun post(id: String) = Post(PostId(SourceKey.PIXIV, id),
    ImageRef("https://example.test/$id.jpg", "/existing/$id.jpg", "image/jpeg"), null,
    pageUrl = "https://example.test/$id", width = 100, height = 100, canonicalTags = listOf("cat"),
    rawTags = listOf("cat"), authorName = "Artist", createdAtEpochMs = 1L, title = "Existing title")

private fun query() = Query(QueryMode.Source(SourceKey.PIXIV), listOf("cat"), emptyList<String>(), SortMode.NEWEST, null, null)
