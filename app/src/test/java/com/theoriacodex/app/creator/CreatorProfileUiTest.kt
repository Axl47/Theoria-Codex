package com.theoriacodex.app.creator

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreatorProfileUiTest {
    @Test
    fun `creatorButtonLabel uses browseable creator profile name when present`() {
        val post = samplePost(
            source = SourceKey.PIXIV,
            authorName = "fallback_author",
            creatorProfile = CreatorProfile(
                source = SourceKey.PIXIV,
                displayName = "profile_name",
                profileId = "201823",
                uploadsQuery = "201823",
            ),
        )

        assertEquals("profile_name", creatorButtonLabel(post))
    }

    @Test
    fun `creatorButtonLabel falls back to author name for supported sources`() {
        val post = samplePost(
            source = SourceKey.IWARA,
            authorName = "saved_author",
            creatorProfile = null,
        )

        assertEquals("saved_author", creatorButtonLabel(post))
    }

    @Test
    fun `creatorButtonLabel hides unsupported sources`() {
        val post = samplePost(
            source = SourceKey.NHENTAI,
            authorName = "someone",
            creatorProfile = null,
        )

        assertNull(creatorButtonLabel(post))
    }

    @Test
    fun `browseableCreatorProfile requires uploadsQuery`() {
        val profile = CreatorProfile(
            source = SourceKey.GELBOORU,
            displayName = "179338",
            profileId = "179338",
            uploadsQuery = null,
        )

        assertNull(browseableCreatorProfile(profile))
    }

    @Test
    fun `browseableCreatorProfile accepts iwara creators with uploads query`() {
        val profile = CreatorProfile(
            source = SourceKey.IWARA,
            displayName = "mmdparadaise",
            profileId = "2f2a6a22-0000-4000-8000-111111111111",
            profileUrl = "https://www.iwara.tv/profile/mmdparadaise/videos",
            uploadsQuery = "2f2a6a22-0000-4000-8000-111111111111",
        )

        assertEquals(profile, browseableCreatorProfile(profile))
    }

    private fun samplePost(
        source: SourceKey,
        authorName: String?,
        creatorProfile: CreatorProfile?,
    ): Post {
        return Post(
            id = PostId(source = source, sourcePostId = "1"),
            preview = ImageRef(url = "https://example.com/1.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://example.com/full/1.jpg", localPath = null, mime = "image/jpeg"),
            pageUrl = "https://example.com/post/1",
            width = 100,
            height = 100,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = authorName,
            createdAtEpochMs = 1L,
            creatorProfile = creatorProfile,
        )
    }
}
