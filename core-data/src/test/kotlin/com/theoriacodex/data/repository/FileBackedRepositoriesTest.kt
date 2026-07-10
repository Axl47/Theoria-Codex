package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedRepositoriesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `codex repository persists codex and items across instances`() = runTest {
        val dir = tempDir("codex-store-")
        val first = FileBackedCodexRepository(dir)
        val created = first.createCodex("Saved")
        first.addItem(created.codexId, samplePost("1", localPath = null, source = SourceKey.PIXIV))
        first.addItem(created.codexId, samplePost("2", localPath = null, source = SourceKey.AIBOORU))

        val second = FileBackedCodexRepository(dir)
        val bySource = second.observeCodexPosts(created.codexId, CodexSortMode.BY_SOURCE).first()

        assertEquals(1, second.observeCodices().first().size)
        assertEquals(2, second.observeCodexItems(created.codexId).first().size)
        assertEquals(SourceKey.AIBOORU, bySource.first().id.source)
        assertNotNull(second.getPost(PostId(SourceKey.PIXIV, "1")))
    }

    @Test
    fun `codex repository persists typed taxonomy and creator profiles across instances`() = runTest {
        val dir = tempDir("codex-creator-profile-")
        val first = FileBackedCodexRepository(dir)
        val created = first.createCodex("Saved")
        val primaryCreator = CreatorProfile(
            source = SourceKey.PIXIV,
            displayName = "artist",
            profileId = "201823",
            profileUrl = "https://www.pixiv.net/en/users/201823",
            uploadsQuery = "201823",
        )
        val collaborator = CreatorProfile(
            source = SourceKey.PIXIV,
            displayName = "collaborator",
            profileId = "441002",
            profileUrl = "https://www.pixiv.net/en/users/441002",
            uploadsQuery = "441002",
        )
        val post = samplePost("1", localPath = null, source = SourceKey.PIXIV).copy(
            creatorProfile = primaryCreator,
            creatorProfiles = listOf(primaryCreator, collaborator),
            taxonomy = listOf(
                PostTaxonomyTerm(
                    value = "artist",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                ),
                PostTaxonomyTerm(
                    value = "original",
                    facet = SearchFacet.SERIES,
                    sourceNamespace = "series",
                ),
            ),
        )

        first.addItem(created.codexId, post)

        val second = FileBackedCodexRepository(dir)
        val loaded = second.getPost(post.id)

        assertEquals("artist", loaded?.creatorProfile?.displayName)
        assertEquals("201823", loaded?.creatorProfile?.profileId)
        assertEquals("201823", loaded?.creatorProfile?.uploadsQuery)
        assertEquals(listOf("artist", "collaborator"), loaded?.creatorProfiles?.map(CreatorProfile::displayName))
        assertEquals(post.taxonomy, loaded?.taxonomy)
    }

    @Test
    fun `codex repository persists media metadata across instances`() = runTest {
        val dir = tempDir("codex-progressive-urls-")
        val first = FileBackedCodexRepository(dir)
        val created = first.createCodex("Saved")
        val progressiveUrls = listOf(
            "https://gelbooru.com/sample/1.jpg",
            "https://gelbooru.com/alternate/1.jpg",
        )
        val previewProgressiveUrls = listOf(
            "https://gelbooru.com/thumb-small/1.jpg",
            "https://gelbooru.com/thumb-large/1.jpg",
        )
        val media = ImageRef(
            url = "https://gelbooru.com/full/1.jpg",
            localPath = null,
            mime = "image/jpeg",
            progressiveUrls = progressiveUrls,
            isAnimated = true,
        )
        val post = samplePost("1", localPath = null, source = SourceKey.GELBOORU).copy(
            preview = ImageRef(
                url = "https://gelbooru.com/preview/1.jpg",
                localPath = null,
                mime = "image/jpeg",
                progressiveUrls = previewProgressiveUrls,
                isAnimated = true,
            ),
            full = media,
            media = listOf(media),
            durationMs = 12_345L,
            mediaCount = 4,
        )

        first.addItem(created.codexId, post)

        val second = FileBackedCodexRepository(dir)
        val loaded = second.getPost(post.id)

        assertEquals(previewProgressiveUrls, loaded?.preview?.progressiveUrls)
        assertEquals(progressiveUrls, loaded?.full?.progressiveUrls)
        assertEquals(progressiveUrls, loaded?.media?.single()?.progressiveUrls)
        assertEquals(true, loaded?.preview?.isAnimated)
        assertEquals(true, loaded?.full?.isAnimated)
        assertEquals(true, loaded?.media?.single()?.isAnimated)
        assertEquals(12_345L, loaded?.durationMs)
        assertEquals(4, loaded?.mediaCount)
    }

    @Test
    fun `codex repository durably replaces an existing saved post without changing membership`() = runTest {
        val dir = tempDir("codex-refresh-post-")
        val first = FileBackedCodexRepository(dir)
        val created = first.createCodex("Saved")
        val stale = samplePost("1", localPath = null, source = SourceKey.GELBOORU)
        first.addItem(created.codexId, stale)
        val originalItem = first.observeCodexItems(created.codexId).first().single()
        val refreshed = stale.copy(
            preview = ImageRef(
                url = "https://gelbooru.com/refreshed-preview.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
            full = ImageRef(
                url = "https://gelbooru.com/refreshed-full.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
        )

        first.updatePost(refreshed)

        val second = FileBackedCodexRepository(dir)
        assertEquals(refreshed.preview.url, second.getPost(stale.id)?.preview?.url)
        assertEquals(refreshed.full?.url, second.getPost(stale.id)?.full?.url)
        assertEquals(listOf(originalItem), second.observeCodexItems(created.codexId).first())
    }

    @Test
    fun `codex repository reads legacy post records with safe typed defaults`() = runTest {
        val dir = tempDir("codex-legacy-post-record-")
        val storageFile = dir.resolve("codex_store.json")
        storageFile.writeText(
            """
            {
              "codices": [],
              "items": {},
              "posts": [
                {
                  "source": "PIXIV",
                  "sourcePostId": "1",
                  "previewUrl": "https://example.com/1.jpg",
                  "previewLocalPath": null,
                  "previewMime": "image/jpeg",
                  "fullUrl": "https://example.com/full/1.jpg",
                  "fullLocalPath": null,
                  "fullMime": "image/jpeg",
                  "pageUrl": "https://example.com/post/1",
                  "width": 100,
                  "height": 100,
                  "canonicalTags": ["landscape"],
                  "rawTags": ["landscape"],
                  "authorName": "artist",
                  "createdAtEpochMs": 1,
                  "media": [],
                  "title": "Legacy"
                },
                {
                  "source": "PIXIV",
                  "sourcePostId": "2",
                  "previewUrl": "https://example.com/2.jpg",
                  "previewLocalPath": null,
                  "previewMime": "image/jpeg",
                  "fullUrl": null,
                  "fullLocalPath": null,
                  "fullMime": null,
                  "pageUrl": "https://example.com/post/2",
                  "width": 100,
                  "height": 100,
                  "canonicalTags": ["portrait"],
                  "rawTags": ["portrait"],
                  "authorName": "legacy artist",
                  "createdAtEpochMs": 2,
                  "creatorProfile": {
                    "source": "PIXIV",
                    "displayName": "legacy artist",
                    "profileId": "legacy-artist",
                    "profileUrl": "https://example.com/artist/legacy-artist",
                    "uploadsQuery": "legacy-artist"
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val repository = FileBackedCodexRepository(dir)
        val loaded = repository.getPost(PostId(SourceKey.PIXIV, "1"))
        val loadedWithCreator = repository.getPost(PostId(SourceKey.PIXIV, "2"))

        assertNotNull(loaded)
        assertEquals(null, loaded?.creatorProfile)
        assertEquals("Legacy", loaded?.title)
        assertEquals(emptyList<String>(), loaded?.preview?.progressiveUrls)
        assertEquals(emptyList<String>(), loaded?.full?.progressiveUrls)
        assertFalse(loaded?.preview?.isAnimated ?: true)
        assertFalse(loaded?.full?.isAnimated ?: true)
        assertEquals(listOf(PostTaxonomyTerm(value = "landscape")), loaded?.taxonomy)
        assertEquals(emptyList<CreatorProfile>(), loaded?.creatorProfiles)
        assertEquals(null, loaded?.durationMs)
        assertEquals(null, loaded?.mediaCount)
        assertEquals("legacy artist", loadedWithCreator?.creatorProfile?.displayName)
        assertEquals(
            listOf("legacy artist"),
            loadedWithCreator?.creatorProfiles?.map(CreatorProfile::displayName),
        )
        assertEquals(listOf(PostTaxonomyTerm(value = "portrait")), loadedWithCreator?.taxonomy)
    }

    @Test
    fun `codex repository skips malformed elements inside typed post arrays`() = runTest {
        val dir = tempDir("codex-malformed-typed-arrays-")
        dir.resolve("codex_store.json").writeText(
            """
            {
              "codices": [],
              "items": {},
              "posts": [
                {
                  "source": "PIXIV",
                  "sourcePostId": "1",
                  "previewUrl": "https://example.com/preview.jpg",
                  "previewLocalPath": null,
                  "previewMime": "image/jpeg",
                  "fullUrl": null,
                  "fullLocalPath": null,
                  "fullMime": null,
                  "pageUrl": "https://example.com/post/1",
                  "width": 100,
                  "height": 100,
                  "canonicalTags": ["legacy"],
                  "rawTags": ["legacy"],
                  "authorName": "artist",
                  "createdAtEpochMs": 1,
                  "media": [
                    null,
                    {"url": "https://example.com/full.jpg", "localPath": null, "mime": "image/jpeg"}
                  ],
                  "taxonomy": [
                    null,
                    {"value": "", "facet": "TAG"},
                    {"value": "removed", "facet": "REMOVED_FACET"},
                    {"value": " najar ", "facet": "ARTIST", "sourceNamespace": " artist "}
                  ],
                  "creatorProfiles": [
                    null,
                    {"source": "PIXIV", "displayName": "Najar", "profileId": "najar"}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val loaded = FileBackedCodexRepository(dir).getPost(PostId(SourceKey.PIXIV, "1"))

        assertEquals(listOf("https://example.com/full.jpg"), loaded?.media?.map(ImageRef::url))
        assertEquals(
            listOf(PostTaxonomyTerm(value = "najar", facet = SearchFacet.ARTIST, sourceNamespace = "artist")),
            loaded?.taxonomy,
        )
        assertEquals(listOf("Najar"), loaded?.creatorProfiles?.map(CreatorProfile::displayName))
    }

    @Test
    fun `codex repository drops records with unknown sources without losing valid records`() = runTest {
        val dir = tempDir("codex-unknown-source-records-")
        val storageFile = dir.resolve("codex_store.json")
        storageFile.writeText(
            """
            {
              "codices": [
                {
                  "codexId": "saved",
                  "name": "Saved",
                  "createdAtEpochMs": 1
                }
              ],
              "items": {
                "saved": [
                  {
                    "codexId": "saved",
                    "source": "PIXIV",
                    "sourcePostId": "1",
                    "savedAtEpochMs": 2
                  },
                  {
                    "codexId": "saved",
                    "source": "REMOVED_SOURCE",
                    "sourcePostId": "2",
                    "savedAtEpochMs": 3
                  }
                ]
              },
              "posts": [
                {
                  "source": "PIXIV",
                  "sourcePostId": "1",
                  "previewUrl": "https://example.com/1.jpg",
                  "previewLocalPath": null,
                  "previewMime": "image/jpeg",
                  "fullUrl": "https://example.com/full/1.jpg",
                  "fullLocalPath": null,
                  "fullMime": "image/jpeg",
                  "pageUrl": "https://example.com/post/1",
                  "width": 100,
                  "height": 100,
                  "canonicalTags": ["landscape"],
                  "rawTags": ["landscape"],
                  "authorName": "artist",
                  "createdAtEpochMs": 1,
                  "media": [],
                  "title": "Valid",
                  "creatorProfile": {
                    "source": "REMOVED_SOURCE",
                    "displayName": "legacy artist",
                    "profileId": "artist-1",
                    "profileUrl": "https://example.com/creator/1",
                    "uploadsQuery": "artist-1"
                  }
                },
                {
                  "source": "REMOVED_SOURCE",
                  "sourcePostId": "2",
                  "previewUrl": "https://example.com/2.jpg",
                  "previewLocalPath": null,
                  "previewMime": "image/jpeg",
                  "fullUrl": null,
                  "fullLocalPath": null,
                  "fullMime": null,
                  "pageUrl": "https://example.com/post/2",
                  "width": 100,
                  "height": 100,
                  "canonicalTags": [],
                  "rawTags": [],
                  "authorName": null,
                  "createdAtEpochMs": null,
                  "media": []
                }
              ]
            }
            """.trimIndent(),
        )

        val repository = FileBackedCodexRepository(dir)
        val items = repository.observeCodexItems("saved").first()
        val posts = repository.observeCodexPosts("saved", CodexSortMode.NEWEST_SAVED).first()
        val validPost = repository.getPost(PostId(SourceKey.PIXIV, "1"))

        assertEquals(listOf(PostId(SourceKey.PIXIV, "1")), items.map { it.postId })
        assertEquals(listOf(PostId(SourceKey.PIXIV, "1")), posts.map { it.id })
        assertEquals("Valid", validPost?.title)
        assertNull(validPost?.creatorProfile)
    }

    @Test
    fun `codex repository writes valid json without leaving temp files`() = runTest {
        val dir = tempDir("codex-atomic-write-")
        val repository = FileBackedCodexRepository(dir)
        val codex = repository.createCodex("Saved")

        repository.addItem(codex.codexId, samplePost("1", localPath = null, source = SourceKey.PIXIV))

        val storageFile = dir.resolve("codex_store.json")
        val reloaded = FileBackedCodexRepository(dir)
        val leftoverTempFiles = dir.listFiles().orEmpty().filter { file ->
            file.name.startsWith("codex_store.json.") && file.name.endsWith(".tmp")
        }

        assertTrue(storageFile.readText().contains("\"codices\""))
        assertEquals(1, reloaded.observeCodexItems(codex.codexId).first().size)
        assertTrue(leftoverTempFiles.isEmpty())
    }

    @Test
    fun `codex repository ensures stable system codex across restarts`() = runTest {
        val dir = tempDir("codex-likes-system-")
        val first = FileBackedCodexRepository(dir)
        first.ensureCodex(codexId = "system_likes_codex", name = "Likes")

        val second = FileBackedCodexRepository(dir)
        val existing = second.ensureCodex(codexId = "system_likes_codex", name = "Likes")
        val codices = second.observeCodices().first()

        assertEquals("system_likes_codex", existing.codexId)
        assertEquals("Likes", existing.name)
        assertEquals(1, codices.size)
    }

    @Test
    fun `codex repository appends numeric suffix for duplicate names across restarts`() = runTest {
        val dir = tempDir("codex-duplicate-names-")
        val first = FileBackedCodexRepository(dir)

        val alpha = first.createCodex("Favorites")
        val beta = first.createCodex("Favorites")
        val gamma = first.ensureCodex(codexId = "manual", name = "Favorites")
        first.renameCodex(beta.codexId, "Favorites")

        val second = FileBackedCodexRepository(dir)
        val names = second.observeCodices().first().associateBy({ it.codexId }, { it.name })

        assertEquals("Favorites", names[alpha.codexId])
        assertEquals("Favorites 2", names[beta.codexId])
        assertEquals("Favorites 3", names[gamma.codexId])
    }

    @Test
    fun `codex repository persists reorder across restarts`() = runTest {
        val dir = tempDir("codex-reorder-")
        val first = FileBackedCodexRepository(dir)
        val alpha = first.createCodex("Alpha")
        val beta = first.createCodex("Beta")
        val gamma = first.createCodex("Gamma")

        first.reorderCodex(codexId = gamma.codexId, targetIndex = 0)

        val second = FileBackedCodexRepository(dir)
        val orderedIds = second.observeCodices().first().map { codex -> codex.codexId }

        assertEquals(listOf(gamma.codexId, alpha.codexId, beta.codexId), orderedIds)
    }

    @Test
    fun `query repository persists applied query and scroll offsets`() = runTest {
        val dir = tempDir("query-store-")
        val first = FileBackedQueryRepository(dir)
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTerms = listOf(
                SearchTerm(value = "landscape"),
                SearchTerm(
                    value = "najar",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                ),
            ),
            excludeTerms = listOf(
                SearchTerm(
                    value = "english",
                    facet = SearchFacet.LANGUAGE,
                    sourceNamespace = "language",
                ),
            ),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = 10,
        )
        first.upsertAppliedQuery("source:PIXIV", query)
        first.upsertScrollOffset("qhash", 320)

        val second = FileBackedQueryRepository(dir)
        val loaded = second.observeAppliedQuery("source:PIXIV").first()
        val storedJson = dir.resolve("query_store.json").readText()

        assertEquals(query, loaded)
        assertTrue(storedJson.contains("\"includeTerms\""))
        assertTrue(storedJson.contains("\"excludeTerms\""))
        assertTrue(storedJson.contains("\"includeTags\""))
        assertTrue(storedJson.contains("\"excludeTags\""))
        assertEquals(320, second.getScrollOffset("qhash"))
    }

    @Test
    fun `query repository falls back when persisted source or sort is unknown`() = runTest {
        val dir = tempDir("query-unknown-enums-")
        dir.resolve("query_store.json").writeText(
            """
            {
              "queries": {
                "source:REMOVED_SOURCE": {
                  "modeType": "source",
                  "modeSource": "REMOVED_SOURCE",
                  "includeTags": ["landscape"],
                  "excludeTags": ["comic"],
                  "sort": "REMOVED_SORT",
                  "dateFromEpochMs": 100,
                  "dateToEpochMs": 200,
                  "minScore": 10
                }
              },
              "scrollOffsets": {
                "qhash": 320
              }
            }
            """.trimIndent(),
        )

        val repository = FileBackedQueryRepository(dir)
        val loaded = repository.observeAppliedQuery("source:REMOVED_SOURCE").first()

        assertEquals(QueryMode.Unified, loaded?.mode)
        assertEquals(SortMode.TOP, loaded?.sort)
        assertEquals(listOf("landscape"), loaded?.includeTags)
        assertEquals(listOf(SearchTerm(value = "landscape")), loaded?.includeTerms)
        assertEquals(320, repository.getScrollOffset("qhash"))
    }

    @Test
    fun `query repository distinguishes absent typed terms from explicit empty terms`() = runTest {
        val dir = tempDir("query-null-vs-empty-terms-")
        dir.resolve("query_store.json").writeText(
            """
            {
              "queries": {
                "legacy": {
                  "modeType": "source",
                  "modeSource": "PIXIV",
                  "includeTags": ["legacy include"],
                  "excludeTags": ["legacy exclude"],
                  "includeTerms": null,
                  "excludeTerms": null,
                  "sort": "TOP",
                  "dateFromEpochMs": null,
                  "dateToEpochMs": null,
                  "minScore": null
                },
                "typed-empty": {
                  "modeType": "source",
                  "modeSource": "PIXIV",
                  "includeTags": ["must not return"],
                  "excludeTags": ["must not return"],
                  "includeTerms": [],
                  "excludeTerms": [],
                  "sort": "TOP",
                  "dateFromEpochMs": null,
                  "dateToEpochMs": null,
                  "minScore": null
                },
                "typed-malformed": {
                  "modeType": "source",
                  "modeSource": "PIXIV",
                  "includeTags": ["must not return"],
                  "excludeTags": [],
                  "includeTerms": [
                    null,
                    {"value": "", "facet": "TAG"},
                    {"value": "unknown", "facet": "REMOVED_FACET"},
                    {"value": " najar ", "facet": "ARTIST", "sourceNamespace": " artist "}
                  ],
                  "excludeTerms": [null],
                  "sort": "TOP",
                  "dateFromEpochMs": null,
                  "dateToEpochMs": null,
                  "minScore": null
                }
              },
              "scrollOffsets": {}
            }
            """.trimIndent(),
        )

        val repository = FileBackedQueryRepository(dir)
        val legacy = repository.observeAppliedQuery("legacy").first()
        val typedEmpty = repository.observeAppliedQuery("typed-empty").first()
        val typedMalformed = repository.observeAppliedQuery("typed-malformed").first()

        assertEquals(listOf(SearchTerm(value = "legacy include")), legacy?.includeTerms)
        assertEquals(listOf(SearchTerm(value = "legacy exclude")), legacy?.excludeTerms)
        assertEquals(emptyList<SearchTerm>(), typedEmpty?.includeTerms)
        assertEquals(emptyList<SearchTerm>(), typedEmpty?.excludeTerms)
        assertEquals(
            listOf(SearchTerm(value = "najar", facet = SearchFacet.ARTIST, sourceNamespace = "artist")),
            typedMalformed?.includeTerms,
        )
        assertEquals(emptyList<SearchTerm>(), typedMalformed?.excludeTerms)
    }

    @Test
    fun `recents repository persists watched posts searches and activity order`() = runTest {
        val dir = tempDir("recents-store-")
        var now = 10_000L
        val first = FileBackedRecentsRepository(
            baseDirectory = dir,
            watchedLimit = 2,
            searchLimit = 2,
            clock = { now },
        )
        val media = ImageRef(
            url = "https://example.com/full-video.mp4",
            localPath = null,
            mime = "video/mp4",
            progressiveUrls = listOf("https://example.com/sample-video.mp4"),
        )
        val basePixivPost = samplePost("1", localPath = null, source = SourceKey.PIXIV)
        val pixivPost = basePixivPost.copy(
            title = "First viewed",
            preview = basePixivPost.preview.copy(
                mime = "image/webp",
                isAnimated = true,
            ),
            media = listOf(media),
            durationMs = 42_000L,
            mediaCount = 2,
            taxonomy = listOf(
                PostTaxonomyTerm(
                    value = "najar",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                ),
            ),
        )
        val gelbooruPost = samplePost("2", localPath = null, source = SourceKey.GELBOORU)
        val aibooruPost = samplePost("3", localPath = null, source = SourceKey.AIBOORU)
        val firstQuery = sampleQuery(includeTags = listOf("landscape")).copy(
            includeTerms = listOf(
                SearchTerm(
                    value = "najar",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                ),
            ),
        )
        val secondQuery = sampleQuery(includeTags = listOf("portrait"))
        val thirdQuery = sampleQuery(includeTags = listOf("city"))

        first.recordWatchedPost(pixivPost, ViewerStreamSource.SEARCH, "query-1")
        now += 1
        first.recordWatchedPost(gelbooruPost, ViewerStreamSource.FOR_YOU, "query-2")
        now += 1
        first.recordWatchedPost(pixivPost.copy(title = "Viewed again"), ViewerStreamSource.CODEX, "query-3")
        now += 1
        first.recordWatchedPost(aibooruPost, ViewerStreamSource.CREATOR_PROFILE, "query-4")
        now += 1
        first.recordSearch(firstQuery, "search-1")
        now += 1
        first.recordSearch(secondQuery, "search-2")
        now += 1
        first.recordSearch(
            firstQuery.copy(excludeTerms = listOf(SearchTerm(value = "comic"))),
            "search-1",
        )
        now += 1
        first.recordSearch(thirdQuery, "search-3")

        val second = FileBackedRecentsRepository(dir)
        val watched = second.observeWatchedPosts().first()
        val searches = second.observeSearches().first()
        val activity = second.observeActivity().first()

        assertEquals(listOf(aibooruPost.id, pixivPost.id), watched.map { entry -> entry.post.id })
        assertEquals("Viewed again", watched[1].post.title)
        assertEquals(ViewerStreamSource.CODEX, watched[1].origin)
        assertEquals("query-3", watched[1].originQueryHash)
        assertEquals(listOf("https://example.com/sample-video.mp4"), watched[1].post.media.single().progressiveUrls)
        assertTrue(watched[1].post.preview.isAnimated)
        assertEquals(42_000L, watched[1].post.durationMs)
        assertEquals(2, watched[1].post.mediaCount)
        assertEquals(pixivPost.taxonomy, watched[1].post.taxonomy)
        assertEquals(listOf("search-3", "search-1"), searches.map { entry -> entry.queryHash })
        assertEquals(listOf("comic"), searches[1].query.excludeTags)
        assertEquals(firstQuery.includeTerms, searches[1].query.includeTerms)
        assertEquals(searches.first().searchedAtEpochMs, activity.first().occurredAtEpochMs)
    }

    @Test
    fun `recents repository clears watched and search history independently across restarts`() = runTest {
        val dir = tempDir("recents-clear-store-")
        val first = FileBackedRecentsRepository(dir, clock = { 1L })

        first.recordWatchedPost(samplePost("1", localPath = null), ViewerStreamSource.SEARCH, "hash")
        first.recordSearch(sampleQuery(), "query")
        first.clearWatchedPosts()

        val second = FileBackedRecentsRepository(dir)
        assertTrue(second.observeWatchedPosts().first().isEmpty())
        assertEquals(1, second.observeSearches().first().size)

        second.clearSearches()
        val third = FileBackedRecentsRepository(dir)
        assertTrue(third.observeSearches().first().isEmpty())

        third.recordWatchedPost(samplePost("2", localPath = null), ViewerStreamSource.CODEX, null)
        third.recordSearch(sampleQuery(includeTags = listOf("city")), "query-2")
        third.clearAll()

        val fourth = FileBackedRecentsRepository(dir)
        assertTrue(fourth.observeWatchedPosts().first().isEmpty())
        assertTrue(fourth.observeSearches().first().isEmpty())
    }

    @Test
    fun `settings repository persists updates`() = runTest {
        val dir = tempDir("settings-store-")
        val first = FileBackedSettingsRepository(dir)
        first.setEnabledSources(setOf(SourceKey.PIXIV, SourceKey.GELBOORU))
        first.setSourceWeights(mapOf(SourceKey.PIXIV to 4.0, SourceKey.GELBOORU to 1.0))
        first.setCacheFullImageOnSave(true)
        first.setResolveUnknownAnimatedDurations(true)
        first.setInvertMultiImageScrollDirection(true)
        first.setScenarioPreset(ScenarioPreset.EMPTY_RESULTS)
        first.setLastTab("codex")
        first.setActiveProfile("profile-alt")

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()

        assertTrue(loaded.cache.cacheFullImageOnSave)
        assertTrue(loaded.contentFilters.resolveUnknownAnimatedDurations)
        assertTrue(loaded.viewer.invertMultiImageScrollDirection)
        assertEquals(ScenarioPreset.EMPTY_RESULTS, loaded.scenarioPreset)
        assertEquals("codex", loaded.lastSelectedTabRoute)
        assertEquals("profile-alt", loaded.activeProfileId)
        val total = loaded.runtime.sourceWeights.values.sum()
        assertEquals(1.0, total, 0.0001)
    }

    @Test
    fun `settings repository persists provider health snapshots`() = runTest {
        val dir = tempDir("settings-provider-health-")
        val first = FileBackedSettingsRepository(dir)
        first.setProviderHealthSnapshots(
            listOf(
                ProviderHealthSnapshot(
                    source = SourceKey.GELBOORU,
                    status = ProviderHealthSnapshotStatus.OK,
                    checkedAtEpochMs = 123L,
                    latencyMs = 45L,
                    message = "Returned 2 posts",
                ),
                ProviderHealthSnapshot(
                    source = SourceKey.PIXIV,
                    status = ProviderHealthSnapshotStatus.FAILED,
                    checkedAtEpochMs = 124L,
                    failureReason = "AUTH_REQUIRED",
                    message = "Missing credentials",
                ),
            )
        )

        val second = FileBackedSettingsRepository(dir)
        val health = second.observeSettings().first().providerHealth

        assertEquals(ProviderHealthSnapshotStatus.OK, health[SourceKey.GELBOORU]?.status)
        assertEquals(45L, health[SourceKey.GELBOORU]?.latencyMs)
        assertEquals("AUTH_REQUIRED", health[SourceKey.PIXIV]?.failureReason)
    }

    @Test
    fun `settings repository defaults unknown duration resolution on for old files`() = runTest {
        val dir = tempDir("settings-store-old-")
        dir.resolve("settings_store.json").writeText(
            """
                {
                  "enabledSources": ["PIXIV", "GELBOORU"],
                  "cacheFullImageOnSave": true
                }
            """.trimIndent(),
        )

        val loaded = FileBackedSettingsRepository(dir).observeSettings().first()

        assertTrue(loaded.cache.cacheFullImageOnSave)
        assertTrue(loaded.contentFilters.resolveUnknownAnimatedDurations)
        assertFalse(loaded.viewer.invertMultiImageScrollDirection)
    }

    @Test
    fun `legacy settings enable hitomi once and preserve a post-upgrade disable`() = runTest {
        val dir = tempDir("settings-source-catalog-migration-")
        dir.resolve("settings_store.json").writeText(
            """
                {
                  "enabledSources": ["PIXIV", "GELBOORU"],
                  "sourceWeights": {"PIXIV": 0.8, "GELBOORU": 0.2}
                }
            """.trimIndent(),
        )

        val repository = FileBackedSettingsRepository(dir)
        val migrated = repository.observeSettings().first()

        assertTrue(SourceKey.HITOMI in migrated.runtime.enabledSources)
        assertTrue(migrated.runtime.sourceWeights.containsKey(SourceKey.HITOMI))
        assertTrue(dir.resolve("settings_store.json").readText().contains("\"sourceCatalogVersion\": 2"))

        repository.setEnabledSources(migrated.runtime.enabledSources - SourceKey.HITOMI)

        val reloaded = FileBackedSettingsRepository(dir).observeSettings().first()
        assertFalse(SourceKey.HITOMI in reloaded.runtime.enabledSources)
    }

    @Test
    fun `settings repository persists dynamic recommendation profiles`() = runTest {
        val dir = tempDir("settings-profiles-")
        val first = FileBackedSettingsRepository(dir)
        val created = first.addRecommendationProfile("Anime Mood")
        assertTrue(
            first.addForYouBlacklistEntry(
                profileId = created.profileId,
                source = SourceKey.PIXIV,
                tags = listOf("portrait", "artist"),
            )
        )
        assertTrue(
            first.addFavoriteTag(
                profileId = created.profileId,
                source = SourceKey.GELBOORU,
                tag = "Blue Hair",
            )
        )
        first.setActiveProfile(created.profileId)

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()

        assertEquals(created.profileId, loaded.activeProfileId)
        assertTrue(loaded.recommendationProfiles.any { it.profileId == created.profileId && it.name == "Anime Mood" })
        assertEquals(1, loaded.forYouBlacklistByProfile[created.profileId].orEmpty().size)
        assertEquals(listOf("blue_hair"), loaded.favoriteTagsByProfile[created.profileId].orEmpty().map { it.tag })
        assertTrue(second.removeRecommendationProfile(created.profileId))
        val third = FileBackedSettingsRepository(dir)
        assertTrue(third.observeSettings().first().recommendationProfiles.none { it.profileId == created.profileId })
        assertTrue(third.observeSettings().first().forYouBlacklistByProfile[created.profileId].isNullOrEmpty())
        assertTrue(third.observeSettings().first().favoriteTagsByProfile[created.profileId].isNullOrEmpty())
    }

    @Test
    fun `settings repository persists for you blacklist entries`() = runTest {
        val dir = tempDir("settings-for-you-blacklist-")
        val first = FileBackedSettingsRepository(dir)
        val profileId = first.observeSettings().first().activeProfileId

        assertTrue(
            first.addForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("Cloud", "Sky"),
            )
        )
        assertFalse(
            first.addForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("sky", "cloud"),
            )
        )

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()
        assertEquals(listOf("cloud", "sky"), loaded.forYouBlacklistByProfile[profileId].orEmpty().first().tags)

        assertTrue(
            second.removeForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("cloud", "sky"),
            )
        )
        val third = FileBackedSettingsRepository(dir)
        assertTrue(third.observeSettings().first().forYouBlacklistByProfile[profileId].isNullOrEmpty())
    }

    @Test
    fun `settings repository persists source-aware favorite tags`() = runTest {
        val dir = tempDir("settings-favorite-tags-")
        val first = FileBackedSettingsRepository(dir)
        val profileId = first.observeSettings().first().activeProfileId

        assertTrue(
            first.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "Blue Hair",
            )
        )
        assertFalse(
            first.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "blue_hair",
            )
        )
        assertTrue(
            first.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.PIXIV,
                tag = "Blue Hair",
            )
        )

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()
        assertEquals(listOf("blue_hair"), loaded.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.GELBOORU }.map { it.tag })
        assertEquals(listOf("Blue Hair"), loaded.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.PIXIV }.map { it.tag })

        assertTrue(
            second.removeFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "blue_hair",
            )
        )
        val third = FileBackedSettingsRepository(dir)
        assertEquals(1, third.observeSettings().first().favoriteTagsByProfile[profileId].orEmpty().size)
        assertEquals(
            SourceKey.PIXIV,
            third.observeSettings().first().favoriteTagsByProfile[profileId].orEmpty().first().source,
        )
    }

    @Test
    fun `likes repository persists toggles and profile isolation`() = runTest {
        val dir = tempDir("likes-store-")
        val first = FileBackedLikesRepository(dir)
        val pixivPost = samplePost("10", localPath = null, source = SourceKey.PIXIV)
        val gelbooruPost = samplePost("11", localPath = null, source = SourceKey.GELBOORU)

        first.toggleLike(
            profileId = "profile-main",
            postId = pixivPost.id,
            tags = listOf("cloud", "sky"),
        )
        first.toggleLike(
            profileId = "profile-alt",
            postId = gelbooruPost.id,
            tags = listOf("sunset"),
        )

        val second = FileBackedLikesRepository(dir)
        val user1Likes = second.observeLikes("profile-main").first()
        val user2Likes = second.observeLikes("profile-alt").first()

        assertEquals(1, user1Likes.size)
        assertEquals(pixivPost.id, user1Likes.first().postId)
        assertEquals(listOf("cloud", "sky"), user1Likes.first().tags)
        assertEquals(1, user2Likes.size)
        assertEquals(gelbooruPost.id, user2Likes.first().postId)

        second.clearLikes("profile-main")
        val third = FileBackedLikesRepository(dir)
        assertTrue(third.observeLikes("profile-main").first().isEmpty())
        assertEquals(1, third.observeLikes("profile-alt").first().size)
    }

    @Test
    fun `cache repository writes entries and survives restart`() = runTest {
        val dir = tempDir("cache-store-")
        val sourceFile = File(dir, "source-thumb.jpg")
        sourceFile.writeText("image-bytes")

        val first = FileBackedCacheRepository(dir)
        first.cacheThumbnail(samplePost("1", sourceFile.absolutePath))

        val second = FileBackedCacheRepository(dir)
        val snapshot = second.observeSnapshot().first()

        assertEquals(1, snapshot.thumbnailCount)
        assertEquals(0, snapshot.fullImageCount)
    }

    @Test
    fun `ui restore repository persists tab scroll and viewer context`() = runTest {
        val dir = tempDir("ui-restore-store-")
        val first = FileBackedUiRestoreRepository(dir)
        val context = ViewerLaunchContext(
            queryHash = "qhash",
            startIndex = 4,
            streamSource = ViewerStreamSource.CODEX,
            scrollOffsetHint = 90,
        )

        first.setLastTab("settings")
        first.setSearchScrollState("qhash", SearchScrollState(firstVisibleItemIndex = 3, firstVisibleItemOffsetPx = 28))
        first.setViewerLaunchContext(context)

        val second = FileBackedUiRestoreRepository(dir)

        assertEquals("settings", second.getLastTab())
        assertEquals(3, second.getSearchScrollState("qhash")?.firstVisibleItemIndex)
        assertEquals(context, second.observeViewerLaunchContext().first())
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

    private fun tempDir(prefix: String): File {
        return tempFolder.newFolder(prefix)
    }

    private fun samplePost(id: String, localPath: String?, source: SourceKey = SourceKey.PIXIV): Post {
        return Post(
            id = PostId(source, id),
            preview = ImageRef(url = "https://example.com/$id.jpg", localPath = localPath, mime = "image/jpeg"),
            full = ImageRef(url = "https://example.com/full/$id.jpg", localPath = null, mime = "image/jpeg"),
            pageUrl = "https://example.com/post/$id",
            width = 100,
            height = 100,
            canonicalTags = listOf("landscape"),
            rawTags = listOf("landscape"),
            authorName = "artist",
            createdAtEpochMs = 1L,
            creatorProfile = CreatorProfile(
                source = source,
                displayName = "artist",
                profileId = "profile-$id",
                profileUrl = "https://example.com/creator/$id",
                uploadsQuery = "uploads-$id",
            ),
        )
    }
}
