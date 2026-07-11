package com.theoriacodex.app.creator

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorProfileUiTest {
    private val creatorBrowsingSources = setOf(
        SourceKey.PIXIV,
        SourceKey.GELBOORU,
        SourceKey.HITOMI,
        SourceKey.IWARA,
    )

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

        assertEquals("profile_name", creatorButtonLabel(post, creatorBrowsingSources))
    }

    @Test
    fun `creatorButtonLabel falls back to author name for supported sources`() {
        val post = samplePost(
            source = SourceKey.IWARA,
            authorName = "saved_author",
            creatorProfile = null,
        )

        assertEquals("saved_author", creatorButtonLabel(post, creatorBrowsingSources))
    }

    @Test
    fun `creatorButtonLabel hides unsupported sources`() {
        val post = samplePost(
            source = SourceKey.NHENTAI,
            authorName = "someone",
            creatorProfile = null,
        )

        assertNull(creatorButtonLabel(post, creatorBrowsingSources))
    }

    @Test
    fun `browseableCreatorProfile requires uploadsQuery`() {
        val profile = CreatorProfile(
            source = SourceKey.GELBOORU,
            displayName = "179338",
            profileId = "179338",
            uploadsQuery = null,
        )

        assertNull(browseableCreatorProfile(profile, creatorBrowsingSources))
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

        assertEquals(profile, browseableCreatorProfile(profile, creatorBrowsingSources))
    }

    @Test
    fun `creator presentation follows the supplied operational capability set`() {
        val profile = CreatorProfile(
            source = SourceKey.PIXIV,
            displayName = "Creator",
            profileId = "42",
            uploadsQuery = "42",
        )
        val post = samplePost(
            source = SourceKey.PIXIV,
            authorName = "Creator",
            creatorProfile = profile,
        )

        assertNull(browseableCreatorProfile(profile, emptySet()))
        assertTrue(creatorProfileActions(post, emptySet()).isEmpty())
        assertEquals("Creator", creatorButtonLabel(post, setOf(SourceKey.PIXIV)))
    }

    @Test
    fun `creator actions expose every exact distinct hitomi artist`() {
        val first = CreatorProfile(
            source = SourceKey.HITOMI,
            displayName = "Artist One",
            profileId = "artist-one",
            uploadsQuery = "artist:artist-one",
        )
        val second = CreatorProfile(
            source = SourceKey.HITOMI,
            displayName = "Artist Two",
            profileId = "artist-two",
            uploadsQuery = "artist:artist-two",
        )
        val post = samplePost(
            source = SourceKey.HITOMI,
            authorName = "legacy",
            creatorProfile = first,
            creatorProfiles = listOf(first, second, first),
        )

        val actions = creatorProfileActions(post, creatorBrowsingSources)

        assertEquals(listOf(first, second), actions.map(CreatorProfileAction::profile))
        assertEquals(listOf("Artist One", "Artist Two"), actions.map(CreatorProfileAction::label))
        assertTrue(actions.none(CreatorProfileAction::requiresLegacyResolution))
        assertTrue(SourceKey.HITOMI in creatorBrowsingSources)
    }

    @Test
    fun `hitomi creator actions require the exact canonical profile contract`() {
        val valid = CreatorProfile(
            source = SourceKey.HITOMI,
            displayName = "Artist One",
            profileId = "artist one",
            uploadsQuery = "artist:artist one",
        )
        val malformedUnicode = "\uD800"
        val invalidProfiles = listOf(
            valid.copy(uploadsQuery = "artist:someone else"),
            valid.copy(profileId = "Artist One", uploadsQuery = "artist:Artist One"),
            valid.copy(profileId = "artist/one", uploadsQuery = "artist:artist/one"),
            valid.copy(profileId = malformedUnicode, uploadsQuery = "artist:$malformedUnicode"),
        )

        assertEquals(valid, browseableCreatorProfile(valid, creatorBrowsingSources))
        invalidProfiles.forEach { profile ->
            assertNull(profile.toString(), browseableCreatorProfile(profile, creatorBrowsingSources))
        }

        val post = samplePost(
            source = SourceKey.HITOMI,
            authorName = "Not actionable",
            creatorProfile = null,
            creatorProfiles = invalidProfiles,
        )
        assertTrue(creatorProfileActions(post, creatorBrowsingSources).isEmpty())
    }

    @Test
    fun `legacy creator action exists only when it has a usable label`() {
        val legacy = samplePost(
            source = SourceKey.HITOMI,
            authorName = "Legacy Artist",
            creatorProfile = null,
            creatorProfiles = emptyList(),
        )
        val empty = samplePost(
            source = SourceKey.HITOMI,
            authorName = "   ",
            creatorProfile = null,
            creatorProfiles = emptyList(),
        )

        assertEquals("Legacy Artist", creatorProfileActions(legacy, creatorBrowsingSources).single().label)
        assertTrue(creatorProfileActions(legacy, creatorBrowsingSources).single().requiresLegacyResolution)
        assertTrue(creatorProfileActions(empty, creatorBrowsingSources).isEmpty())
    }

    @Test
    fun `invalid explicit profiles do not create a dead creator action`() {
        val post = samplePost(
            source = SourceKey.HITOMI,
            authorName = "Not actionable",
            creatorProfile = null,
            creatorProfiles = listOf(
                CreatorProfile(
                    source = SourceKey.HITOMI,
                    displayName = "Missing query",
                    uploadsQuery = null,
                ),
            ),
        )

        assertTrue(creatorProfileActions(post, creatorBrowsingSources).isEmpty())
        assertFalse(
            creatorProfileActions(post, creatorBrowsingSources)
                .any(CreatorProfileAction::requiresLegacyResolution),
        )
    }

    private fun samplePost(
        source: SourceKey,
        authorName: String?,
        creatorProfile: CreatorProfile?,
        creatorProfiles: List<CreatorProfile> = listOfNotNull(creatorProfile),
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
            creatorProfiles = creatorProfiles,
        )
    }
}
