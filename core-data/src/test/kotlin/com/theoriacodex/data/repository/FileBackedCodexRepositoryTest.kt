package com.theoriacodex.data.repository

import com.theoriacodex.data.testing.RecordingIoDispatcher
import com.theoriacodex.data.testing.ControllableIoDispatcher
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.CodexAutomaticTag
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
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class FileBackedCodexRepositoryTest : FileBackedRepositoryTestFixture() {
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
    fun `codex addItem persists hydrated snapshots without duplicating or resaving membership`() = runTest {
        val dir = tempDir("codex-add-item-hydration-")
        val first = FileBackedCodexRepository(dir)
        val created = first.createCodex("Saved")
        val sparse = samplePost("1", localPath = null, source = SourceKey.GELBOORU).copy(
            full = null,
            title = null,
        )
        first.addItem(created.codexId, sparse)
        first.addItem(
            created.codexId,
            samplePost("2", localPath = null, source = SourceKey.PIXIV),
        )
        val originalItems = first.observeCodexItems(created.codexId).first()
        val hydrated = sparse.copy(
            title = "Hydrated",
            full = ImageRef(
                url = "https://gelbooru.com/hydrated-full.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
        )

        first.addItem(created.codexId, hydrated)

        assertEquals(originalItems, first.observeCodexItems(created.codexId).first())
        val reconstructed = FileBackedCodexRepository(dir)
        val reconstructedItems = reconstructed.observeCodexItems(created.codexId).first()
        assertEquals(hydrated, reconstructed.getPost(hydrated.id))
        assertEquals(originalItems, reconstructedItems)
        assertEquals(1, reconstructedItems.count { item -> item.postId == hydrated.id })
    }

    @Test
    fun `codex addItem skips persistence when snapshot and membership are unchanged`() = runTest {
        val dir = tempDir("codex-add-item-no-op-")
        val repository = FileBackedCodexRepository(dir)
        val codex = repository.createCodex("Saved")
        val post = samplePost("1", localPath = null, source = SourceKey.PIXIV)
        repository.addItem(codex.codexId, post)
        val storageFile = dir.resolve("codex_store.json")
        val sentinelModifiedAt = 1_000_000_000_000L
        assertTrue(storageFile.setLastModified(sentinelModifiedAt))

        repository.addItem(codex.codexId, post)

        assertEquals(sentinelModifiedAt, storageFile.lastModified())
        assertEquals(1, repository.observeCodexItems(codex.codexId).first().size)
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
    fun `codex repository persists automatic tags across restarts`() = runTest {
        val dir = tempDir("codex-automatic-tags-")
        val first = FileBackedCodexRepository(dir)
        val codex = first.createCodex("Automatic")

        first.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue sky"),
            enabled = true,
        )
        first.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue_sky"),
            enabled = true,
        )

        val reloaded = FileBackedCodexRepository(dir)
        assertEquals(
            listOf(CodexAutomaticTag(SourceKey.GELBOORU, "blue sky")),
            reloaded.observeCodex(codex.codexId).first()?.automaticTags,
        )

        reloaded.setAutomaticTag(
            codex.codexId,
            CodexAutomaticTag(SourceKey.GELBOORU, "blue_sky"),
            enabled = false,
        )
        assertTrue(FileBackedCodexRepository(dir).observeCodex(codex.codexId).first()?.automaticTags.isNullOrEmpty())
    }

}
