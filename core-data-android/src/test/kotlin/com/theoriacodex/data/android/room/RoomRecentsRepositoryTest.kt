package com.theoriacodex.data.android.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomRecentsRepositoryTest {
    private lateinit var database: TheoriaRoomDatabase
    private var now = 100L

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            TheoriaRoomDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun `dedupe caps deterministic ties and origin preservation match Recents contract`() = runTest {
        val repository = RoomRecentsRepository(database, watchedLimit = 2, searchLimit = 2, clock = { now })
        repository.recordWatchedPost(recentPost("b"), ViewerStreamSource.SEARCH, "original")
        repository.recordWatchedPost(recentPost("a"), ViewerStreamSource.CODEX, "codex")
        repository.recordWatchedPost(
            recentPost("b").copy(title = "refreshed"),
            ViewerStreamSource.RECENTS,
            "ignored",
            RecentPostSection.WATCHED,
        )
        repository.recordWatchedPost(recentPost("c"), ViewerStreamSource.FOR_YOU, null)
        repository.recordSearch(recentQuery("one"), " b ")
        repository.recordSearch(recentQuery("two"), "a")
        repository.recordSearch(recentQuery("three"), "c")

        val watched = repository.observeWatchedPosts().first()
        val searches = repository.observeSearches().first()
        assertEquals(listOf("c", "b"), watched.map { it.post.id.sourcePostId })
        assertEquals("refreshed", watched.single { it.post.id.sourcePostId == "b" }.post.title)
        assertEquals(ViewerStreamSource.SEARCH, watched.single { it.post.id.sourcePostId == "b" }.origin)
        assertEquals("original", watched.single { it.post.id.sourcePostId == "b" }.originQueryHash)
        assertEquals(listOf("c", "a"), searches.map { it.queryHash })

        val activity = repository.observeActivity().first()
        assertTrue(activity.take(2).all { it is RecentActivityEntry.Watched })
        assertEquals(
            listOf("c", "b"),
            activity.take(2).map { (it as RecentActivityEntry.Watched).entry.post.id.sourcePostId },
        )
    }

    @Test fun `filtered clears clean only newly orphaned posts`() = runTest {
        val recents = RoomRecentsRepository(database, clock = { now++ })
        val codex = RoomCodexLikesRepository(database, clock = { now++ }, newId = { "codex" })
        val shared = recentPost("shared")
        val recentsOnly = recentPost("recents-only")
        val codexId = codex.createCodex("Saved").codexId
        codex.addItem(codexId, shared)
        recents.recordWatchedPost(shared, ViewerStreamSource.SEARCH, null)
        recents.recordWatchedPost(shared, ViewerStreamSource.CODEX, null)
        recents.recordWatchedPost(recentsOnly, ViewerStreamSource.FOR_YOU, null)

        recents.clearWatchedPosts(RecentPostSection.WATCHED)
        assertNull(codex.getPost(recentsOnly.id))
        assertEquals(shared, codex.getPost(shared.id))
        assertEquals(
            listOf(RecentPostSection.CODEX),
            recents.observeWatchedPosts().first().map { entry -> entry.section },
        )

        recents.clearWatchedPosts(RecentPostSection.CODEX)
        assertEquals(shared, codex.getPost(shared.id))
        codex.deleteCodex(codexId)
        assertNull(codex.getPost(shared.id))
    }

    @Test fun `same post retains independent watched and codex memberships`() = runTest {
        val repository = RoomRecentsRepository(database, clock = { now++ })
        val post = recentPost("shared-membership")

        repository.recordWatchedPost(post, ViewerStreamSource.SEARCH, "search")
        repository.recordWatchedPost(post, ViewerStreamSource.CODEX, "codex")

        val memberships = repository.observeWatchedPosts().first()
        assertEquals(2, memberships.size)
        assertEquals(
            setOf(RecentPostSection.WATCHED, RecentPostSection.CODEX),
            memberships.map { entry -> entry.section }.toSet(),
        )
        assertEquals(1, repository.observeActivity().first().filterIsInstance<RecentActivityEntry.Watched>().size)
        assertEquals(1, database.codexLikesDao().posts().size)
    }

    @Test fun `restore preserves exact rows and keeps newer activity`() = runTest {
        val repository = RoomRecentsRepository(database, clock = { now++ })
        val shared = recentPost("restore-shared")
        val codex = recentPost("restore-codex")
        val query = recentQuery("restore")
        repository.recordWatchedPost(shared, ViewerStreamSource.SEARCH, "search:restore")
        repository.recordWatchedPost(codex, ViewerStreamSource.CODEX, "codex:restore")
        repository.recordSearch(query, "restore-query")
        val watchedSnapshot = repository.observeWatchedPosts().first()
        val searchSnapshot = repository.observeSearches().first()

        repository.clearAll()
        now = 500L
        repository.recordWatchedPost(shared, ViewerStreamSource.FOR_YOU, "newer")
        repository.recordSearch(recentQuery("newer"), "restore-query")
        repository.restoreEntries(watchedSnapshot, searchSnapshot)

        val restoredWatched = repository.observeWatchedPosts().first()
        val restoredSearch = repository.observeSearches().first().single()
        assertEquals(2, restoredWatched.size)
        assertEquals(
            ViewerStreamSource.FOR_YOU,
            restoredWatched.single { it.post.id == shared.id }.origin,
        )
        assertEquals(
            watchedSnapshot.single { it.section == RecentPostSection.CODEX },
            restoredWatched.single { it.section == RecentPostSection.CODEX },
        )
        assertEquals(recentQuery("newer"), restoredSearch.query)
        assertEquals(501L, restoredSearch.searchedAtEpochMs)
    }

    @Test fun `partial Recents snapshots preserve rich shared payload while applying enrichment`() = runTest {
        val recents = RoomRecentsRepository(database, clock = { now++ })
        val codex = RoomCodexLikesRepository(database, clock = { now++ }, newId = { "codex" })
        val rich = recentPost("shared-rich").copy(
            preview = ImageRef(
                "https://example.com/preview.jpg",
                "/cached/preview.jpg",
                "image/jpeg",
                progressiveUrls = listOf("https://example.com/progressive.jpg"),
            ),
            full = ImageRef("https://example.com/full.jpg", null, "image/jpeg"),
            media = listOf(
                ImageRef("https://example.com/page-1.jpg", null, "image/jpeg"),
                ImageRef("https://example.com/page-2.jpg", null, "image/jpeg"),
            ),
            canonicalTags = listOf("rich", "shared"),
            rawTags = listOf("rich_raw", "shared_raw"),
            durationMs = 1_000L,
            title = "original title",
        )
        codex.addItem(codex.createCodex("Saved").codexId, rich)

        recents.recordWatchedPost(
            recentPost("shared-rich").copy(
                preview = ImageRef(null, null, null),
                full = null,
                media = emptyList(),
                canonicalTags = emptyList(),
                rawTags = emptyList(),
                authorName = null,
                title = "refreshed title",
                durationMs = 2_000L,
            ),
            ViewerStreamSource.SEARCH,
            null,
        )

        val stored = requireNotNull(codex.getPost(rich.id))
        assertEquals(rich.preview, stored.preview)
        assertEquals(rich.full, stored.full)
        assertEquals(rich.media, stored.media)
        assertEquals(rich.canonicalTags, stored.canonicalTags)
        assertEquals(rich.rawTags, stored.rawTags)
        assertEquals("artist", stored.authorName)
        assertEquals("refreshed title", stored.title)
        assertEquals(2_000L, stored.durationMs)
    }

    @Test fun `failed watched transaction rolls back its shared post`() = runTest {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_recent BEFORE INSERT ON recent_watched BEGIN SELECT RAISE(ABORT, 'no'); END"
        )
        val repository = RoomRecentsRepository(database)
        val post = recentPost("rollback")

        assertTrue(runCatching {
            repository.recordWatchedPost(post, ViewerStreamSource.SEARCH, null)
        }.isFailure)
        assertNull(database.codexLikesDao().post(post.id.source.name, post.id.sourcePostId))
        assertTrue(repository.observeWatchedPosts().first().isEmpty())
    }

    @Test fun `versioned query payload restarts from the same database`() = runTest {
        val repository = RoomRecentsRepository(database, clock = { 9L })
        val query = recentQuery("artist").copy(
            includeTerms = listOf(SearchTerm("name", sourceNamespace = "pixiv")),
        )
        repository.recordSearch(query, "hash")

        val reconstructed = RoomRecentsRepository(database)
        assertEquals(query, reconstructed.observeSearches().first().single().query)
        assertTrue(database.recentsDao().searches().single().queryPayloadJson.contains("schemaVersion"))
    }

    @Test fun `unfiltered watched search and combined clears remain independent`() = runTest {
        val repository = RoomRecentsRepository(database, clock = { now++ })
        repository.recordWatchedPost(recentPost("watched"), ViewerStreamSource.SEARCH, null)
        repository.recordSearch(recentQuery("search"), "search")

        repository.clearWatchedPosts()
        assertTrue(repository.observeWatchedPosts().first().isEmpty())
        assertEquals(listOf("search"), repository.observeSearches().first().map { it.queryHash })

        repository.recordWatchedPost(recentPost("watched-2"), ViewerStreamSource.CODEX, null)
        repository.clearSearches()
        assertTrue(repository.observeSearches().first().isEmpty())
        assertEquals(listOf("watched-2"), repository.observeWatchedPosts().first().map { it.post.id.sourcePostId })

        repository.recordSearch(recentQuery("again"), "again")
        repository.clearAll()
        assertTrue(repository.observeActivity().first().isEmpty())
        assertNull(database.codexLikesDao().post(SourceKey.PIXIV.name, "watched-2"))
    }
}

private fun recentQuery(tag: String) = Query(
    mode = QueryMode.Source(SourceKey.PIXIV),
    includeTerms = listOf(SearchTerm(tag)),
    excludeTerms = emptyList(),
    sort = SortMode.NEWEST,
    dateRange = null,
    minScore = null,
)

private fun recentPost(id: String) = Post(
    id = PostId(SourceKey.PIXIV, id),
    preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
    full = null,
    pageUrl = "https://example.com/$id",
    width = 100,
    height = 100,
    canonicalTags = listOf("tag"),
    rawTags = listOf("tag"),
    authorName = "artist",
    createdAtEpochMs = 1L,
    title = "post-$id",
)
