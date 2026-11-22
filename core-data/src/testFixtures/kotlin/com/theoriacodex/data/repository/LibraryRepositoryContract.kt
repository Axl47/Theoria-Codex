package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex and Recents behavior that every backing shares. Each test asks for fresh repositories: the in-memory
 * fakes run it in core-data and Room runs it in core-data-android, so the fakes cannot drift from the durable store.
 */
abstract class LibraryRepositoryContract {
    protected abstract fun createCodexRepository(): CodexRepository

    /** The transactions over [repository]'s data. */
    protected abstract fun transactionsFor(repository: CodexRepository): CodexLikesTransactions

    /** The Likes [transactions] write, read back through the repository interface. */
    protected abstract fun likesFor(transactions: CodexLikesTransactions): LikesRepository

    /** A Recents repository keeping at most [watchedLimit] watched rows and [searchLimit] searches, the app's limits by default. */
    protected abstract fun createRecentsRepository(
        clock: () -> Long,
        watchedLimit: Int = 200,
        searchLimit: Int = 100,
    ): RecentsRepository

    @Test
    fun `codex membership deduplicates while explicit hydration updates the shared post`() = runTest {
        val repository = createCodexRepository()
        val codex = repository.createCodex("Contract")
        val sparse = repositoryTestPost(id = "post-1").copy(full = null, title = null)

        repository.addItem(codex.codexId, sparse)
        repository.addItem(codex.codexId, sparse)

        assertEquals(1, repository.observeCodexItems(codex.codexId).first().size)
        assertEquals(listOf(sparse), repository.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED).first())

        val hydrated = sparse.copy(
            title = "Hydrated",
            full = ImageRef(
                url = "https://example.com/full/post-1.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
        )
        repository.updatePost(hydrated)

        assertEquals(hydrated, repository.getPost(hydrated.id))
        assertEquals(listOf(hydrated), repository.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED).first())
    }

    @Test
    fun `codex names order and source sort follow one policy`() = runTest {
        val repository = createCodexRepository()
        val first = repository.createCodex(" Favorites ")
        val second = repository.createCodex("favorites")
        val third = repository.createCodex("  ")
        repository.addItem(first.codexId, repositoryTestPost(id = "pixiv", source = SourceKey.PIXIV))
        repository.addItem(first.codexId, repositoryTestPost(id = "ai", source = SourceKey.AIBOORU))

        transactionsFor(repository).reorderCodices(listOf(third.codexId, first.codexId, second.codexId))

        assertEquals("Favorites", first.name)
        assertEquals("favorites 2", second.name)
        assertEquals("Codex", third.name)
        assertEquals(
            listOf(third.codexId, first.codexId, second.codexId),
            repository.observeCodices().first().map { codex -> codex.codexId },
        )
        assertEquals(
            listOf(SourceKey.AIBOORU, SourceKey.PIXIV),
            repository.observeCodexPosts(first.codexId, CodexSortMode.BY_SOURCE)
                .first()
                .map { post -> post.id.source },
        )
    }

    @Test
    fun `codex automatic tags are source aware idempotent and reversible`() = runTest {
        val repository = createCodexRepository()
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
        repository.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.PIXIV, "blue sky"),
            enabled = true,
        )

        assertEquals(
            listOf(
                CodexAutomaticTag(SourceKey.PIXIV, "blue sky"),
                CodexAutomaticTag(SourceKey.GELBOORU, "blue sky"),
            ),
            repository.observeCodex(codex.codexId).first()?.automaticTags,
        )

        repository.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue_sky"),
            enabled = false,
        )
        assertEquals(
            listOf(CodexAutomaticTag(SourceKey.PIXIV, "blue sky")),
            repository.observeCodex(codex.codexId).first()?.automaticTags,
        )
    }

    @Test
    fun `recents keeps one newest watched and search entry per identity`() = runTest {
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        val post = repositoryTestPost(id = "post-1")
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        repository.recordWatchedPost(post, ViewerStreamSource.SEARCH, "old-query")
        repository.recordWatchedPost(
            post,
            ViewerStreamSource.FOR_YOU,
            "new-query",
            RecentPostSection.WATCHED,
        )
        repository.recordSearch(query, " query-hash ")
        repository.recordSearch(
            query.withIncludeTermsAsRequired(listOf(SearchTerm("updated"))),
            "query-hash",
        )

        val watched = repository.observeWatchedPosts().first()
        val searches = repository.observeSearches().first()
        assertEquals(1, watched.size)
        assertEquals(ViewerStreamSource.FOR_YOU, watched.single().origin)
        assertEquals("new-query", watched.single().originQueryHash)
        assertEquals(1, searches.size)
        assertEquals("query-hash", searches.single().queryHash)
        assertEquals(listOf("updated"), searches.single().query.includeTags)
    }

    @Test
    fun `recents restore preserves exact cleared entries without replacing newer activity`() = runTest {
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        val watchedPost = repositoryTestPost(id = "watched")
        val codexPost = repositoryTestPost(id = "codex")
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("restore"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        repository.recordWatchedPost(watchedPost, ViewerStreamSource.SEARCH, "search:restore")
        repository.recordWatchedPost(codexPost, ViewerStreamSource.CODEX, "codex:restore")
        repository.recordSearch(query, "restore-query")
        val watchedSnapshot = repository.observeWatchedPosts().first()
        val searchSnapshot = repository.observeSearches().first()

        repository.clearAll()
        now = 500L
        repository.recordWatchedPost(
            watchedPost,
            ViewerStreamSource.FOR_YOU,
            "newer",
            RecentPostSection.WATCHED,
        )
        repository.recordSearch(query.withIncludeTermsAsRequired(listOf(SearchTerm("newer"))), "restore-query")
        repository.restoreEntries(watchedSnapshot, searchSnapshot)

        val restoredWatched = repository.observeWatchedPosts().first()
        val restoredSearch = repository.observeSearches().first().single()
        assertEquals(2, restoredWatched.size)
        assertEquals(
            ViewerStreamSource.FOR_YOU,
            restoredWatched.single { it.post.id == watchedPost.id }.origin,
        )
        assertEquals(
            watchedSnapshot.single { it.section == RecentPostSection.CODEX },
            restoredWatched.single { it.section == RecentPostSection.CODEX },
        )
        assertEquals(listOf("newer"), restoredSearch.query.includeTags)
        assertEquals(501L, restoredSearch.searchedAtEpochMs)
    }

    @Test
    fun `recents removes one entry and restores it in place`() = runTest {
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        val post = repositoryTestPost(id = "single")
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("single"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        repository.recordWatchedPost(post, ViewerStreamSource.SEARCH, "q", RecentPostSection.WATCHED)
        repository.recordWatchedPost(post, ViewerStreamSource.CODEX, "c", RecentPostSection.CODEX)
        repository.recordSearch(query, "kept")
        repository.recordSearch(query.withIncludeTermsAsRequired(listOf(SearchTerm("gone"))), "gone")
        val watched = repository.observeWatchedPosts().first().single { it.section == RecentPostSection.WATCHED }
        val search = repository.observeSearches().first().single { it.queryHash == "gone" }

        repository.removeWatchedPost(post.id, RecentPostSection.WATCHED)
        repository.removeSearch("gone")

        assertEquals(listOf(RecentPostSection.CODEX), repository.observeWatchedPosts().first().map { it.section })
        assertEquals(listOf("kept"), repository.observeSearches().first().map { it.queryHash })

        repository.restoreEntries(listOf(watched), listOf(search))

        assertEquals(watched, repository.observeWatchedPosts().first().single { it.section == RecentPostSection.WATCHED })
        assertEquals(listOf("gone", "kept"), repository.observeSearches().first().map { it.queryHash })
    }

    @Test
    fun `recents preserves a posts section when it is reopened from history`() = runTest {
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        val codexPost = repositoryTestPost(id = "codex-post")
        val watchedPost = repositoryTestPost(id = "watched-post")

        repository.recordWatchedPost(codexPost, ViewerStreamSource.CODEX, "codex:art")
        repository.recordWatchedPost(watchedPost, ViewerStreamSource.SEARCH, "search:art")
        repository.recordWatchedPost(
            codexPost,
            ViewerStreamSource.RECENTS,
            "recents:codex",
            RecentPostSection.CODEX,
        )
        repository.recordWatchedPost(
            watchedPost,
            ViewerStreamSource.RECENTS,
            "recents:watched",
            RecentPostSection.WATCHED,
        )

        val watched = repository.observeWatchedPosts().first()
        assertEquals(ViewerStreamSource.SEARCH, watched.first { it.post.id == watchedPost.id }.origin)
        assertEquals(ViewerStreamSource.CODEX, watched.first { it.post.id == codexPost.id }.origin)
        assertEquals("codex:art", watched.first { it.post.id == codexPost.id }.originQueryHash)
    }

    @Test
    fun `recents keeps independent watched and codex memberships for one post`() = runTest {
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        val post = repositoryTestPost(id = "shared-post")

        repository.recordWatchedPost(post, ViewerStreamSource.SEARCH, "search:art")
        repository.recordWatchedPost(post, ViewerStreamSource.CODEX, "codex:art")

        val watched = repository.observeWatchedPosts().first()
        assertEquals(2, watched.size)
        assertEquals(
            setOf(RecentPostSection.WATCHED, RecentPostSection.CODEX),
            watched.map { entry -> entry.section }.toSet(),
        )
        assertEquals(1, repository.activity().filterIsInstance<RecentActivityEntry.Watched>().size)
    }

    @Test
    fun `recents clears codex and external watched sections independently`() = runTest {
        var now = 100L
        val repository = createRecentsRepository(clock = { now++ })
        repository.recordWatchedPost(repositoryTestPost(id = "codex-post"), ViewerStreamSource.CODEX, null)
        repository.recordWatchedPost(repositoryTestPost(id = "watched-post"), ViewerStreamSource.SEARCH, null)

        repository.clearWatchedPosts(RecentPostSection.WATCHED)
        assertEquals(listOf(RecentPostSection.CODEX), repository.observeWatchedPosts().first().map { it.section })

        repository.clearWatchedPosts(RecentPostSection.CODEX)
        assertTrue(repository.observeWatchedPosts().first().isEmpty())
    }

    @Test
    fun `a Codex is deleted only while it is empty`() = runTest {
        val repo = createCodexRepository()
        val held = repo.createCodex("Held")
        val empty = repo.createCodex("Empty")
        repo.addItem(held.codexId, repositoryTestPost("1"))

        assertEquals(false, repo.deleteCodexIfEmpty(held.codexId))
        assertEquals(true, repo.deleteCodexIfEmpty(empty.codexId))
        assertEquals(listOf("Held"), repo.observeCodices().first().map { it.name })
    }

    @Test
    fun `codex repository creates and tracks items`() = runTest {
        val repo = createCodexRepository()
        val codex = repo.createCodex("Favorites")
        repo.addItem(codex.codexId, repositoryTestPost("1"))

        val codices = repo.observeCodices().first()
        val items = repo.observeCodexItems(codex.codexId).first()

        assertEquals(1, codices.size)
        assertEquals("Favorites", codices.first().name)
        assertEquals(1, items.size)
    }

    @Test
    fun `codex repository deduplicates and sorts post hydration`() = runTest {
        val repo = createCodexRepository()
        val codex = repo.createCodex("Favorites")
        val pixiv = repositoryTestPost(id = "1", source = SourceKey.PIXIV)
        val gelbooru = repositoryTestPost(id = "2", source = SourceKey.GELBOORU)

        repo.addItem(codex.codexId, pixiv)
        repo.addItem(codex.codexId, gelbooru)
        repo.addItem(codex.codexId, pixiv)

        val items = repo.observeCodexItems(codex.codexId).first()
        val bySource = repo.observeCodexPosts(codex.codexId, CodexSortMode.BY_SOURCE).first()

        assertEquals(2, items.size)
        assertEquals(SourceKey.GELBOORU, bySource.first().id.source)
        assertNotNull(repo.getPost(pixiv.id))
    }

    @Test
    fun `codex repository ensures stable system codex`() = runTest {
        val repo = createCodexRepository()

        val first = repo.ensureCodex(codexId = "system_likes_codex", name = "Likes")
        val second = repo.ensureCodex(codexId = "system_likes_codex", name = "Likes")

        assertEquals(first.codexId, second.codexId)
        assertEquals("Likes", second.name)
        assertEquals(1, repo.observeCodices().first().size)
    }

    @Test
    fun `codex repository appends numeric suffix for duplicate names`() = runTest {
        val repo = createCodexRepository()

        val first = repo.createCodex("Favorites")
        val second = repo.createCodex("Favorites")
        val third = repo.ensureCodex(codexId = "manual", name = "Favorites")

        repo.renameCodex(second.codexId, "Favorites")

        val names = repo.observeCodices().first().associateBy({ it.codexId }, { it.name })
        assertEquals("Favorites", names[first.codexId])
        assertEquals("Favorites 2", names[second.codexId])
        assertEquals("Favorites 3", names[third.codexId])
    }

    @Test
    fun `recents repository dedupes watched posts and searches by newest activity`() = runTest {
        var now = 1_000L
        val repo = createRecentsRepository(
            clock = { now },
            watchedLimit = 3,
            searchLimit = 2,
        )
        val firstPost = repositoryTestPost("1", source = SourceKey.PIXIV)
        val secondPost = repositoryTestPost("2", source = SourceKey.GELBOORU)
        val thirdPost = repositoryTestPost("3", source = SourceKey.AIBOORU)
        val firstQuery = sampleQuery(includeTags = listOf("landscape"))
        val secondQuery = sampleQuery(includeTags = listOf("portrait"))
        val thirdQuery = sampleQuery(includeTags = listOf("city"))

        repo.recordWatchedPost(firstPost, ViewerStreamSource.SEARCH, "hash-1")
        now += 1
        repo.recordWatchedPost(secondPost, ViewerStreamSource.FOR_YOU, "hash-2")
        now += 1
        repo.recordWatchedPost(firstPost.copy(title = "updated"), ViewerStreamSource.CODEX, "hash-3")
        now += 1
        repo.recordWatchedPost(thirdPost, ViewerStreamSource.CREATOR_PROFILE, "hash-4")
        now += 1
        repo.recordSearch(firstQuery, "query-1")
        now += 1
        repo.recordSearch(secondQuery, "query-2")
        now += 1
        repo.recordSearch(
            firstQuery.copy(excludeTerms = listOf(SearchTerm(value = "sketch"))),
            "query-1",
        )
        now += 1
        repo.recordSearch(thirdQuery, "query-3")

        val watched = repo.observeWatchedPosts().first()
        val searches = repo.observeSearches().first()
        val activity = mergeRecentActivity(watched, searches)

        assertEquals(listOf(thirdPost.id, firstPost.id, secondPost.id), watched.map { entry -> entry.post.id })
        assertEquals("updated", watched[1].post.title)
        assertEquals(ViewerStreamSource.CODEX, watched[1].origin)
        assertEquals(RecentPostSection.CODEX, watched[1].section)
        assertEquals(listOf("query-3", "query-1"), searches.map { entry -> entry.queryHash })
        assertEquals(listOf("sketch"), searches[1].query.excludeTags)
        assertEquals(searches.first().searchedAtEpochMs, activity.first().occurredAtEpochMs)
    }

    @Test
    fun `recents repository clears watched and searches independently`() = runTest {
        val repo = createRecentsRepository(clock = { 1L })

        repo.recordWatchedPost(repositoryTestPost("1"), ViewerStreamSource.SEARCH, "hash")
        repo.recordSearch(sampleQuery(), "query")
        repo.clearWatchedPosts(RecentPostSection.WATCHED)

        assertTrue(repo.observeWatchedPosts().first().isEmpty())
        assertEquals(1, repo.observeSearches().first().size)

        repo.clearSearches()

        assertTrue(repo.observeSearches().first().isEmpty())

        repo.recordWatchedPost(repositoryTestPost("2"), ViewerStreamSource.CODEX, null)
        repo.recordSearch(sampleQuery(includeTags = listOf("city")), "query-2")
        repo.clearAll()

        assertTrue(repo.observeWatchedPosts().first().isEmpty())
        assertTrue(repo.observeSearches().first().isEmpty())
    }

    @Test
    fun `watched media progress is monotonic and section scoped`() = runTest {
        val repo = createRecentsRepository(clock = { 1L })
        val post = repositoryTestPost("progress")

        repo.recordWatchedMediaProgress(
            post,
            origin = ViewerStreamSource.SEARCH,
            originQueryHash = null,
            section = RecentPostSection.WATCHED,
            viewedMediaNumber = 50,
        )
        repo.recordWatchedPost(
            post,
            ViewerStreamSource.SEARCH,
            null,
            viewedMediaNumber = 1,
        )
        repo.recordWatchedPost(
            post,
            ViewerStreamSource.CODEX,
            null,
            viewedMediaNumber = 3,
        )
        repo.recordWatchedMediaProgress(
            post,
            ViewerStreamSource.SEARCH,
            null,
            RecentPostSection.WATCHED,
            30,
        )

        val entries = repo.observeWatchedPosts().first()
        assertEquals(
            50,
            entries.single { it.section == RecentPostSection.WATCHED }.maxViewedMediaNumber,
        )
        assertEquals(
            3,
            entries.single { it.section == RecentPostSection.CODEX }.maxViewedMediaNumber,
        )
    }

    @Test
    fun `removing items drops memberships and releases posts no collection holds`() = runTest {
        val repository = createCodexRepository()
        val first = repository.createCodex("First")
        val second = repository.createCodex("Second")
        val solo = repositoryTestPost("solo")
        val shared = repositoryTestPost("shared")
        repository.addItems(first.codexId, listOf(solo, shared))
        repository.addItem(second.codexId, shared)

        repository.removeItems(first.codexId, emptySet())
        repository.removeItems("missing", setOf(shared.id))
        assertEquals(2, repository.observeCodexItems(first.codexId).first().size)
        repository.removeItems(first.codexId, setOf(solo.id, shared.id, repositoryTestPost("absent").id))

        assertTrue(repository.observeCodexItems(first.codexId).first().isEmpty())
        assertEquals(listOf(shared.id), repository.observeCodexItems(second.codexId).first().map { it.postId })
        assertNull(repository.getPost(solo.id))
        assertEquals(shared, repository.getPost(shared.id))
        assertEquals(setOf(shared.id), repository.observeSavedPostIds(setOf(first.codexId, second.codexId)).first())
    }

    @Test
    fun `a deleted codex restores in place with its tags, items and posts, and restoring again changes nothing`() = runTest {
        val repository = createCodexRepository()
        val first = repository.createCodex("First")
        val middle = repository.createCodex("Middle")
        val last = repository.createCodex("Last")
        repository.setAutomaticTag(middle.codexId, CodexAutomaticTag(SourceKey.PIXIV, "landscape"), enabled = true)
        val post = repositoryTestPost("kept")
        repository.addItem(middle.codexId, post)

        val snapshot = checkNotNull(repository.deleteCodexForUndo(middle.codexId))
        assertEquals(listOf(first.codexId, last.codexId), repository.observeCodices().first().map { it.codexId })
        assertNull(repository.getPost(post.id))
        assertNull(repository.deleteCodexForUndo(middle.codexId))

        repository.restoreDeletedCodex(snapshot)
        repository.restoreDeletedCodex(snapshot)

        assertEquals(
            listOf(first.codexId, middle.codexId, last.codexId),
            repository.observeCodices().first().map { it.codexId },
        )
        assertEquals(snapshot.codex, repository.observeCodex(middle.codexId).first())
        assertEquals(snapshot.items, repository.observeCodexItems(middle.codexId).first())
        assertEquals(listOf(post), repository.observeCodexPosts(middle.codexId, CodexSortMode.NEWEST_SAVED).first())
    }

    @Test
    fun `a deleted codex restores under a free name when another took its name meanwhile`() = runTest {
        val repository = createCodexRepository()
        val original = repository.createCodex("Trips")
        val snapshot = checkNotNull(repository.deleteCodexForUndo(original.codexId))
        val replacement = repository.createCodex("Trips")

        repository.restoreDeletedCodex(snapshot)

        val names = repository.observeCodices().first().associate { codex -> codex.codexId to codex.name }
        assertEquals("Trips", names[replacement.codexId])
        assertEquals("Trips 2", names[original.codexId])
    }

    @Test
    fun `a like is profile scoped, normalizes its tags and moves its system codex membership with it`() = runTest {
        val repository = createCodexRepository()
        val transactions = transactionsFor(repository)
        val likes = likesFor(transactions)
        val post = repositoryTestPost("liked")
        suspend fun toggle(profileId: String, tags: List<String> = emptyList()) =
            transactions.toggleLikeAndSyncSystemCodex(profileId, "system_likes_codex", "Likes", post, tags)

        assertEquals(CodexLikeSyncResult(nowLiked = false, membershipChanged = false), toggle("  "))
        assertTrue(repository.observeCodices().first().isEmpty())

        assertEquals(CodexLikeSyncResult(nowLiked = true, membershipChanged = true), toggle(" main ", listOf(" Tag ", "tag", "Other")))
        assertEquals(listOf("Tag", "Other"), likes.observeLikes("main").first().single().tags)
        assertEquals(setOf(post.id), likes.observeLikedPostIds("main").first())
        assertTrue(likes.observeLikedPostIds("other").first().isEmpty())
        assertEquals(listOf(post), repository.observeCodexPosts("system_likes_codex", CodexSortMode.NEWEST_SAVED).first())

        assertEquals(CodexLikeSyncResult(nowLiked = false, membershipChanged = true), toggle("main"))
        assertTrue(likes.observeLikes("main").first().isEmpty())
        assertTrue(repository.observeCodexItems("system_likes_codex").first().isEmpty())
        assertNull(repository.getPost(post.id))
    }

    @Test
    fun `clearing likes removes only liked memberships of that profile and can be undone`() = runTest {
        val repository = createCodexRepository()
        val transactions = transactionsFor(repository)
        val likes = likesFor(transactions)
        val liked = repositoryTestPost("liked")
        val saved = repositoryTestPost("saved")
        val others = repositoryTestPost("others")
        transactions.toggleLikeAndSyncSystemCodex("main", "system_likes_codex", "Likes", liked, emptyList())
        transactions.toggleLikeAndSyncSystemCodex("other", "system_likes_other", "Likes other", others, emptyList())
        repository.addItem("system_likes_codex", saved)

        val cleared = transactions.clearLikesAndLikedMemberships("main", "system_likes_codex")

        assertEquals(1, cleared.clearedLikes)
        assertEquals(1, cleared.removedMemberships)
        assertTrue(likes.observeLikedPostIds("main").first().isEmpty())
        assertEquals(setOf(others.id), likes.observeLikedPostIds("other").first())
        assertEquals(listOf(saved.id), repository.observeCodexItems("system_likes_codex").first().map { it.postId })
        assertNull(repository.getPost(liked.id))

        transactions.restoreClearedLikes(checkNotNull(cleared.undo))

        assertEquals(setOf(liked.id), likes.observeLikedPostIds("main").first())
        assertEquals(
            setOf(liked.id, saved.id),
            repository.observeCodexItems("system_likes_codex").first().mapTo(mutableSetOf()) { it.postId },
        )
        assertEquals(liked, repository.getPost(liked.id))
        assertNull(transactions.clearLikesAndLikedMemberships("nobody", "system_likes_codex").undo)
    }

    private fun sampleQuery(includeTags: List<String> = listOf("landscape")): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = includeTags,
            excludeTags = emptyList(),
            sort = SortMode.TOP,
            dateRange = DateRange(fromEpochMs = 100L, toEpochMs = 200L),
            minScore = 20,
        )
    }
}
