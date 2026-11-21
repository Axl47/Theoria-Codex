package com.theoriacodex.data.storage

import com.theoriacodex.data.repository.mergeSparsePost
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostStorageRecordsTest {
    @Test
    fun `versioned storage record round trips every supported Post field`() {
        val post = samplePost()
        val record = PostStorageCodec.encode(post)
        val json = DurableJson.encodeToString(record)
        val reconstructedRecord = DurableJson.decodeFromString<PostStorageRecord>(json)

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertEquals(CURRENT_POST_STORAGE_SCHEMA_VERSION, record.schemaVersion)
        assertEquals(post, PostStorageCodec.decode(reconstructedRecord))
    }

    @Test
    fun `a gallery of unknown length keeps its flag, and other records stay as they were`() {
        val gallery = samplePost().copy(galleryLengthUnknown = true)
        val stored = DurableJson.decodeFromString<PostStorageRecord>(DurableJson.encodeToString(PostStorageCodec.encode(gallery)))

        assertEquals(gallery, PostStorageCodec.decode(stored))
        assertTrue("galleryLengthUnknown" !in DurableJson.encodeToString(PostStorageCodec.encode(samplePost())))
    }

    @Test
    fun `nhentai and hitomi listings saved before the gallery flag read as galleries of unknown length`() {
        fun saved(source: String, extra: String = "") = PostStorageCodec.decode(
            DurableJson.decodeFromString<PostStorageRecord>(
                """{"source":"$source","sourcePostId":"1","previewUrl":"https://example.com/t.jpg","media":[]$extra,"schemaVersion":1}""",
            ),
        )

        assertTrue(requireNotNull(saved("NHENTAI")).galleryLengthUnknown)
        assertTrue(requireNotNull(saved("HITOMI")).galleryLengthUnknown)
        assertTrue(!requireNotNull(saved("NHENTAI", ""","mediaCount":3""")).galleryLengthUnknown)
        assertTrue(!requireNotNull(saved("IWARA")).galleryLengthUnknown)
    }

    @Test
    fun `nhentai and hitomi records saved before fallback urls keep their copies as fallbacks`() {
        fun legacyRecord(source: String) = DurableJson.decodeFromString<PostStorageRecord>(
            """
            {
              "source": "$source",
              "sourcePostId": "1",
              "previewUrl": "https://example.com/thumb.webp",
              "previewProgressiveUrls": ["https://example.com/thumb2.webp"],
              "fullUrl": "https://example.com/1.webp",
              "fullProgressiveUrls": ["https://example.com/1.webp", "https://example.com/1.jpg"],
              "media": [{
                "url": "https://example.com/1.webp",
                "progressiveUrls": ["https://example.com/1.webp", "https://example.com/1.jpg"],
                "isAnimated": false
              }],
              "schemaVersion": 1
            }
            """.trimIndent(),
        )
        val copies = listOf("https://example.com/1.webp", "https://example.com/1.jpg")

        listOf("NHENTAI", "HITOMI").forEach { source ->
            val post = requireNotNull(PostStorageCodec.decode(legacyRecord(source)))

            assertEquals(emptyList<String>(), post.full?.progressiveUrls)
            assertEquals(copies, post.full?.fallbackUrls)
            assertEquals(copies, post.media.single().fallbackUrls)
            assertEquals(emptyList<String>(), post.media.single().progressiveUrls)
            assertEquals(listOf("https://example.com/thumb2.webp"), post.preview.fallbackUrls)
        }

        val booru = requireNotNull(PostStorageCodec.decode(legacyRecord("GELBOORU")))
        assertEquals(copies, booru.full?.progressiveUrls)
        assertEquals(emptyList<String>(), booru.full?.fallbackUrls)
    }

    @Test
    fun `an explicit fallback field is never reinterpreted`() {
        val post = samplePost().copy(
            id = PostId(SourceKey.NHENTAI, "1"),
            media = listOf(ImageRef("https://example.com/1.webp", null, "image/webp", progressiveUrls = listOf("https://example.com/small.webp"))),
        )

        val decoded = PostStorageCodec.decode(DurableJson.decodeFromString<PostStorageRecord>(DurableJson.encodeToString(PostStorageCodec.encode(post))))

        assertEquals(post.media, decoded?.media)
    }

    @Test
    fun `merging a ref without a thumbnail keeps the stored one`() {
        val stored = samplePost()
        val sparse = stored.copy(media = stored.media.map { it.copy(thumbnailUrl = null) })

        val merged = mergeSparsePost(existing = stored, incoming = sparse)

        assertEquals("https://example.com/full-thumb.webp", merged.media.single().thumbnailUrl)
    }

    @Test
    fun `merging a snapshot without a hash keeps the stored hash only for the same file`() {
        val stored = samplePost()
        val sparse = stored.copy(contentHash = null)
        val replaced = sparse.copy(full = stored.full?.copy(url = "https://example.com/replaced.webp"))

        assertEquals(stored.contentHash, mergeSparsePost(existing = stored, incoming = sparse).contentHash)
        assertEquals(stored.contentHash, mergeSparsePost(existing = stored, incoming = sparse.copy(full = null)).contentHash)
        assertNull(mergeSparsePost(existing = stored, incoming = replaced).contentHash)
    }

    // nhentai's mirror fallback and Codex share payloads carry tags without counts.
    @Test
    fun `merging tags without counts or translations keeps the stored ones`() {
        val stored = samplePost()
        val sparse = stored.copy(taxonomy = stored.taxonomy.map { it.copy(count = null, translation = null) })

        assertEquals(stored.taxonomy, mergeSparsePost(existing = stored, incoming = sparse).taxonomy)
    }

    @Test
    fun `merging creators without links keeps the stored links`() {
        val stored = samplePost()
        val sparse = stored.copy(creatorProfiles = stored.creatorProfiles.map { it.copy(profileUrl = null, uploadsQuery = null) })

        assertEquals(stored.creatorProfiles, mergeSparsePost(existing = stored, incoming = sparse).creatorProfiles)
    }

    @Test
    fun `merging present tag and creator values replaces the stored ones`() {
        val stored = samplePost()
        val (artist, landscape) = stored.taxonomy
        val creator = stored.creatorProfiles.single()
        val incoming = stored.copy(
            taxonomy = listOf(landscape.copy(translation = "scenery"), artist.copy(count = 43)),
            creatorProfiles = listOf(creator.copy(profileUrl = "https://example.com/artist-new")),
        )

        val merged = mergeSparsePost(existing = stored, incoming = incoming)

        assertEquals(incoming.taxonomy, merged.taxonomy)
        assertEquals(incoming.creatorProfiles, merged.creatorProfiles)
    }

    @Test
    fun `pre-versioned storage record retains safe typed fallbacks`() {
        val legacyJson =
            """
            {
              "source": "PIXIV",
              "sourcePostId": "legacy",
              "previewUrl": "https://example.com/preview.jpg",
              "previewLocalPath": null,
              "previewMime": "image/jpeg",
              "fullUrl": null,
              "fullLocalPath": null,
              "fullMime": null,
              "pageUrl": null,
              "width": null,
              "height": null,
              "canonicalTags": ["landscape"],
              "rawTags": [],
              "authorName": "artist",
              "createdAtEpochMs": 1,
              "creatorProfile": {
                "source": "PIXIV",
                "displayName": "artist",
                "profileId": "artist-id"
              }
            }
            """.trimIndent()

        val record = DurableJson.decodeFromString<PostStorageRecord>(legacyJson)
        val post = PostStorageCodec.decode(record)

        assertEquals(null, record.schemaVersion)
        assertEquals(listOf(PostTaxonomyTerm("landscape")), post?.taxonomy)
        assertEquals(listOf("artist"), post?.creatorProfiles?.map(CreatorProfile::displayName))
        assertEquals(emptyList<String>(), post?.preview?.progressiveUrls)
        assertEquals(false, post?.preview?.isAnimated)
    }

    @Test
    fun `pixiv payloads written before paired taxonomy decode with translations`() {
        val legacy = samplePost().copy(
            canonicalTags = listOf("風景", "scenery"),
            rawTags = listOf("風景"),
            taxonomy = listOf(PostTaxonomyTerm("風景"), PostTaxonomyTerm("scenery")),
        )

        val decoded = PostStorageCodec.decode(PostStorageCodec.encode(legacy))

        assertEquals(listOf(PostTaxonomyTerm("風景", translation = "scenery")), decoded?.taxonomy)
    }

    @Test
    fun `unknown explicit storage version fails closed`() {
        val future = PostStorageCodec.encode(samplePost()).copy(schemaVersion = 99)

        assertNull(PostStorageCodec.decode(future))
    }

    private fun samplePost(): Post {
        val creator = CreatorProfile(
            source = SourceKey.PIXIV,
            displayName = "artist",
            profileId = "artist-id",
            profileUrl = "https://example.com/artist",
            uploadsQuery = "artist-id",
        )
        val media = ImageRef(
            url = "https://example.com/full.webp",
            localPath = null,
            mime = "image/webp",
            progressiveUrls = listOf("https://example.com/sample.webp"),
            fallbackUrls = listOf("https://mirror.example.com/full.webp"),
            isAnimated = true,
            thumbnailUrl = "https://example.com/full-thumb.webp",
        )
        return Post(
            id = PostId(SourceKey.PIXIV, "42"),
            preview = ImageRef(
                url = "https://example.com/preview.jpg",
                localPath = "/cache/preview.jpg",
                mime = "image/jpeg",
                progressiveUrls = listOf("https://example.com/preview-small.jpg"),
                isAnimated = false,
            ),
            full = media.copy(thumbnailUrl = null),
            media = listOf(media),
            pageUrl = "https://example.com/post/42",
            width = 1200,
            height = 800,
            canonicalTags = listOf("landscape"),
            rawTags = listOf("Landscape"),
            authorName = "artist",
            createdAtEpochMs = 123L,
            title = "Sample",
            creatorProfile = creator,
            durationMs = 456L,
            mediaCount = 1,
            taxonomy = listOf(
                PostTaxonomyTerm(
                    value = "artist",
                    count = 42,
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "user",
                ),
                PostTaxonomyTerm(value = "Landscape", translation = "landscape"),
            ),
            creatorProfiles = listOf(creator),
            contentHash = "0123456789abcdef0123456789abcdef",
        )
    }
}
