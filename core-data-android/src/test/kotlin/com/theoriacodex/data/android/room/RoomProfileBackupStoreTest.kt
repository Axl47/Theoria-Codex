package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.BackupLibrary
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
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
        content.reorderCodices(listOf(second.codexId, first.codexId))
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

    @Test fun `series restore with their progress and add to a stored series without taking from it`() = runTest {
        // An extension source: restoring needs no extension installed, since nothing here asks one.
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val series = RoomSeriesRepository(database, clock = { 30L })
        series.store(Series(id, "Title", coverUrl = "https://cdn.test/c.jpg", language = "en"), listOf(SeriesEntry("/chapter/2", "Ch.2"), SeriesEntry("/chapter/1", "Ch.1")))
        series.setFollowed(id, true)
        series.setReadingMode(id, GalleryReadingMode.VERTICAL)
        series.recordProgress(id, "/chapter/1", position = 18, length = 18)
        series.recordProgress(id, "/chapter/2", position = 3, length = 20)
        // Only opened, never read or followed: not the user's, so not in a backup.
        series.store(Series(SeriesId(id.source, "/title/2"), "Glanced at"))
        val snapshot = backups.snapshot(includeRecents = false)
        assertEquals(listOf(id), snapshot.series.map { it.series.series.id })

        val restoredDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java).allowMainThreadQueries().build()
        val restoredAt = 60L
        val restored = RoomSeriesRepository(restoredDatabase, clock = { restoredAt })
        RoomProfileBackupStore(restoredDatabase).merge(snapshot)
        assertEquals(series.series(id), restored.series(id))
        assertEquals(series.entries(id), restored.entries(id))
        assertEquals(listOf("Title" to 1), restored.observeFollowed().first().map { it.series.title to it.unreadCount })

        // On a device that already has the series, unfollowed and further along, the restore only adds.
        restored.setFollowed(id, false)
        restored.setReadingMode(id, GalleryReadingMode.RIGHT_TO_LEFT)
        restored.recordProgress(id, "/chapter/2", position = 20, length = 20)
        RoomProfileBackupStore(restoredDatabase).merge(snapshot)
        val kept = restored.series(id)!!
        assertTrue(kept.followed)
        // Followed again by the restore, since the backup's follow; read last on the device, after the backup's read.
        assertEquals(30L, kept.followedAtEpochMs)
        assertEquals(restoredAt, restored.entries(id).single { it.entry.address == "/chapter/2" }.lastReadAtEpochMs)
        assertEquals(GalleryReadingMode.RIGHT_TO_LEFT, kept.readingMode)
        assertTrue(restored.entries(id).single { it.entry.address == "/chapter/2" }.read)
        restoredDatabase.close()
    }

    @Test fun `a restore onto a series the device already opened merges progress and lists nothing the device lacks`() = runTest {
        val id = SeriesId(SourceKey.parseOrThrow("custom_mihon_7"), "/title/1")
        val series = RoomSeriesRepository(database, clock = { 30L })
        val title = Series(id, "Title", language = "en")
        series.store(title, listOf(SeriesEntry("/chapter/2", "Ch.2"), SeriesEntry("/chapter/1", "Ch.1"), SeriesEntry("/chapter/0", "Ch.0")))
        series.setFollowed(id, true)
        series.recordProgress(id, "/chapter/0", position = 9, length = 9)
        series.recordProgress(id, "/chapter/1", position = 18, length = 18)
        series.recordProgress(id, "/chapter/2", position = 3, length = 20)
        val snapshot = backups.snapshot(includeRecents = false)

        // A new device where the series was opened before the restore: the source's list today, nothing read.
        val deviceDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java).allowMainThreadQueries().build()
        val device = RoomSeriesRepository(deviceDatabase, clock = { 40L })
        device.store(title, listOf(SeriesEntry("/chapter/3", "Ch.3"), SeriesEntry("/chapter/2", "Ch.2"), SeriesEntry("/chapter/1", "Ch.1")))
        RoomProfileBackupStore(deviceDatabase).merge(snapshot)

        assertEquals("/chapter/2", device.series(id)!!.lastOpenedEntry)
        val merged = device.entries(id).associateBy { it.entry.address }
        assertEquals(setOf("/chapter/3", "/chapter/2", "/chapter/1"), merged.keys)
        assertTrue(merged.getValue("/chapter/1").read)
        assertEquals(3L to 20L, merged.getValue("/chapter/2").let { it.position to it.length })
        // Chapter 0 keeps its progress but is not listed, so the unread count is the device's list: chapters 2 and 3.
        val dropped = deviceDatabase.seriesDao().allEntries(id.source.id, id.address).single { it.address == "/chapter/0" }
        assertTrue(dropped.read && dropped.sourceOrder == NOT_LISTED)
        assertEquals(listOf(2), device.observeFollowed().first().map { it.unreadCount })

        // Further along on the device than in the backup: a second restore takes nothing back.
        device.recordProgress(id, "/chapter/2", position = 7, length = 20)
        device.recordProgress(id, "/chapter/3", position = 2, length = 9)
        RoomProfileBackupStore(deviceDatabase).merge(snapshot)
        assertEquals("/chapter/3", device.series(id)!!.lastOpenedEntry)
        assertEquals(7, device.entries(id).single { it.entry.address == "/chapter/2" }.position)

        // The page count goes with the position that wins: page 8 of 10 here and page 3 of 20 in the backup stay 8 of 10,
        // and page 2 of 10 here becomes the backup's 3 of 20, never "3 of 10".
        suspend fun progress() = device.entries(id).single { it.entry.address == "/chapter/2" }.let { it.position to it.length }
        deviceDatabase.seriesDao().mergeRestoredEntry(id.source.id, id.address, "/chapter/2", false, 8L, 10L, null)
        RoomProfileBackupStore(deviceDatabase).merge(snapshot)
        assertEquals(8L to 10L, progress())
        deviceDatabase.openHelper.writableDatabase.execSQL("UPDATE series_entries SET last_page = 2, page_count = 10 WHERE address = '/chapter/2'")
        RoomProfileBackupStore(deviceDatabase).merge(snapshot)
        assertEquals(3L to 20L, progress())
        deviceDatabase.close()
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

    @Test fun `a restore that trims Recents deletes posts only the trimmed rows held`() = runTest {
        val posts = (0..DEFAULT_RECENT_WATCHED_LIMIT).map { index -> post("watched-$index") }
        val watched = posts.mapIndexed { index, post ->
            RecentPostEntry(post, viewedAtEpochMs = index + 1L, ViewerStreamSource.SEARCH, null)
        }
        content.ensureCodex("kept", "Kept")
        content.addItems("kept", listOf(posts[1]))

        backups.merge(BackupLibrary(emptyList(), emptyList(), posts, emptyList(), watched.asReversed()))

        assertEquals(DEFAULT_RECENT_WATCHED_LIMIT, recents.observeWatchedPosts().first().size)
        assertEquals(null, content.getPost(posts[0].id))
        assertEquals(posts[1].id, content.getPost(posts[1].id)?.id)
        assertEquals(posts.last().id, content.getPost(posts.last().id)?.id)
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
