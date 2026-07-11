package com.theoriacodex.data.storage

import com.google.gson.Gson
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
            isAnimated = true,
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
            full = media,
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
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "user",
                )
            ),
            creatorProfiles = listOf(creator),
        )
    }
}
