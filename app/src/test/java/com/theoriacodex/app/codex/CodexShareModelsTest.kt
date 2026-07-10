package com.theoriacodex.app.codex

import com.google.gson.Gson
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexShareModelsTest {
    @Test
    fun `builds v2 share file with stable source ids and embedded snapshots`() {
        val export = buildCodexShareFile(
            title = "Favorites",
            posts = listOf(samplePost(SourceKey.PIXIV, "42"), samplePost(SourceKey.GELBOORU, "99")),
        )

        assertEquals("Favorites", export.title)
        assertEquals(2, export.version)
        assertEquals(
            listOf(
                PostId(source = SourceKey.PIXIV, sourcePostId = "42"),
                PostId(source = SourceKey.GELBOORU, sourcePostId = "99"),
            ),
            export.posts.orEmpty().mapNotNull(::codexSharePostId),
        )
        assertTrue(export.posts.orEmpty().all { post -> post.snapshot != null })
    }

    @Test
    fun `v1 share JSON keeps source id compatibility without a snapshot`() {
        val parsed = parseCodexShareFile(
            """
                {
                  "version": 1,
                  "title": "Legacy favorites",
                  "posts": [
                    {"source": "pixiv", "sourcePostId": "42"},
                    {"source": "GELBOORU", "sourcePostId": "99"}
                  ]
                }
            """.trimIndent(),
        )!!

        assertEquals(1, parsed.version)
        assertEquals("Legacy favorites", parsed.title)
        assertEquals(
            listOf(
                PostId(source = SourceKey.PIXIV, sourcePostId = "42"),
                PostId(source = SourceKey.GELBOORU, sourcePostId = "99"),
            ),
            parsed.posts.orEmpty().mapNotNull(::codexSharePostId),
        )
        assertTrue(parsed.posts.orEmpty().all { post -> post.snapshot == null })
        assertTrue(parsed.posts.orEmpty().all { post -> codexSharePostSnapshot(post) == null })
    }

    @Test
    fun `v2 share JSON round trips complete ordered post snapshots`() {
        val original = detailedPost()
        val encoded = Gson().toJson(buildCodexShareFile(title = "Offline", posts = listOf(original)))
        val parsed = parseCodexShareFile(encoded)!!

        val restored = codexSharePostSnapshot(parsed.posts.orEmpty().single())
        val expected = original.copy(
            preview = original.preview.copy(localPath = null),
            full = original.full?.copy(localPath = null),
            media = original.media.map { media -> media.copy(localPath = null) },
        )

        assertEquals(2, parsed.version)
        assertEquals(expected, restored)
        assertFalse(encoded.contains("/tmp/page-1.jpg"))
        assertNull(restored?.full?.localPath)
        assertTrue(restored?.media.orEmpty().all { media -> media.localPath == null })
        assertEquals(
            original.media.map(ImageRef::url),
            restored?.media?.map(ImageRef::url),
        )
        assertTrue(restored?.media?.get(1)?.isAnimated == true)
        assertEquals(original.taxonomy, restored?.taxonomy)
        assertEquals(original.creatorProfiles, restored?.creatorProfiles)
    }

    @Test
    fun `malformed optional snapshot values are dropped without losing the v1 id`() {
        val parsed = parseCodexShareFile(
            """
                {
                  "version": 2,
                  "title": "Defensive",
                  "posts": [
                    {
                      "source": " pixiv ",
                      "sourcePostId": " 42 ",
                      "snapshot": {
                        "preview": {
                          "url": " https://example.com/preview.webp ",
                          "progressiveUrls": [null, " ", "https://example.com/preview-small.webp"]
                        },
                        "full": {"url": " ", "localPath": " "},
                        "media": [
                          null,
                          {"url": " ", "localPath": " "},
                          {"url": "https://example.com/page.webp", "isAnimated": true}
                        ],
                        "pageUrl": " ",
                        "width": -1,
                        "height": 0,
                        "canonicalTags": [null, " general ", "general"],
                        "taxonomy": [
                          null,
                          {"value": " ", "facet": "TAG"},
                          {"value": "artist", "facet": "not-a-facet"},
                          {"value": " general "}
                        ],
                        "authorName": " ",
                        "createdAtEpochMs": -1,
                        "title": " ",
                        "creatorProfile": {"source": "PIXIV", "displayName": " "},
                        "creatorProfiles": [
                          null,
                          {"source": "unknown", "displayName": " Dropped creator "},
                          {"displayName": " Valid creator "}
                        ],
                        "durationMs": -1,
                        "mediaCount": 0
                      }
                    }
                  ]
                }
            """.trimIndent(),
        )!!
        val entry = parsed.posts.orEmpty().single()

        val restored = codexSharePostSnapshot(entry)

        assertEquals(PostId(SourceKey.PIXIV, "42"), codexSharePostId(entry))
        assertNotNull(restored)
        assertEquals("https://example.com/preview.webp", restored?.preview?.url)
        assertEquals(
            listOf("https://example.com/preview-small.webp"),
            restored?.preview?.progressiveUrls,
        )
        assertFalse(restored?.preview?.isAnimated ?: true)
        assertNull(restored?.full)
        assertEquals(listOf("https://example.com/page.webp"), restored?.media?.map(ImageRef::url))
        assertEquals(listOf("general"), restored?.canonicalTags)
        assertEquals(listOf("general"), restored?.rawTags)
        assertEquals(
            listOf(PostTaxonomyTerm(value = "general")),
            restored?.taxonomy,
        )
        assertEquals("Valid creator", restored?.creatorProfile?.displayName)
        assertEquals(SourceKey.PIXIV, restored?.creatorProfile?.source)
        assertEquals(listOf("Valid creator"), restored?.creatorProfiles?.map(CreatorProfile::displayName))
        assertNull(restored?.durationMs)
        assertNull(restored?.mediaCount)
        assertNull(restored?.pageUrl)
        assertNull(restored?.width)
        assertNull(restored?.height)
    }

    @Test
    fun `snapshot without a usable preview falls back to source resolution`() {
        val entry = CodexSharePost(
            source = "PIXIV",
            sourcePostId = "42",
            snapshot = CodexSharePostSnapshot(
                preview = CodexShareImageRef(url = " ", localPath = null),
            ),
        )

        assertEquals(PostId(SourceKey.PIXIV, "42"), codexSharePostId(entry))
        assertNull(codexSharePostSnapshot(entry))
    }

    @Test
    fun `wrong shaped optional JSON is ignored while valid post data survives`() {
        val parsed = parseCodexShareFile(
            """
                {
                  "version": 2,
                  "title": "Tolerant import",
                  "posts": [
                    17,
                    {
                      "source": "PIXIV",
                      "sourcePostId": "42",
                      "snapshot": {
                        "preview": {
                          "url": "https://example.com/preview.webp",
                          "localPath": "/private/device/cache/preview.webp",
                          "progressiveUrls": {"unexpected": true},
                          "isAnimated": "yes"
                        },
                        "full": {"localPath": "/private/device/cache/full.webp"},
                        "media": {"unexpected": true},
                        "width": [],
                        "height": false,
                        "canonicalTags": ["general"],
                        "rawTags": 42,
                        "taxonomy": {"value": "must-not-fallback"},
                        "creatorProfile": "unexpected",
                        "creatorProfiles": true,
                        "createdAtEpochMs": "yesterday",
                        "durationMs": "oops",
                        "mediaCount": {}
                      }
                    }
                  ]
                }
            """.trimIndent(),
        )!!

        val entry = parsed.posts.orEmpty().single()
        val restored = codexSharePostSnapshot(entry)

        assertEquals(PostId(SourceKey.PIXIV, "42"), codexSharePostId(entry))
        assertNotNull(restored)
        assertEquals("https://example.com/preview.webp", restored?.preview?.url)
        assertNull(restored?.preview?.localPath)
        assertNull(restored?.full)
        assertTrue(restored?.media.orEmpty().isEmpty())
        assertEquals(listOf("general"), restored?.canonicalTags)
        assertTrue(restored?.taxonomy.orEmpty().isEmpty())
        assertNull(restored?.durationMs)
        assertNull(restored?.mediaCount)
        assertNull(restored?.width)
        assertNull(restored?.height)
    }

    @Test
    fun `taxonomy distinguishes missing legacy data from explicit empty typed data`() {
        val parsed = parseCodexShareFile(
            """
                {
                  "version": 2,
                  "title": "Taxonomy semantics",
                  "posts": [
                    {
                      "source": "PIXIV",
                      "sourcePostId": "legacy",
                      "snapshot": {
                        "preview": {"url": "https://example.com/legacy.webp"},
                        "canonicalTags": ["legacy tag"]
                      }
                    },
                    {
                      "source": "PIXIV",
                      "sourcePostId": "typed-empty",
                      "snapshot": {
                        "preview": {"url": "https://example.com/typed.webp"},
                        "canonicalTags": ["flat tag"],
                        "taxonomy": []
                      }
                    },
                    {
                      "source": "PIXIV",
                      "sourcePostId": "typed-invalid",
                      "snapshot": {
                        "preview": {"url": "https://example.com/invalid.webp"},
                        "canonicalTags": ["flat tag"],
                        "taxonomy": [{"value": "artist", "facet": "not-a-facet"}]
                      }
                    }
                  ]
                }
            """.trimIndent(),
        )!!
        val restoredById = parsed.posts.orEmpty()
            .mapNotNull(::codexSharePostSnapshot)
            .associateBy { post -> post.id.sourcePostId }

        assertEquals(
            listOf(PostTaxonomyTerm(value = "legacy tag")),
            restoredById.getValue("legacy").taxonomy,
        )
        assertTrue(restoredById.getValue("typed-empty").taxonomy.isEmpty())
        assertTrue(restoredById.getValue("typed-invalid").taxonomy.isEmpty())
    }

    @Test
    fun `duplicate selection upgrades an earlier id-only entry to a later usable snapshot`() {
        val idOnly = CodexSharePost(source = "PIXIV", sourcePostId = "42")
        val withSnapshot = buildCodexShareFile(
            title = "Duplicate",
            posts = listOf(samplePost(SourceKey.PIXIV, "42")),
        ).posts.orEmpty().single()

        val selected = selectCodexShareEntries(listOf(idOnly, withSnapshot))

        assertEquals(1, selected.size)
        assertEquals(withSnapshot, selected.single().first)
        assertEquals(PostId(SourceKey.PIXIV, "42"), selected.single().second)
    }

    @Test
    fun `import resolution keeps source then snapshot then repository precedence`() = runTest {
        val snapshotPost = samplePost(SourceKey.PIXIV, "42")
        val entry = buildCodexShareFile("Import", listOf(snapshotPost)).posts.orEmpty().single()
        val sourcePost = snapshotPost.copy(title = "Fresh source")
        val storedPost = snapshotPost.copy(title = "Stored fallback")
        var repositoryReads = 0

        val resolvedFromSource = resolveCodexShareImportPost(entry, sourcePost) {
            repositoryReads += 1
            storedPost
        }
        assertEquals(sourcePost, resolvedFromSource)
        assertEquals(0, repositoryReads)

        val resolvedFromSnapshot = resolveCodexShareImportPost(entry, null) {
            repositoryReads += 1
            storedPost
        }
        assertEquals(snapshotPost, resolvedFromSnapshot)
        assertEquals(0, repositoryReads)

        val idOnly = CodexSharePost(source = "PIXIV", sourcePostId = "42")
        val resolvedFromRepository = resolveCodexShareImportPost(idOnly, null) {
            repositoryReads += 1
            storedPost
        }
        assertEquals(storedPost, resolvedFromRepository)
        assertEquals(1, repositoryReads)
    }

    @Test
    fun `parses share post ids case-insensitively and rejects invalid entries`() {
        assertEquals(
            PostId(source = SourceKey.RULE34VIDEO, sourcePostId = "abc"),
            codexSharePostId(CodexSharePost(source = " rule34video ", sourcePostId = " abc ")),
        )
        assertNull(codexSharePostId(CodexSharePost(source = "unknown", sourcePostId = "1")))
        assertNull(codexSharePostId(CodexSharePost(source = "PIXIV", sourcePostId = " ")))
        assertNull(codexSharePostId(CodexSharePost(source = null, sourcePostId = "1")))
    }

    @Test
    fun `sanitizes export names for filesystem output`() {
        assertEquals("my_codex_2026", sanitizeCodexExportName(" My Codex! 2026 "))
        assertEquals("codex", sanitizeCodexExportName("..."))
    }

    private fun detailedPost(): Post {
        val primaryCreator = CreatorProfile(
            source = SourceKey.NHENTAI,
            displayName = "Primary artist",
            profileId = "primary-artist",
            profileUrl = "https://example.com/artist/primary",
            uploadsQuery = "artist:primary-artist",
        )
        val secondCreator = CreatorProfile(
            source = SourceKey.NHENTAI,
            displayName = "Second artist",
            profileId = "second-artist",
            profileUrl = "https://example.com/artist/second",
            uploadsQuery = "artist:second-artist",
        )
        val media = listOf(
            ImageRef(
                url = "https://example.com/page-1.jpg",
                localPath = "/tmp/page-1.jpg",
                mime = "image/jpeg",
                progressiveUrls = listOf("https://example.com/page-1-small.jpg"),
                isAnimated = false,
            ),
            ImageRef(
                url = "https://example.com/page-2.webp",
                localPath = null,
                mime = "image/webp",
                progressiveUrls = listOf(
                    "https://example.com/page-2-small.webp",
                    "https://example.com/page-2-medium.webp",
                ),
                isAnimated = true,
            ),
            ImageRef(
                url = "https://example.com/video.mp4",
                localPath = null,
                mime = "video/mp4",
                isAnimated = false,
            ),
        )
        return Post(
            id = PostId(source = SourceKey.NHENTAI, sourcePostId = "123"),
            preview = ImageRef(
                url = "https://example.com/preview.webp",
                localPath = null,
                mime = "image/webp",
                progressiveUrls = listOf("https://example.com/preview-small.webp"),
                isAnimated = true,
            ),
            full = media.first(),
            media = media,
            pageUrl = "https://example.com/post/123",
            width = 1200,
            height = 1800,
            canonicalTags = listOf("animated", "primary artist"),
            rawTags = listOf("animated", "primary artist", "english"),
            taxonomy = listOf(
                PostTaxonomyTerm(value = "animated"),
                PostTaxonomyTerm(
                    value = "primary artist",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                ),
                PostTaxonomyTerm(
                    value = "english",
                    facet = SearchFacet.LANGUAGE,
                    sourceNamespace = "language",
                ),
            ),
            authorName = "Primary artist",
            createdAtEpochMs = 1_710_000_000_000L,
            title = "Mixed media gallery",
            creatorProfile = primaryCreator,
            creatorProfiles = listOf(primaryCreator, secondCreator),
            durationMs = 12_345L,
            mediaCount = 3,
        )
    }

    private fun samplePost(source: SourceKey, sourcePostId: String): Post {
        return Post(
            id = PostId(source = source, sourcePostId = sourcePostId),
            preview = ImageRef(url = "https://example.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            media = emptyList(),
            pageUrl = "https://example.com/post/$sourcePostId",
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = null,
        )
    }
}
