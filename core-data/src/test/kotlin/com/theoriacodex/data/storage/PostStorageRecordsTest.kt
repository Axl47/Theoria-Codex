package com.theoriacodex.data.storage

import com.google.gson.Gson
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
    private val gson = Gson()

    @Test
    fun `versioned storage record round trips every supported Post field`() {
        val post = samplePost()
        val record = PostStorageCodec.encode(post)
        val json = gson.toJson(record)
        val reconstructedRecord = gson.fromJson(json, PostStorageRecord::class.java)

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertEquals(CURRENT_POST_STORAGE_SCHEMA_VERSION, record.schemaVersion)
        assertEquals(post, PostStorageCodec.decode(reconstructedRecord))
    }

    @Test
    fun `nhentai and hitomi records saved before fallback urls keep their copies as fallbacks`() {
        fun legacyRecord(source: String) = gson.fromJson(
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
            PostStorageRecord::class.java,
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

        val decoded = PostStorageCodec.decode(gson.fromJson(gson.toJson(PostStorageCodec.encode(post)), PostStorageRecord::class.java))

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

        val record = gson.fromJson(legacyJson, PostStorageRecord::class.java)
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
        )
    }
}
